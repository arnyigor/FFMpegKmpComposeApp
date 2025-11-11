package com.arny.ffmpegcompose.data

import com.arny.ffmpegcompose.components.home.ConvertType
import com.arny.ffmpegcompose.data.config.ConfigManager
import com.arny.ffmpegcompose.data.models.AudioCodec
import com.arny.ffmpegcompose.data.models.ConversionParams
import com.arny.ffmpegcompose.data.models.ConversionProgress
import com.arny.ffmpegcompose.data.models.MediaInfo
import com.arny.ffmpegcompose.data.models.VideoCodec
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

            onLog("Команда: ${command.joinToString(" ")}")

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
                Result.success("Конвертация завершена: ${params.outputFile}")
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
     * Построение команды FFmpeg в зависимости от параметров
     */
    private fun buildFFmpegCommand(params: ConversionParams): List<String> {
        return buildList {
            add(configManager.getFfmpegPath().toString())

            // === ВХОДНЫЕ ФАЙЛЫ ===
            add("-i")
            add(params.inputFile)

            // Если заменяем аудио - добавляем второй вход
            if (params.replaceAudio && params.audioFile != null) {
                add("-i")
                add(params.audioFile)
            }

            // === ПРОГРЕСС И СТАТИСТИКА ===
            add("-progress")
            add("-")        // Прогресс в stdout (key=value)
            add("-nostats")  // Отключаем статистику в stderr

            // === РЕЖИМ КОНВЕРТАЦИИ ===
            when (params.convertType) {
                ConvertType.STREAM_COPY -> {
                    // Прямопотоковое копирование
                    if (params.replaceAudio && params.audioFile != null) {
                        // Копируем видео, перекодируем аудио
                        add("-map")
                        add("0:v")  // Видео из первого входа
                        add("-map")
                        add("1:a")  // Аудио из второго входа
                        add("-c:v")
                        add(VideoCodec.COPY.codecName)
                        add("-c:a")
                        add(params.audioCodec.codecName)
                    } else {
                        // Копируем всё как есть
                        add("-c")
                        add("copy")
                    }
                }

                ConvertType.CONVERT -> {
                    // Полная конвертация
                    if (params.replaceAudio && params.audioFile != null) {
                        // Карта потоков: видео из первого, аудио из второго
                        add("-map")
                        add("0:v")
                        add("-map")
                        add("1:a")
                    }

                    // Видео кодек
                    add("-c:v")
                    add(params.videoCodec.codecName)

                    // Настройки для libx264/libx265
                    if (params.videoCodec in listOf(VideoCodec.LIBX264, VideoCodec.LIBX265)) {
                        add("-preset")
                        add(params.preset)
                        add("-crf")
                        add(params.crf.toString())
                    }

                    // Аудио кодек
                    add("-c:a")
                    add(params.audioCodec.codecName)

                    // Битрейт для AAC
                    if (params.audioCodec == AudioCodec.AAC) {
                        add("-b:a")
                        add("192k")
                    }
                }
            }

            // === ДОПОЛНИТЕЛЬНЫЕ ОПЦИИ ===
            // Если заменяем аудио, обрезаем по длине видео
            if (params.replaceAudio && params.audioFile != null) {
                add("-shortest")
            }

            // Перезапись выходного файла
            add("-y")

            // Выходной файл
            add(params.outputFile)
        }
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
