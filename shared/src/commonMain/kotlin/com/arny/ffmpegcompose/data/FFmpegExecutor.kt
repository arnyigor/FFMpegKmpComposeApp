package com.arny.ffmpegcompose.data

import com.arny.ffmpegcompose.components.home.ConvertType
import com.arny.ffmpegcompose.data.config.ConfigManager
import com.arny.ffmpegcompose.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader

class FFmpegExecutor(
    private val configManager: ConfigManager,
    private val json: Json
) {

    private var currentProcess: Process? = null
    private val _isRunning = MutableStateFlow(false)

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

            val ffprobePath =
                configManager.getFfmpegPath()?.parent?.resolve("ffprobe.exe").toString()

            val command = listOf(
                ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_streams",
                "-show_format",
                "-select_streams", "v:0",
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

            stdoutJob.cancel()
            stderrJob.cancel()

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
                    add("-map"); add("0:v")
                    add("-map"); add("1:a")
                    add("-c:v"); add(VideoCodec.COPY.codecName)
                    add("-c:a"); add(params.audioCodec.codecName)
                } else {
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

                // аудио‑кодек
                add("-c:a"); add(params.audioCodec.codecName)
                if (params.audioCodec == AudioCodec.AAC) {
                    add("-b:a"); add("192k")
                }
            }

            ConvertType.AUDIO_EXTRACT -> {
                // убираем видео, оставляем только аудио
                add("-vn")
                add("-acodec"); add(params.audioCodec.codecName)
            }
        }

        /* ---------- 8. Дополнительно ---------- */
        if (params.replaceAudio && params.audioFile != null) {
            add("-shortest")          // если audio shorter than video
        }

        /* ---------- 9. Перезапись и выходной файл ---------- */
        add("-y")
        add(params.outputFile)
    }

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
                            outTimeMs = progressData["out_time_ms"]?.toLongOrNull() ?: 0L,
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
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (e: Exception) {
                    process.destroyForcibly()
                }
            }
        }
        _isRunning.value = false
        currentProcess = null
    }
}
