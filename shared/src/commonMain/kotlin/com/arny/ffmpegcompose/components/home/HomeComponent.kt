package com.arny.ffmpegcompose.components.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.arny.ffmpegcompose.components.utils.*
import com.arny.ffmpegcompose.data.FFmpegExecutor
import com.arny.ffmpegcompose.data.models.*
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.math.abs

interface HomeComponent : HomeCallbacks {
    val state: StateFlow<HomeUiState>
}

object EmptyHomeCallbacks : HomeCallbacks {
    override fun onSelectInputFile() {}
    override fun onSelectOutputFile() {}
    override fun onSelectAudioFile() {}
    override fun onGetMediaInfo() {}
    override fun onStartConversion() {}
    override fun onCancelConversion() {}
    override fun onClearLogs() {}
    override fun onOpenOutputFolder() {}
    override fun onAddAudioToggled(checked: Boolean) {}
    override fun onTrimToggled(checked: Boolean) {}
    override fun onChangeConvertType(type: ConvertType) {}
    override fun onTrimEndChange(trimEnd: Long?) {}
    override fun onTrimStartChange(startMs: Long?) {}
    override fun onTrimStrategyChange(trimStrategy: TrimStrategy) {}
}

interface HomeCallbacks {
    fun onSelectInputFile()
    fun onSelectOutputFile()
    fun onSelectAudioFile()
    fun onGetMediaInfo()
    fun onStartConversion()
    fun onCancelConversion()
    fun onClearLogs()
    fun onOpenOutputFolder()
    fun onAddAudioToggled(checked: Boolean)
    fun onTrimToggled(checked: Boolean)
    fun onChangeConvertType(type: ConvertType)
    fun onTrimStartChange(startMs: Long?)
    fun onTrimEndChange(trimEnd: Long?)
    fun onTrimStrategyChange(trimStrategy: TrimStrategy)
}

data class HomeUiState(
    val convertType: ConvertType = ConvertType.STREAM_COPY,
    val inputFile: String? = null,
    val outputFile: String? = null,
    val audioFile: String? = null,
    val replaceAudioSelected: Boolean = false,
    val streamCopySelected: Boolean = false,
    val trimSelected: Boolean = false,
    val mediaInfo: MediaInfo? = null,
    val conversionProgress: ConversionProgress? = null,
    val isProcessing: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val error: String? = null,
    val successMessage: String? = null,
    val totalDurationMs: Long = 0L,
    val trimParams: TrimParams = TrimParams(),
)

data class TrimParams(
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val trimStrategy: TrimStrategy = TrimStrategy.AUTO,
    val totalDurationMs: Long = 0,
)

enum class ConvertType(
    val title: String
) {
    STREAM_COPY("Прямопотоковое копирование"),
    CONVERT("Конвертация"),
    AUDIO_EXTRACT("Извлечь аудио"),
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel {
    DEBUG, INFO, SUCCESS, WARNING, ERROR
}

class DefaultHomeComponent(
    componentContext: ComponentContext,
    private val ffmpegExecutor: FFmpegExecutor
) : HomeComponent, ComponentContext by componentContext {

    private val scope = coroutineScope(SupervisorJob())

    private val _state = MutableStateFlow(HomeUiState())
    override val state: StateFlow<HomeUiState> = _state.asStateFlow()

    override fun onChangeConvertType(type: ConvertType) {
        _state.update { it.copy(convertType = type) }
        addLog("Изменен тип конвертации на ${type.title}", LogLevel.INFO)
    }

    override fun onAddAudioToggled(checked: Boolean) {
        _state.update { it.copy(replaceAudioSelected = checked) }
        addLog(
            if (checked) "Включена замена аудио дорожки"
            else "Выключена замена аудио дорожки",
            LogLevel.INFO
        )
    }

    override fun onTrimToggled(checked: Boolean) {
        _state.update { it.copy(trimSelected = checked) }
        addLog(
            if (checked) "Обрезка включена" else "Обрезка отключена",
            LogLevel.INFO
        )
    }

    override fun onSelectInputFile() {
        val fileDialog = FileDialog(null as Frame?, "Выберите видео файл", FileDialog.LOAD)
        fileDialog.file = "*.mp4;*.avi;*.mkv;*.mov;*.webm"
        fileDialog.isVisible = true

        val directory = fileDialog.directory
        val file = fileDialog.file

        if (directory != null && file != null) {
            val path = Paths.get(directory, file).absolutePathString()
            _state.update { it.copy(inputFile = path, mediaInfo = null) }
            addLog("Выбран входной файл: $path", LogLevel.INFO)
        }
    }

    fun onOpenFolder(filePath: String) {
        try {
            val file = File(filePath)
            // Проверяем существование переданного пути (опционально – можно убрать)
            require(file.exists()) { "Путь '$filePath' не найден" }

            val folder = when {
                file.isDirectory -> file
                else -> requireNotNull(file.parentFile) {
                    "У файла '$filePath' отсутствует родительская директория"
                }
            }
            Desktop.getDesktop().open(folder)
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    override fun onSelectOutputFile() {
        val fileDialog = FileDialog(null as Frame?, "Сохранить как", FileDialog.SAVE)
        val extension = when (_state.value.convertType) {
            ConvertType.STREAM_COPY -> ".mp4"
            ConvertType.CONVERT -> ".mp4"
            ConvertType.AUDIO_EXTRACT -> ".wav" // сделать опциональным
        }

        fileDialog.file = "output$extension"
        fileDialog.isVisible = true

        val directory = fileDialog.directory
        val fileName = fileDialog.file

        if (directory != null && fileName != null) {
            val paths = Paths.get(directory, replaceDotsWithUnderscores(fileName))
            var outputFile = paths.absolutePathString()
            if (paths.extension.isEmpty()) {
                outputFile += extension
            }
            _state.update { it.copy(outputFile = outputFile) }
            addLog("Выбран выходной файл: $outputFile", LogLevel.INFO)
        }
    }

    fun replaceDotsWithUnderscores(fileName: String): String = fileName.replace(".", "_")

    override fun onSelectAudioFile() {
        val fileDialog = FileDialog(null as Frame?, "Выберите аудио файл", FileDialog.LOAD)
        fileDialog.file = "*.mp3;*.wav;*.flac;*.m4a;*.aac;*.ogg;*.opus"
        fileDialog.isVisible = true

        val directory = fileDialog.directory
        val file = fileDialog.file

        if (directory != null && file != null) {
            val path = Paths.get(directory, file).absolutePathString()
            _state.update { it.copy(audioFile = path) }
            addLog("Выбран аудио файл: $path", LogLevel.INFO)
            getMediaInfo(path)
        } else {
            addLog("Не выбран аудио файл или директория.", LogLevel.WARNING)
        }
    }

    override fun onGetMediaInfo() {
        val inputFile = _state.value.inputFile ?: return
        getMediaInfo(inputFile)
    }

    private fun getMediaInfo(inputFile: String) {
        scope.launch {
            _state.update { it.copy(isProcessing = true, error = null) }
            addLog("=== АНАЛИЗ МЕДИА ФАЙЛА ===", LogLevel.INFO)

            val result = ffmpegExecutor.getMediaInfo(inputFile)

            result.onSuccess { mediaInfo ->
                val totalDurationMs = mediaInfo.format.duration.toDurationLongMs() ?: 0L
                _state.update {
                    it.copy(
                        mediaInfo = enrichMediaInfo(mediaInfo),
                        isProcessing = false,
                        totalDurationMs = totalDurationMs
                    )
                }
                addLog("✓ Анализ завершён", LogLevel.SUCCESS)
                addLog("MediaInfo: $mediaInfo", LogLevel.INFO)

                mediaInfo.streams.forEach { stream ->
                    val totalFrames = stream.nbFrames?.toLongOrNull()
                    when (stream.codecType) {
                        "video" -> {
                            val frames = totalFrames?.let { "кадров: $it" }.orEmpty()
                            addLog(
                                "Видео: ${stream.codecName} ${stream.width}x${stream.height} $frames",
                                LogLevel.INFO
                            )
                        }

                        "audio" -> {
                            addLog(
                                "Аудио: ${stream.codecName} ${stream.sampleRate ?: "N/A"} Hz",
                                LogLevel.INFO
                            )
                        }
                    }
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isProcessing = false,
                        error = error.message
                    )
                }
                addLog("✗ Ошибка: ${error.message}", LogLevel.ERROR)
            }
        }
    }

    override fun onStartConversion() {
        val currentState = _state.value
        val inputFile = currentState.inputFile
        val outputFile = currentState.outputFile

        if (inputFile == null) {
            addLog("✗ Не выбран входной файл", LogLevel.ERROR)
            return
        }

        if (outputFile == null) {
            addLog("✗ Не выбран выходной файл", LogLevel.ERROR)
            return
        }

        // Валидация для замены аудио
        if (currentState.replaceAudioSelected && currentState.audioFile == null) {
            addLog("✗ Не выбран аудио файл для замены", LogLevel.ERROR)
            _state.update { it.copy(error = "Выберите аудио файл для замены") }
            return
        }

        scope.launch {
            _state.update {
                it.copy(
                    isProcessing = true,
                    error = null,
                    successMessage = null,
                    conversionProgress = null
                )
            }

            addLog("=== НАЧАЛО КОНВЕРТАЦИИ ===", LogLevel.INFO)
            addLog("Режим: ${currentState.convertType.title}", LogLevel.INFO)

            if (currentState.replaceAudioSelected) {
                addLog("Замена аудио: ${currentState.audioFile}", LogLevel.INFO)
            }

            // Формируем параметры конвертации
            val params = ConversionParams(
                inputFile = inputFile,
                outputFile = outputFile,
                audioFile = currentState.audioFile,
                convertType = currentState.convertType,
                replaceAudio = currentState.replaceAudioSelected,
                videoCodec = when (currentState.convertType) {
                    ConvertType.STREAM_COPY, ConvertType.AUDIO_EXTRACT -> VideoCodec.COPY
                    else -> VideoCodec.LIBX264
                },
                audioCodec = when (currentState.convertType) {
                    ConvertType.STREAM_COPY if !currentState.replaceAudioSelected -> AudioCodec.COPY
                    ConvertType.AUDIO_EXTRACT -> AudioCodec.WAV
                    else -> AudioCodec.AAC
                },
                preset = "medium",
                trimStartMs = currentState.trimParams.trimStartMs,
                trimEndMs = currentState.trimParams.trimEndMs,
                trimStrategy = currentState.trimParams.trimStrategy,
                crf = 23
            )

            val result = ffmpegExecutor.convertWithProgress(
                params = params,
                onProgress = { progress ->
                    _state.update { it.copy(conversionProgress = progress) }
                },
                onLog = { logMessage ->
                    if (logMessage.contains("[stderr]")) {
                        addLog(logMessage, LogLevel.DEBUG)
                    }
                }
            )

            result.onSuccess { filePath ->
                _state.update {
                    it.copy(
                        isProcessing = false,
                        successMessage = "Конвертация завершена $filePath"
                    )
                }
                addLog("=== КОНВЕРТАЦИЯ ЗАВЕРШЕНА ===", LogLevel.SUCCESS)
                addLog(filePath, LogLevel.SUCCESS)
                onOpenFolder(filePath)
            }.onFailure { error ->
                error.printStackTrace()
                _state.update {
                    it.copy(
                        isProcessing = false,
                        error = error.message
                    )
                }
                addLog("=== ОШИБКА КОНВЕРТАЦИИ ===", LogLevel.ERROR)
                addLog("✗ ${error.message}", LogLevel.ERROR)
            }
        }
    }

    override fun onCancelConversion() {
        ffmpegExecutor.cancel()
        _state.update {
            it.copy(
                isProcessing = false,
                conversionProgress = null
            )
        }
        addLog("Конвертация отменена пользователем", LogLevel.WARNING)
    }

    override fun onClearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    override fun onOpenOutputFolder() {
        onOpenFolder(_state.value.outputFile.orEmpty())
    }

    private fun enrichMediaInfo(mediaInfo: MediaInfo): MediaInfo {
        var info = mediaInfo
        mediaInfo.streams.firstOrNull { it.codecType == "video" }?.let { video ->
            val nbFrames = video.nbFrames?.toLongOrNull()
            val rFps = video.frameRate?.toFrameRate()
            val avgFps = video.avgFrameRate?.toFrameRate()
            val durationSec = (video.duration ?: mediaInfo.format.duration).toDurationSeconds()

            // Точное количество кадров
            val totalFrames: Long? = nbFrames ?: avgFps?.let { fps ->
                durationSec?.let { (fps * it).toLong().coerceAtLeast(0) }
            }

            // VFR или CFR?
            val isVfr = rFps != null && avgFps != null && abs(rFps - avgFps) > 0.1
            val fpsLabel = buildString {
                append(avgFps?.formatFps() ?: rFps?.formatFps() ?: "—")
                if (isVfr) append(" (VFR)") else append(" (CFR)")
            }

            val fileSize = mediaInfo.format.size.toLongOrNull()
            val readableSize = fileSize.toReadableSize()
            val readableDuration = durationSec.toReadableDuration()

            val formatInfo = buildString {
                append(mediaInfo.format.formatName.uppercase())
                if (video.width != null && video.height != null) {
                    append(" • ${video.width}×${video.height}")
                }
                append(" • $fpsLabel")
                if (totalFrames != null) {
                    append(" • кадров: $totalFrames")
                }
                append(" • $readableDuration")
                append(" • $readableSize")
                if (video.bitRate?.isNotBlank() == true) {
                    val br = video.bitRate.toLongOrNull()?.let { "${it / 1000} kbps" } ?: "—"
                    append(" • $br")
                }
            }

            info = info.copy(
                format = info.format.copy(formatInfo = formatInfo)
            )
        }
        return info
    }

    private fun addLog(message: String, level: LogLevel) {
        println(message)
        _state.update { state ->
            val newLog = LogEntry(message = message, level = level)
            state.copy(logs = (state.logs + newLog).takeLast(200))
        }
    }

    override fun onTrimEndChange(trimEnd: Long?) {
        _state.update {
            it.copy(
                trimParams = it.trimParams.copy(
                    trimEndMs = trimEnd
                )
            )
        }
        addLog("Изменили конец обрезки на $trimEnd ms", LogLevel.INFO)
    }

    override fun onTrimStartChange(startMs: Long?) {
        _state.update {
            it.copy(
                trimParams = it.trimParams.copy(
                    trimStartMs = startMs
                )
            )
        }
        addLog("Изменили начало обрезки на $startMs", LogLevel.INFO)
    }

    override fun onTrimStrategyChange(trimStrategy: TrimStrategy) {
        _state.update {
            it.copy(
                trimParams = it.trimParams.copy(
                    trimStrategy = trimStrategy
                )
            )
        }
        addLog("Изменили стратегию обрезки на $trimStrategy", LogLevel.INFO)
    }
}

