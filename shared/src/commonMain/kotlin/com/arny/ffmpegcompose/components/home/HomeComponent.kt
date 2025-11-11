package com.arny.ffmpegcompose.components.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.arny.ffmpegcompose.components.utils.formatFps
import com.arny.ffmpegcompose.components.utils.toDurationLongMs
import com.arny.ffmpegcompose.components.utils.toDurationSeconds
import com.arny.ffmpegcompose.components.utils.toFrameRate
import com.arny.ffmpegcompose.components.utils.toReadableDuration
import com.arny.ffmpegcompose.components.utils.toReadableSize
import com.arny.ffmpegcompose.data.FFmpegExecutor
import com.arny.ffmpegcompose.data.models.AudioCodec
import com.arny.ffmpegcompose.data.models.ConversionParams
import com.arny.ffmpegcompose.data.models.ConversionProgress
import com.arny.ffmpegcompose.data.models.MediaInfo
import com.arny.ffmpegcompose.data.models.VideoCodec
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.math.abs

interface HomeComponent : HomeCallbacks {
    val state: StateFlow<HomeUiState>
}

interface HomeCallbacks {
    fun onSelectInputFile()
    fun onSelectOutputFile()
    fun onSelectAudioFile()
    fun onGetMediaInfo()
    fun onStartConversion()
    fun onCancelConversion()
    fun onClearLogs()
    fun onAddAudioToggled(checked: Boolean)
    fun onChangeConvertType(type: ConvertType)
}

data class HomeUiState(
    val convertType: ConvertType = ConvertType.STREAM_COPY,
    val inputFile: String? = null,
    val outputFile: String? = null,
    val audioFile: String? = null,
    val replaceAudioSelected: Boolean = false,
    val streamCopySelected: Boolean = false,
    val mediaInfo: MediaInfo? = null,
    val conversionProgress: ConversionProgress? = null,
    val isProcessing: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val error: String? = null,
    val successMessage: String? = null,
    val totalDurationMs: Long = 0L
)

enum class ConvertType(
    val title: String
) {
    STREAM_COPY("Прямопотоковое копирование"),
    CONVERT("Конвертация"),
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

    override fun onSelectOutputFile() {
        val fileDialog = FileDialog(null as Frame?, "Сохранить как", FileDialog.SAVE)
        fileDialog.file = "output.mp4"
        fileDialog.isVisible = true

        val directory = fileDialog.directory
        val file = fileDialog.file

        if (directory != null && file != null) {
            val paths = Paths.get(directory, file)
            var outputFile = paths.absolutePathString()
            if (paths.extension.isEmpty()) {
                outputFile += ".mp4"
            }
            _state.update { it.copy(outputFile = outputFile) }
            addLog("Выбран выходной файл: $outputFile", LogLevel.INFO)
        }
    }

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
                videoCodec = if (currentState.convertType == ConvertType.STREAM_COPY) {
                    VideoCodec.COPY
                } else {
                    VideoCodec.LIBX264
                },
                audioCodec = if (currentState.convertType == ConvertType.STREAM_COPY &&
                    !currentState.replaceAudioSelected
                ) {
                    AudioCodec.COPY
                } else {
                    AudioCodec.AAC
                },
                preset = "medium",
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

            result.onSuccess { message ->
                _state.update {
                    it.copy(
                        isProcessing = false,
                        successMessage = message
                    )
                }
                addLog("=== КОНВЕРТАЦИЯ ЗАВЕРШЕНА ===", LogLevel.SUCCESS)
                addLog(message, LogLevel.SUCCESS)
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
        _state.update { state ->
            val newLog = LogEntry(message = message, level = level)
            state.copy(logs = (state.logs + newLog).takeLast(200))
        }
    }
}

