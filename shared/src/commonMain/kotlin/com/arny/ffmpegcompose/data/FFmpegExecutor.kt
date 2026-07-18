package com.arny.ffmpegcompose.data

import com.arny.ffmpegcompose.components.home.ConvertType
import com.arny.ffmpegcompose.data.config.ConfigManager
import com.arny.ffmpegcompose.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

class FFmpegExecutor(
    private val configManager: ConfigManager,
    private val json: Json
) {

    private var currentProcess: Process? = null
    @Volatile
    private var previewProcess: Process? = null
    @Volatile
    private var previewVideoProcess: Process? = null
    private val _isRunning = MutableStateFlow(false)

    suspend fun preview(
        inputFile: String,
        startMs: Long,
        endMs: Long?,
        volume: Int,
        hasVideo: Boolean,
        onFrame: (ByteArray) -> Unit,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                stopPreview()
                val ffmpegPath = configManager.getFfmpegPath() ?: error("FFmpeg не настроен")
                val ffplayName = if (ffmpegPath.fileName.toString().endsWith(".exe", true)) "ffplay.exe" else "ffplay"
                val ffplayPath = ffmpegPath.parent.resolve(ffplayName).toFile()
                require(ffplayPath.isFile) { "Не найден ${ffplayPath.absolutePath}" }
                val audioCommand = buildList {
                    add(ffplayPath.absolutePath)
                    add("-autoexit")
                    add("-hide_banner")
                    add("-loglevel")
                    add("error")
                    add("-nostats")
                    add("-nodisp")
                    add("-volume")
                    add(volume.coerceIn(0, 100).toString())
                    if (startMs > 0) {
                        add("-ss")
                        add(ConversionParams(
                            inputFile = inputFile,
                            outputFile = "",
                            convertType = ConvertType.STREAM_COPY,
                        ).formatTimeMs(startMs))
                    }
                    endMs?.takeIf { it > startMs }?.let {
                        add("-t")
                        add(((it - startMs) / 1_000.0).toString())
                    }
                    add(inputFile)
                }
                val videoProcess = if (hasVideo) {
                    val videoCommand = buildList {
                        add(ffmpegPath.toString())
                        add("-hide_banner")
                        add("-loglevel")
                        add("error")
                        add("-re")
                        if (startMs > 0) {
                            add("-ss")
                            add(String.format(Locale.US, "%.3f", startMs / 1_000.0))
                        }
                        add("-i")
                        add(inputFile)
                        endMs?.takeIf { it > startMs }?.let {
                            add("-t")
                            add(String.format(Locale.US, "%.3f", (it - startMs) / 1_000.0))
                        }
                        add("-an")
                        add("-vf")
                        add("fps=24,scale=960:-2")
                        add("-q:v")
                        add("5")
                        add("-f")
                        add("image2pipe")
                        add("-vcodec")
                        add("mjpeg")
                        add("pipe:1")
                    }
                    ProcessBuilder(videoCommand)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .also { previewVideoProcess = it }
                } else null
                val process = ProcessBuilder(audioCommand)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                previewProcess = process
                coroutineScope {
                    val videoReader = videoProcess?.let { player ->
                        launch(Dispatchers.IO) { readMjpegFrames(player.inputStream, onFrame) }
                    }
                    val exitCode = process.waitFor()
                    val completedNormally = previewProcess === process
                    if (completedNormally) previewProcess = null
                    videoReader?.join()
                    if (previewVideoProcess === videoProcess) previewVideoProcess = null
                    check(exitCode == 0 || !completedNormally) { "ffplay завершился с кодом $exitCode" }
                }
            }
        }

    private fun readMjpegFrames(stream: java.io.InputStream, onFrame: (ByteArray) -> Unit) {
        BufferedInputStream(stream, 128 * 1024).use { input ->
            val frame = ByteArrayOutputStream(256 * 1024)
            var previous = -1
            var capturing = false
            while (true) {
                val current = input.read()
                if (current < 0) break
                if (!capturing) {
                    if (previous == 0xFF && current == 0xD8) {
                        frame.reset()
                        frame.write(0xFF)
                        frame.write(0xD8)
                        capturing = true
                    }
                    previous = current
                    continue
                }
                frame.write(current)
                if (previous == 0xFF && current == 0xD9) {
                    onFrame(frame.toByteArray())
                    frame.reset()
                    capturing = false
                    previous = -1
                } else {
                    previous = current
                }
                if (frame.size() > 8 * 1024 * 1024) {
                    frame.reset()
                    capturing = false
                    previous = -1
                }
            }
        }
    }

    suspend fun createPreviewFrame(inputFile: String, positionMs: Long): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ffmpegPath = configManager.getFfmpegPath() ?: error("FFmpeg не настроен")
                val command = listOf(
                    ffmpegPath.toString(), "-hide_banner", "-loglevel", "error",
                    "-ss", String.format(Locale.US, "%.3f", positionMs.coerceAtLeast(0L) / 1_000.0),
                    "-i", inputFile,
                    "-frames:v", "1", "-vf", "scale=960:-2", "-q:v", "4",
                    "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1",
                )
                val process = ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                val frame = process.inputStream.use { it.readBytes() }
                check(process.waitFor() == 0 && frame.isNotEmpty()) { "Не удалось получить кадр" }
                frame
            }
        }

    fun stopPreview() {
        val process = previewProcess
        val videoProcess = previewVideoProcess
        previewProcess = null
        previewVideoProcess = null
        process?.let {
            runCatching {
                it.outputStream.write("q\n".toByteArray())
                it.outputStream.flush()
            }
            it.destroy()
            if (it.isAlive) it.destroyForcibly()
        }
        videoProcess?.destroy()
        if (videoProcess?.isAlive == true) videoProcess.destroyForcibly()
    }

    /**
     * Получение информации о медиа файле (JSON)
     */
    suspend fun getMediaInfo(inputFile: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            if (!configManager.isFfmpegConfigured()) {
                return@withContext Result.failure(
                    Exception("ffprobe.exe не найден рядом с ffmpeg.exe")
                )
            }

            val ffmpegPath = configManager.getFfmpegPath()
                ?: return@withContext Result.failure(Exception("FFmpeg не настроен"))
            val ffprobeName = if (ffmpegPath.fileName.toString().endsWith(".exe", true)) {
                "ffprobe.exe"
            } else {
                "ffprobe"
            }
            val ffprobePath = ffmpegPath.parent.resolve(ffprobeName).toString()

            val command = listOf(
                ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                "-show_format",
                inputFile
            )

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val jsonOutput = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                return@withContext Result.failure(
                    Exception("ffprobe завершился с ошибкой (код $exitCode)")
                )
            }

            val mediaInfo = json.decodeFromString<MediaInfo>(jsonOutput)
            Result.success(mediaInfo)

        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Конвертация с прогрессом и гибкими параметрами
     */
    suspend fun convertWithProgress(
        params: ConversionParams,
        onProgress: (ConversionProgress) -> Unit,
        onLog: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!configManager.isFfmpegConfigured()) {
                return@withContext Result.failure(
                    Exception("ffmpeg.exe не найден")
                )
            }

            _isRunning.value = true

            // Строим команду в зависимости от параметров
            val command = buildFFmpegCommand(params)
            val string = "Команда: ${command.joinToString(" ")}"
            println(string)
            onLog(string)

            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)

            currentProcess = processBuilder.start()

            // Читаем прогресс из stdout (key=value)
            val stdoutJob = launch {
                parseProgressStream(
                    reader = currentProcess?.inputStream?.bufferedReader(),
                    onProgress = onProgress,
                )
            }

            // Читаем ошибки из stderr
            val stderrJob = launch {
                currentProcess?.errorStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            onLog("[stderr] $line")
                        }
                    }
                }
            }

            val exitCode = currentProcess?.waitFor() ?: -1

            stdoutJob.join()
            stderrJob.join()

            _isRunning.value = false
            currentProcess = null

            if (exitCode == 0) {
                Result.success(params.outputFile)
            } else {
                Result.failure(Exception("FFmpeg завершился с кодом $exitCode"))
            }

        } catch (e: Exception) {
            _isRunning.value = false
            currentProcess = null
            Result.failure(e)
        }
    }

    suspend fun mixSmartVoiceReplacement(
        inputFile: String,
        outputFile: String,
        backgroundAudioFile: String,
        originalVoiceFile: String,
        replacementVoiceFile: String,
        originalVoiceVolumePercent: Int,
        replacementVoiceVolumePercent: Int,
        videoCodec: VideoCodec,
        preset: String,
        crf: Int,
        trimStartMs: Long?,
        trimEndMs: Long?,
        onProgress: (ConversionProgress) -> Unit,
        onLog: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!configManager.isFfmpegConfigured()) {
                return@withContext Result.failure(Exception("ffmpeg.exe не найден"))
            }
            _isRunning.value = true
            val helperParams = ConversionParams(
                inputFile = inputFile,
                outputFile = outputFile,
                convertType = ConvertType.STREAM_COPY,
            )
            val originalVoiceVolume = originalVoiceVolumePercent.coerceIn(0, 100) / 100.0
            val replacementVoiceVolume = replacementVoiceVolumePercent.coerceIn(0, 200) / 100.0
            val command = buildList {
                add(configManager.getFfmpegPath().toString())
                trimStartMs?.let { startMs ->
                    add("-ss")
                    add(helperParams.formatTimeMs(startMs))
                }
                add("-i"); add(inputFile)
                add("-i"); add(backgroundAudioFile)
                add("-i"); add(originalVoiceFile)
                add("-i"); add(replacementVoiceFile)
                val durationMs = when {
                    trimStartMs != null && trimEndMs != null -> trimEndMs - trimStartMs
                    trimEndMs != null -> trimEndMs
                    else -> null
                }
                durationMs?.takeIf { it > 0L }?.let {
                    add("-t")
                    add(helperParams.formatTimeMs(it))
                }
                add("-filter_complex")
                add(
                    "[1:a]volume=1.000[bg];" +
                            "[2:a]volume=${String.format(Locale.US, "%.3f", originalVoiceVolume)}[ov];" +
                            "[3:a]volume=${String.format(Locale.US, "%.3f", replacementVoiceVolume)}[nv];" +
                            "[bg][ov][nv]amix=inputs=3:duration=first:dropout_transition=0," +
                            "loudnorm=I=-16:LRA=11:TP=-1.5[aout]"
                )
                add("-map"); add("0:v:0?")
                add("-map"); add("[aout]")
                add("-map_metadata"); add("0")
                add("-c:v"); add(videoCodec.codecName)
                if (videoCodec in listOf(VideoCodec.LIBX264, VideoCodec.LIBX265)) {
                    add("-preset"); add(preset)
                    add("-crf"); add(crf.toString())
                }
                if (videoCodec == VideoCodec.LIBX265 && outputFile.isMp4LikeContainer()) {
                    add("-tag:v"); add("hvc1")
                }
                add("-c:a"); add(AudioCodec.AAC.codecName)
                add("-b:a"); add("192k")
                add("-shortest")
                if (outputFile.isMp4LikeContainer()) {
                    add("-movflags"); add("+faststart")
                }
                add("-progress"); add("-")
                add("-nostats")
                add("-y")
                add(outputFile)
            }
            val string = "Команда: ${command.joinToString(" ")}"
            println(string)
            onLog(string)

            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            currentProcess = process
            coroutineScope {
                val stdoutJob = launch {
                    parseProgressStream(process.inputStream.bufferedReader(), onProgress)
                }
                val stderrJob = launch {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (line.isNotBlank()) onLog("[stderr] $line") }
                    }
                }
                val exitCode = process.waitFor()
                stdoutJob.join()
                stderrJob.join()
                check(exitCode == 0) { "FFmpeg завершился с кодом $exitCode" }
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isRunning.value = false
            currentProcess = null
        }
    }

    /**
     * Конструирует список аргументов FFmpeg.
     *
     * @return список строк‑аргументов, готовый к передаче в ProcessBuilder
     */
    /**
     * Конструирует список аргументов FFmpeg.
     *
     * @return список строк‑аргументов, готовый к передаче в ProcessBuilder
     */
    private fun buildFFmpegCommand(params: ConversionParams): List<String> = buildList {
        // 1. Путь к исполняемому файлу ffmpeg
        add(configManager.getFfmpegPath().toString())

        val effectiveStrategy = params.getEffectiveTrimStrategy()
        // Учитываем обе границы обрезки (начало или конец)
        val hasTrim = params.trimStartMs != null || params.trimEndMs != null
        val outputAudioCodec = params.audioCodec.withContainerCompatibility(params.outputFile)

        /* ---------- 2. FAST‑seek (до -i) ---------- */
        if (hasTrim && effectiveStrategy == TrimStrategy.FAST) {
            params.trimStartMs?.let { startMs ->
                add("-ss")
                add(params.formatTimeMs(startMs))
            }
        }

        /* ---------- 3. Входные файлы ---------- */
        add("-i"); add(params.inputFile)

        if (params.replaceAudio && params.audioFile != null) {
            add("-i"); add(params.audioFile)
        }

        /* ---------- 4. ACCURATE‑seek (после -i) ---------- */
        if (hasTrim && effectiveStrategy == TrimStrategy.ACCURATE) {
            params.trimStartMs?.let { startMs ->
                add("-ss")
                add(params.formatTimeMs(startMs))
            }
        }

        /* ---------- 5. Обрезка: длительность / конечное время ---------- */
        if (hasTrim) {
            when {
                // обе границы заданы → задаём длительность
                params.trimStartMs != null && params.trimEndMs != null -> {
                    val duration = params.trimEndMs!! - params.trimStartMs!!
                    add("-t")
                    add(params.formatTimeMs(duration))
                }
                // только конец – используем -to
                params.trimEndMs != null -> {
                    add("-to")
                    add(params.formatTimeMs(params.trimEndMs))
                }
            }
        }

        /* ---------- 6. Прогресс и статистика ---------- */
        add("-progress"); add("-")
        add("-nostats")

        /* ---------- 7. Режим конвертации ---------- */
        when (params.convertType) {
            ConvertType.STREAM_COPY -> {
                if (params.replaceAudio && params.audioFile != null) {
                    val mp4LikeOutput = params.outputFile.isMp4LikeContainer()
                    add("-map"); add("0:v:0")
                    add("-map"); add("1:a:0")
                    if (!mp4LikeOutput) {
                        add("-map"); add("0:s?")
                    }
                    add("-map_metadata"); add("0")
                    add("-map_chapters"); add("0")
                    add("-c:v"); add(VideoCodec.COPY.codecName)
                    add("-c:a"); add(outputAudioCodec.codecName)
                    if (outputAudioCodec == AudioCodec.AAC) {
                        add("-b:a"); add("192k")
                    }
                    if (!mp4LikeOutput) {
                        add("-c:s"); add("copy")
                    }
                    if (mp4LikeOutput) {
                        add("-movflags"); add("+faststart")
                    }
                } else {
                    add("-map"); add("0")
                    add("-c"); add("copy")
                }
            }

            ConvertType.CONVERT -> {
                if (params.replaceAudio && params.audioFile != null) {
                    add("-map"); add("0:v")
                    add("-map"); add("1:a")
                }

                // видео‑кодек
                add("-c:v"); add(params.videoCodec.codecName)
                if (params.videoCodec in listOf(VideoCodec.LIBX264, VideoCodec.LIBX265)) {
                    add("-preset"); add(params.preset)
                    add("-crf"); add(params.crf.toString())
                }
                if (params.videoCodec == VideoCodec.LIBX265 && params.outputFile.isMp4LikeContainer()) {
                    add("-tag:v"); add("hvc1")
                }

                // аудио‑кодек
                add("-c:a"); add(outputAudioCodec.codecName)
                if (outputAudioCodec == AudioCodec.AAC) {
                    add("-b:a"); add("192k")
                }
            }

            ConvertType.AUDIO_EXTRACT -> {
                // убираем видео, оставляем только аудио
                add("-map")
                add(params.audioStreamIndex?.let { "0:$it?" } ?: "0:a:0?")
                add("-vn")
                add("-acodec"); add(params.audioCodec.codecName)
                params.audioChannels?.let { channels ->
                    add("-ac"); add(channels.toString())
                }
                params.audioSampleRate?.let { sampleRate ->
                    add("-ar"); add(sampleRate.toString())
                }
            }

            ConvertType.TRANSCRIBE -> error("TRANSCRIBE должен запускаться через Whisper pipeline")
        }

        /* ---------- 8. Дополнительно ---------- */
        if (params.replaceAudio && params.audioFile != null) {
            add("-shortest")          // если audio shorter than video
        }

        /* ---------- 9. Перезапись и выходной файл ---------- */
        add("-y")
        add(params.outputFile)
    }

    private fun AudioCodec.withContainerCompatibility(outputFile: String): AudioCodec {
        if (this == AudioCodec.COPY) return this
        return when (outputFile.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "webm", "ogg", "opus" -> AudioCodec.OPUS
            else -> this
        }
    }

    private fun String.isMp4LikeContainer(): Boolean =
        substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("mp4", "m4v", "mov")

    /**
     * Парсинг прогресса из key=value потока
     */
    private suspend fun parseProgressStream(
        reader: BufferedReader?,
        onProgress: (ConversionProgress) -> Unit,
    ) {
        reader?.useLines { lines ->
            val progressData = mutableMapOf<String, String>()

            lines.forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    progressData[key] = value

                    // Когда получаем "progress", данные полные
                    if (key == "progress") {
                        val progress = ConversionProgress(
                            frame = progressData["frame"]?.toIntOrNull() ?: 0,
                            fps = progressData["fps"]?.toFloatOrNull() ?: 0f,
                            outTimeUs = progressData["out_time_us"]?.toLongOrNull()
                                ?: progressData["out_time_ms"]?.toLongOrNull()
                                ?: 0L,
                            totalSize = progressData["total_size"]?.toLongOrNull() ?: 0L,
                            bitrate = progressData["bitrate"]?.replace("kbits/s", "")
                                ?.toFloatOrNull() ?: 0f,
                            speed = progressData["speed"]?.replace("x", "")
                                ?.toFloatOrNull() ?: 0f,
                            progress = value
                        )

                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }

                        progressData.clear()
                    }
                }
            }
        }
    }

    /**
     * Отмена текущей операции
     */
    fun cancel() {
        currentProcess?.let { process ->
            if (process.isAlive) {
                try {
                    // Отправляем 'q' для graceful shutdown
                    process.outputStream.write("q\n".toByteArray())
                    process.outputStream.flush()

                    // Ждём 2 секунды
                    process.destroy()
                    if (process.isAlive) process.destroyForcibly()
                } catch (e: Exception) {
                    process.destroyForcibly()
                }
            }
        }
        _isRunning.value = false
        currentProcess = null
    }
}
