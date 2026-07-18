package com.arny.ffmpegcompose.components.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.arny.ffmpegcompose.components.utils.*
import com.arny.ffmpegcompose.data.FFmpegExecutor
import com.arny.ffmpegcompose.data.SmartVoiceExecutor
import com.arny.ffmpegcompose.data.WhisperExecutor
import com.arny.ffmpegcompose.data.models.*
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import kotlin.math.roundToLong

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
    override fun onSmartVoiceToggled(checked: Boolean) {}
    override fun onTrimToggled(checked: Boolean) {}
    override fun onChangeConvertType(type: ConvertType) {}
    override fun onTrimEndChange(trimEnd: Long?) {}
    override fun onTrimStartChange(startMs: Long?) {}
    override fun onTrimStrategyChange(trimStrategy: TrimStrategy) {}
    override fun onWhisperModelChange(model: WhisperModelOption) {}
    override fun onWhisperLanguageChange(language: String) {}
    override fun onWordTimestampsToggled(checked: Boolean) {}
    override fun onOriginalVoiceVolumeChange(percent: Int) {}
    override fun onReplacementVoiceVolumeChange(percent: Int) {}
    override fun onSmartVoiceDeviceChange(device: SmartVoiceDevice) {}
    override fun onSmartVoiceModelChange(model: SmartVoiceSeparationModel) {}
    override fun onAudioStreamChange(index: Int) {}
    override fun onPreviewSelection() {}
    override fun onStopPreview() {}
    override fun onPreviewVolumeChange(volume: Int) {}
    override fun onPreviewVolumeCommitted() {}
    override fun onPreviewPositionChange(positionMs: Long) {}
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
    fun onSmartVoiceToggled(checked: Boolean)
    fun onTrimToggled(checked: Boolean)
    fun onChangeConvertType(type: ConvertType)
    fun onTrimStartChange(startMs: Long?)
    fun onTrimEndChange(trimEnd: Long?)
    fun onTrimStrategyChange(trimStrategy: TrimStrategy)
    fun onWhisperModelChange(model: WhisperModelOption)
    fun onWhisperLanguageChange(language: String)
    fun onWordTimestampsToggled(checked: Boolean)
    fun onOriginalVoiceVolumeChange(percent: Int)
    fun onReplacementVoiceVolumeChange(percent: Int)
    fun onSmartVoiceDeviceChange(device: SmartVoiceDevice)
    fun onSmartVoiceModelChange(model: SmartVoiceSeparationModel)
    fun onAudioStreamChange(index: Int)
    fun onPreviewSelection()
    fun onStopPreview()
    fun onPreviewVolumeChange(volume: Int)
    fun onPreviewVolumeCommitted()
    fun onPreviewPositionChange(positionMs: Long)
}

data class HomeUiState(
    val convertType: ConvertType = ConvertType.STREAM_COPY,
    val inputFile: String? = null,
    val outputFile: String? = null,
    val audioFile: String? = null,
    val replaceAudioSelected: Boolean = false,
    val smartVoiceReplacementSelected: Boolean = false,
    val trimSelected: Boolean = false,
    val mediaInfo: MediaInfo? = null,
    val conversionProgress: ConversionProgress? = null,
    val isProcessing: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val error: String? = null,
    val successMessage: String? = null,
    val totalDurationUs: Long = 0L,
    val trimParams: TrimParams = TrimParams(),
    val processingProgress: ProcessingProgress = ProcessingProgress(),
    val whisperSettings: WhisperSettings = WhisperSettings(),
    val smartVoiceSettings: SmartVoiceSettings = SmartVoiceSettings(),
    val resultFiles: List<String> = emptyList(),
    val selectedAudioStreamIndex: Int? = null,
    val preview: PreviewUiState = PreviewUiState(),
)

data class PreviewUiState(
    val isPreparing: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val volume: Int = 70,
    val currentFrame: ByteArray? = null,
    val isPreviewFrameLoading: Boolean = false,
)

data class TrimParams(
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val trimStrategy: TrimStrategy = TrimStrategy.AUTO,
)

enum class ConvertType(
    val title: String
) {
    STREAM_COPY("Прямопотоковое копирование"),
    CONVERT("Конвертация"),
    AUDIO_EXTRACT("Извлечь аудио"),
    TRANSCRIBE("Распознать речь"),
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
    private val ffmpegExecutor: FFmpegExecutor,
    private val whisperExecutor: WhisperExecutor,
    private val smartVoiceExecutor: SmartVoiceExecutor,
) : HomeComponent, ComponentContext by componentContext {

    private val scope = coroutineScope(SupervisorJob())
    private var previewJob: Job? = null
    private var currentFrameJob: Job? = null

    private val _state = MutableStateFlow(HomeUiState())
    override val state: StateFlow<HomeUiState> = _state.asStateFlow()

    override fun onChangeConvertType(type: ConvertType) {
        _state.update { state ->
            val supportsAudioReplacement = type != ConvertType.AUDIO_EXTRACT && type != ConvertType.TRANSCRIBE
            val replaceAudio = state.replaceAudioSelected && supportsAudioReplacement
            val smartVoice = state.smartVoiceReplacementSelected && supportsAudioReplacement
            state.copy(
                convertType = type,
                outputFile = state.inputFile?.let { suggestedOutputPath(it, type, replaceAudio, smartVoice) },
                replaceAudioSelected = replaceAudio,
                smartVoiceReplacementSelected = smartVoice,
                error = null,
                successMessage = null,
            )
        }
        addLog("Изменен тип конвертации на ${type.title}", LogLevel.INFO)
    }

    override fun onAddAudioToggled(checked: Boolean) {
        _state.update { state ->
            state.copy(
                replaceAudioSelected = checked,
                smartVoiceReplacementSelected = if (checked) false else state.smartVoiceReplacementSelected,
                outputFile = state.inputFile?.let { suggestedOutputPath(it, state.convertType, checked, false) } ?: state.outputFile,
            )
        }
        addLog(
            if (checked) "Включена замена аудио дорожки"
            else "Выключена замена аудио дорожки",
            LogLevel.INFO
        )
    }

    override fun onSmartVoiceToggled(checked: Boolean) {
        _state.update { state ->
            state.copy(
                smartVoiceReplacementSelected = checked,
                replaceAudioSelected = if (checked) false else state.replaceAudioSelected,
                outputFile = state.inputFile?.let { suggestedOutputPath(it, state.convertType, false, checked) } ?: state.outputFile,
            )
        }
        addLog(
            if (checked) "Включена умная замена голоса"
            else "Выключена умная замена голоса",
            LogLevel.INFO
        )
    }

    override fun onTrimToggled(checked: Boolean) {
        stopPreview(resetPosition = true)
        _state.update {
            it.copy(
                trimSelected = checked,
                trimParams = if (checked) it.trimParams else TrimParams(),
            )
        }
        addLog(
            if (checked) "Обрезка включена" else "Обрезка отключена",
            LogLevel.INFO
        )
    }

    override fun onSelectInputFile() {
        val fileDialog = FileDialog(null as Frame?, "Выберите видео файл", FileDialog.LOAD)
        fileDialog.file = "*.mp4;*.avi;*.mkv;*.mov;*.webm;*.mp3;*.wav;*.flac;*.m4a;*.aac;*.ogg;*.opus"
        fileDialog.isVisible = true

        val directory = fileDialog.directory
        val file = fileDialog.file

        if (directory != null && file != null) {
            stopPreview(resetPosition = true)
            val path = Paths.get(directory, file).absolutePathString()
            _state.update {
                it.copy(
                    inputFile = path,
                    outputFile = suggestedOutputPath(path, it.convertType, it.replaceAudioSelected, it.smartVoiceReplacementSelected),
                    mediaInfo = null,
                    error = null,
                    successMessage = null,
                    preview = it.preview.copy(
                        positionMs = 0L,
                        currentFrame = null,
                        isPreviewFrameLoading = true,
                    ),
                )
            }
            addLog("Выбран входной файл: $path", LogLevel.INFO)
            getMediaInfo(path)
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
        val extension = if (_state.value.smartVoiceReplacementSelected) {
            ".mp4"
        } else when (_state.value.convertType) {
            ConvertType.STREAM_COPY -> if (_state.value.replaceAudioSelected) {
                ".mp4"
            } else {
                _state.value.inputFile
                    ?.let { File(it).extension.takeIf(String::isNotBlank)?.let { ext -> ".$ext" } }
                    ?: ".mkv"
            }
            ConvertType.CONVERT -> ".mp4"
            ConvertType.AUDIO_EXTRACT -> ".wav"
            ConvertType.TRANSCRIBE -> ".srt"
        }

        fileDialog.file = "output$extension"
        fileDialog.isVisible = true

        val directory = fileDialog.directory
        val fileName = fileDialog.file

        if (directory != null && fileName != null) {
            val paths = Paths.get(directory, fileName)
            var outputFile = paths.absolutePathString()
            if (paths.extension.isEmpty()) {
                outputFile += extension
            }
            _state.update { it.copy(outputFile = outputFile) }
            addLog("Выбран выходной файл: $outputFile", LogLevel.INFO)
        }
    }

    private fun suggestedOutputPath(
        inputPath: String,
        type: ConvertType,
        replaceAudio: Boolean = false,
        smartVoice: Boolean = false,
    ): String {
        val input = File(inputPath)
        val base = input.nameWithoutExtension.ifBlank { "output" }
        val sourceExtension = input.extension.takeIf(String::isNotBlank)?.let { ".$it" } ?: ".mkv"
        val suffix = if (smartVoice) {
            "_smart_voice.mp4"
        } else when (type) {
            ConvertType.STREAM_COPY -> if (replaceAudio) "_audio_replaced.mp4" else "_copy$sourceExtension"
            ConvertType.CONVERT -> "_converted.mp4"
            ConvertType.AUDIO_EXTRACT -> "_audio.wav"
            ConvertType.TRANSCRIBE -> "_transcript.srt"
        }
        return File(input.parentFile ?: File("."), base + suffix).absolutePath
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
                val totalDurationUs = mediaInfo.format.duration.toDurationUs() ?: 0L
                _state.update {
                    it.copy(
                        mediaInfo = enrichMediaInfo(mediaInfo),
                        isProcessing = false,
                        totalDurationUs = totalDurationUs,
                        processingProgress = ProcessingProgress(),
                        selectedAudioStreamIndex = mediaInfo.streams.firstOrNull { stream -> stream.codecType == "audio" }?.index,
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

                if (mediaInfo.streams.any { it.codecType == "video" }) {
                    ffmpegExecutor.createPreviewFrame(inputFile, 0L)
                        .onSuccess { frame ->
                            _state.update { current ->
                                current.copy(preview = current.preview.copy(
                                    isPreviewFrameLoading = false,
                                    currentFrame = frame,
                                ))
                            }
                        }
                        .onFailure { error ->
                            _state.update { current ->
                                current.copy(preview = current.preview.copy(isPreviewFrameLoading = false))
                            }
                            addLog("Стоп-кадр недоступен: ${error.message}", LogLevel.WARNING)
                        }
                } else {
                    _state.update { current ->
                        current.copy(preview = current.preview.copy(isPreviewFrameLoading = false))
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
        stopPreview(resetPosition = false)
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
        if ((currentState.replaceAudioSelected || currentState.smartVoiceReplacementSelected) && currentState.audioFile == null) {
            addLog("✗ Не выбран аудио файл для замены", LogLevel.ERROR)
            _state.update { it.copy(error = "Выберите аудио файл для замены") }
            return
        }

        if (currentState.trimSelected) {
            val start = currentState.trimParams.trimStartMs ?: 0L
            val end = currentState.trimParams.trimEndMs
            val durationMs = currentState.totalDurationUs / 1_000L
            if (start < 0 || (end != null && end <= start) || start >= durationMs || (end != null && end > durationMs)) {
                _state.update { it.copy(error = "Проверьте границы обрезки: начало должно быть меньше конца и находиться внутри файла") }
                return
            }
        }

        scope.launch {
            _state.update {
                it.copy(
                    isProcessing = true,
                    error = null,
                    successMessage = null,
                    conversionProgress = null,
                    resultFiles = emptyList(),
                    processingProgress = ProcessingProgress(
                        phase = when {
                            currentState.convertType == ConvertType.TRANSCRIBE -> ProcessingPhase.EXTRACTING_AUDIO
                            currentState.smartVoiceReplacementSelected -> ProcessingPhase.EXTRACTING_AUDIO
                            else -> ProcessingPhase.CONVERTING
                        },
                    ),
                )
            }

            addLog("=== НАЧАЛО КОНВЕРТАЦИИ ===", LogLevel.INFO)
            addLog("Режим: ${currentState.convertType.title}", LogLevel.INFO)

            when {
                currentState.smartVoiceReplacementSelected -> addLog("Умная замена голоса: ${currentState.audioFile}", LogLevel.INFO)
                currentState.replaceAudioSelected -> addLog("Замена аудио: ${currentState.audioFile}", LogLevel.INFO)
            }

            if (currentState.convertType == ConvertType.TRANSCRIBE) {
                runTranscription(currentState, inputFile, outputFile)
                return@launch
            }
            if (currentState.smartVoiceReplacementSelected) {
                runSmartVoiceReplacement(currentState, inputFile, outputFile)
                return@launch
            }

            val startedAt = System.currentTimeMillis()
            val params = ConversionParams(
                inputFile = inputFile,
                outputFile = outputFile,
                audioFile = currentState.audioFile,
                convertType = currentState.convertType,
                replaceAudio = currentState.replaceAudioSelected,
                videoCodec = when (currentState.convertType) {
                    ConvertType.STREAM_COPY, ConvertType.AUDIO_EXTRACT, ConvertType.TRANSCRIBE -> VideoCodec.COPY
                    ConvertType.CONVERT -> VideoCodec.LIBX265
                },
                audioCodec = when (currentState.convertType) {
                    ConvertType.STREAM_COPY if !currentState.replaceAudioSelected -> AudioCodec.COPY
                    ConvertType.STREAM_COPY -> replacementAudioCodecForOutput(outputFile)
                    ConvertType.AUDIO_EXTRACT -> AudioCodec.WAV
                    ConvertType.TRANSCRIBE -> AudioCodec.WAV
                    ConvertType.CONVERT -> AudioCodec.AAC
                },
                preset = "medium",
                trimStartMs = currentState.trimParams.trimStartMs.takeIf { currentState.trimSelected },
                trimEndMs = currentState.trimParams.trimEndMs.takeIf { currentState.trimSelected },
                trimStrategy = currentState.trimParams.trimStrategy,
                totalDurationUs = targetDurationUs(currentState),
                audioStreamIndex = currentState.selectedAudioStreamIndex,
                crf = 23
            )

            val result = ffmpegExecutor.convertWithProgress(
                params = params,
                onProgress = { progress ->
                    val targetUs = params.totalDurationUs
                    val fraction = if (targetUs > 0L) {
                        (progress.outTimeUs.toDouble() / targetUs).toFloat().coerceIn(0f, 1f)
                    } else null
                    val remainingMs = if (targetUs > 0L && progress.speed > 0f) {
                        (((targetUs - progress.outTimeUs).coerceAtLeast(0L) / progress.speed) / 1_000.0).toLong()
                    } else null
                    _state.update {
                        it.copy(
                            conversionProgress = progress,
                            processingProgress = ProcessingProgress(
                                phase = ProcessingPhase.CONVERTING,
                                phaseProgress = fraction,
                                overallProgress = fraction,
                                elapsedMs = System.currentTimeMillis() - startedAt,
                                estimatedRemainingMs = remainingMs,
                                detail = "${progress.formatTime()} • ${progress.speed}x",
                            ),
                        )
                    }
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
                        successMessage = "Готово: $filePath",
                        resultFiles = listOf(filePath),
                        processingProgress = it.processingProgress.copy(
                            phase = ProcessingPhase.COMPLETED,
                            phaseProgress = 1f,
                            overallProgress = 1f,
                            estimatedRemainingMs = 0L,
                        ),
                    )
                }
                addLog("=== КОНВЕРТАЦИЯ ЗАВЕРШЕНА ===", LogLevel.SUCCESS)
                addLog(filePath, LogLevel.SUCCESS)
                onOpenFolder(filePath)
            }.onFailure { error ->
                error.printStackTrace()
                if (_state.value.processingProgress.phase == ProcessingPhase.CANCELLED) return@onFailure
                _state.update {
                    it.copy(
                        isProcessing = false,
                        error = error.message,
                        processingProgress = it.processingProgress.copy(phase = ProcessingPhase.FAILED),
                    )
                }
                addLog("=== ОШИБКА КОНВЕРТАЦИИ ===", LogLevel.ERROR)
                addLog("✗ ${error.message}", LogLevel.ERROR)
            }
        }
    }

    private suspend fun runSmartVoiceReplacement(state: HomeUiState, inputFile: String, outputFile: String) {
        val startedAt = System.currentTimeMillis()
        val output = File(outputFile)
        val workDir = File(output.parentFile ?: File("."), "${output.nameWithoutExtension}_smart_voice_work").apply { mkdirs() }
        val sourceAudio = File(workDir, "source.wav")
        val replacementAudio = requireNotNull(state.audioFile) { "Выберите аудио файл для замены" }
        try {
            addLog("Рабочая папка умной замены: ${workDir.absolutePath}", LogLevel.INFO)
            val extractionParams = ConversionParams(
                inputFile = inputFile,
                outputFile = sourceAudio.absolutePath,
                convertType = ConvertType.AUDIO_EXTRACT,
                videoCodec = VideoCodec.COPY,
                audioCodec = AudioCodec.WAV,
                audioStreamIndex = state.selectedAudioStreamIndex,
                trimStartMs = state.trimParams.trimStartMs.takeIf { state.trimSelected },
                trimEndMs = state.trimParams.trimEndMs.takeIf { state.trimSelected },
                trimStrategy = TrimStrategy.ACCURATE,
                totalDurationUs = targetDurationUs(state),
            )
            ffmpegExecutor.convertWithProgress(
                params = extractionParams,
                onProgress = { progress ->
                    val fraction = if (extractionParams.totalDurationUs > 0) {
                        (progress.outTimeUs.toDouble() / extractionParams.totalDurationUs).toFloat().coerceIn(0f, 1f)
                    } else null
                    _state.update {
                        it.copy(
                            conversionProgress = progress,
                            processingProgress = ProcessingProgress(
                                phase = ProcessingPhase.EXTRACTING_AUDIO,
                                phaseProgress = fraction,
                                overallProgress = fraction?.times(0.15f),
                                elapsedMs = System.currentTimeMillis() - startedAt,
                                detail = "Подготовка оригинальной аудиодорожки",
                            ),
                        )
                    }
                },
                onLog = { addLog(it, LogLevel.DEBUG) },
            ).getOrThrow()

            val stems = smartVoiceExecutor.separateVocals(
                audioFile = sourceAudio.absolutePath,
                workDir = workDir,
                device = state.smartVoiceSettings.device,
                model = state.smartVoiceSettings.model,
                onProgress = { phase, fraction, detail ->
                    if (!_state.value.isProcessing || _state.value.processingProgress.phase == ProcessingPhase.CANCELLED) return@separateVocals
                    _state.update {
                        it.copy(
                            processingProgress = ProcessingProgress(
                                phase = phase,
                                phaseProgress = fraction,
                                overallProgress = 0.15f + (fraction ?: 0f) * 0.60f,
                                elapsedMs = System.currentTimeMillis() - startedAt,
                                detail = detail,
                            ),
                        )
                    }
                },
                onLog = { addLog(it, LogLevel.DEBUG) },
            ).getOrThrow()

            val mixParamsTotalUs = targetDurationUs(state)
            ffmpegExecutor.mixSmartVoiceReplacement(
                inputFile = inputFile,
                outputFile = outputFile,
                backgroundAudioFile = stems.backgroundFile,
                originalVoiceFile = stems.vocalsFile,
                replacementVoiceFile = replacementAudio,
                originalVoiceVolumePercent = state.smartVoiceSettings.originalVoiceVolumePercent,
                replacementVoiceVolumePercent = state.smartVoiceSettings.replacementVoiceVolumePercent,
                videoCodec = when (state.convertType) {
                    ConvertType.CONVERT -> VideoCodec.LIBX265
                    else -> VideoCodec.COPY
                },
                preset = "medium",
                crf = 23,
                trimStartMs = state.trimParams.trimStartMs.takeIf { state.trimSelected },
                trimEndMs = state.trimParams.trimEndMs.takeIf { state.trimSelected },
                onProgress = { progress ->
                    val fraction = if (mixParamsTotalUs > 0) {
                        (progress.outTimeUs.toDouble() / mixParamsTotalUs).toFloat().coerceIn(0f, 1f)
                    } else null
                    _state.update {
                        it.copy(
                            conversionProgress = progress,
                            processingProgress = ProcessingProgress(
                                phase = ProcessingPhase.MIXING_AUDIO,
                                phaseProgress = fraction,
                                overallProgress = 0.75f + (fraction ?: 0f) * 0.25f,
                                elapsedMs = System.currentTimeMillis() - startedAt,
                                detail = "Сведение фона, приглушённого оригинала и нового голоса",
                            ),
                        )
                    }
                },
                onLog = { addLog(it, LogLevel.DEBUG) },
            ).getOrThrow()

            _state.update {
                it.copy(
                    isProcessing = false,
                    successMessage = "Умная замена голоса завершена: $outputFile",
                    resultFiles = listOf(outputFile, stems.backgroundFile, stems.vocalsFile),
                    processingProgress = ProcessingProgress(
                        phase = ProcessingPhase.COMPLETED,
                        phaseProgress = 1f,
                        overallProgress = 1f,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        estimatedRemainingMs = 0L,
                        detail = "Видео + фон + новый голос",
                    ),
                )
            }
            addLog("=== УМНАЯ ЗАМЕНА ГОЛОСА ЗАВЕРШЕНА ===", LogLevel.SUCCESS)
            addLog(outputFile, LogLevel.SUCCESS)
            onOpenFolder(outputFile)
        } catch (error: Exception) {
            if (!_state.value.isProcessing || _state.value.processingProgress.phase == ProcessingPhase.CANCELLED) return
            _state.update {
                it.copy(
                    isProcessing = false,
                    error = error.message ?: "Ошибка умной замены голоса",
                    processingProgress = it.processingProgress.copy(phase = ProcessingPhase.FAILED),
                )
            }
            addLog("Ошибка умной замены голоса: ${error.message}", LogLevel.ERROR)
        }
    }

    private suspend fun runTranscription(state: HomeUiState, inputFile: String, outputFile: String) {
        val startedAt = System.currentTimeMillis()
        val tempAudio = File.createTempFile("ffmpeg-whisper-", ".wav")
        val outputBase = File(outputFile).let { file ->
            File(file.parentFile ?: File("."), file.nameWithoutExtension).absolutePath
        }
        try {
            val extractionParams = ConversionParams(
                inputFile = inputFile,
                outputFile = tempAudio.absolutePath,
                convertType = ConvertType.AUDIO_EXTRACT,
                videoCodec = VideoCodec.COPY,
                audioCodec = AudioCodec.WAV,
                audioChannels = 1,
                audioSampleRate = 16_000,
                audioStreamIndex = state.selectedAudioStreamIndex,
                trimStartMs = state.trimParams.trimStartMs.takeIf { state.trimSelected },
                trimEndMs = state.trimParams.trimEndMs.takeIf { state.trimSelected },
                trimStrategy = TrimStrategy.ACCURATE,
                totalDurationUs = targetDurationUs(state),
            )
            val extraction = ffmpegExecutor.convertWithProgress(
                params = extractionParams,
                onProgress = { progress ->
                    val fraction = if (extractionParams.totalDurationUs > 0) {
                        (progress.outTimeUs.toDouble() / extractionParams.totalDurationUs).toFloat().coerceIn(0f, 1f)
                    } else null
                    _state.update {
                        it.copy(
                            conversionProgress = progress,
                            processingProgress = ProcessingProgress(
                                phase = ProcessingPhase.EXTRACTING_AUDIO,
                                phaseProgress = fraction,
                                overallProgress = fraction?.times(0.2f),
                                elapsedMs = System.currentTimeMillis() - startedAt,
                                detail = "Подготовка WAV 16 kHz mono",
                            ),
                        )
                    }
                },
                onLog = { addLog(it, LogLevel.DEBUG) },
            )
            extraction.getOrThrow()

            val whisperStartedAt = System.currentTimeMillis()
            val transcription = whisperExecutor.transcribe(
                audioFile = tempAudio.absolutePath,
                outputBase = outputBase,
                settings = state.whisperSettings,
                onProgress = { phase, fraction, detail ->
                    val phaseElapsedMs = System.currentTimeMillis() - whisperStartedAt
                    val remainingMs = fraction?.takeIf { it > 0f }?.let {
                        (phaseElapsedMs / it - phaseElapsedMs).toLong().coerceAtLeast(0L)
                    }
                    val overall = when (phase) {
                        ProcessingPhase.LOADING_MODEL -> 0.2f
                        ProcessingPhase.TRANSCRIBING -> 0.2f + (fraction ?: 0f) * 0.78f
                        else -> null
                    }
                    _state.update {
                        it.copy(
                            processingProgress = ProcessingProgress(
                                phase = phase,
                                phaseProgress = fraction,
                                overallProgress = overall,
                                elapsedMs = System.currentTimeMillis() - startedAt,
                                estimatedRemainingMs = remainingMs,
                                detail = detail,
                            ),
                        )
                    }
                },
                onLog = { addLog(it, LogLevel.DEBUG) },
            ).getOrThrow()

            _state.update {
                it.copy(
                    isProcessing = false,
                    successMessage = "Транскрибация завершена (${transcription.detectedLanguage ?: "язык не определён"})",
                    resultFiles = transcription.outputFiles,
                    processingProgress = ProcessingProgress(
                        phase = ProcessingPhase.COMPLETED,
                        phaseProgress = 1f,
                        overallProgress = 1f,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        estimatedRemainingMs = 0L,
                        detail = transcription.outputFiles.joinToString(),
                    ),
                )
            }
            transcription.outputFiles.forEach { addLog(it, LogLevel.SUCCESS) }
        } catch (error: Exception) {
            if (_state.value.processingProgress.phase == ProcessingPhase.CANCELLED) return
            _state.update {
                it.copy(
                    isProcessing = false,
                    error = error.message ?: "Ошибка Whisper",
                    processingProgress = it.processingProgress.copy(phase = ProcessingPhase.FAILED),
                )
            }
            addLog("Ошибка Whisper: ${error.message}", LogLevel.ERROR)
        } finally {
            tempAudio.delete()
        }
    }

    private fun targetDurationUs(state: HomeUiState): Long {
        if (!state.trimSelected) return state.totalDurationUs
        val startUs = (state.trimParams.trimStartMs ?: 0L) * 1_000L
        val endUs = (state.trimParams.trimEndMs?.times(1_000L) ?: state.totalDurationUs)
        return (endUs - startUs).coerceAtLeast(0L)
    }

    private fun replacementAudioCodecForOutput(outputFile: String): AudioCodec =
        when (File(outputFile).extension.lowercase(Locale.ROOT)) {
            "webm", "ogg", "opus" -> AudioCodec.OPUS
            else -> AudioCodec.AAC
        }

    override fun onCancelConversion() {
        ffmpegExecutor.cancel()
        whisperExecutor.cancel()
        smartVoiceExecutor.cancel()
        _state.update {
            it.copy(
                isProcessing = false,
                conversionProgress = null,
                processingProgress = it.processingProgress.copy(phase = ProcessingPhase.CANCELLED),
            )
        }
        addLog("Конвертация отменена пользователем", LogLevel.WARNING)
    }

    override fun onClearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    override fun onOpenOutputFolder() {
        onOpenFolder(_state.value.resultFiles.firstOrNull() ?: _state.value.outputFile.orEmpty())
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
        if (_state.value.preview.isPlaying) stopPreview(resetPosition = false)
        val current = _state.value
        val durationMs = (current.totalDurationUs / 1_000L).coerceAtLeast(0L)
        val minGapMs = minTrimGapMs(current)
        val minEnd = ((current.trimParams.trimStartMs ?: 0L) + minGapMs).coerceAtMost(durationMs)
        val normalizedEnd = trimEnd
            ?.let { snapToTrimStep(it, current, durationMs) }
            ?.coerceIn(minEnd, durationMs)
        _state.update {
            it.copy(
                trimParams = it.trimParams.copy(
                    trimEndMs = normalizedEnd
                ),
                preview = it.preview.copy(positionMs = normalizedEnd ?: it.preview.positionMs),
            )
        }
        normalizedEnd?.let(::requestCurrentFrame)
    }

    override fun onTrimStartChange(startMs: Long?) {
        if (_state.value.preview.isPlaying) stopPreview(resetPosition = false)
        val current = _state.value
        val durationMs = (current.totalDurationUs / 1_000L).coerceAtLeast(0L)
        val currentEnd = current.trimParams.trimEndMs ?: durationMs
        val maxStart = (currentEnd - minTrimGapMs(current)).coerceAtLeast(0L)
        val normalizedStart = startMs
            ?.let { snapToTrimStep(it, current, durationMs) }
            ?.coerceIn(0L, maxStart)
        _state.update {
            it.copy(
                trimParams = it.trimParams.copy(
                    trimStartMs = normalizedStart
                ),
                preview = if (it.preview.isPlaying) {
                    it.preview
                } else {
                    it.preview.copy(positionMs = normalizedStart ?: 0L)
                },
            )
        }
        normalizedStart?.let(::requestCurrentFrame)
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

    override fun onWhisperModelChange(model: WhisperModelOption) {
        _state.update { it.copy(whisperSettings = it.whisperSettings.copy(model = model)) }
    }

    override fun onWhisperLanguageChange(language: String) {
        _state.update { it.copy(whisperSettings = it.whisperSettings.copy(language = language.trim())) }
    }

    override fun onWordTimestampsToggled(checked: Boolean) {
        _state.update { it.copy(whisperSettings = it.whisperSettings.copy(wordTimestamps = checked)) }
    }

    override fun onOriginalVoiceVolumeChange(percent: Int) {
        _state.update {
            it.copy(
                smartVoiceSettings = it.smartVoiceSettings.copy(
                    originalVoiceVolumePercent = percent.coerceIn(0, 100),
                ),
            )
        }
    }

    override fun onReplacementVoiceVolumeChange(percent: Int) {
        _state.update {
            it.copy(
                smartVoiceSettings = it.smartVoiceSettings.copy(
                    replacementVoiceVolumePercent = percent.coerceIn(0, 200),
                ),
            )
        }
    }

    override fun onSmartVoiceDeviceChange(device: SmartVoiceDevice) {
        _state.update { it.copy(smartVoiceSettings = it.smartVoiceSettings.copy(device = device)) }
        addLog("Устройство умной замены голоса: ${device.title}", LogLevel.INFO)
    }

    override fun onSmartVoiceModelChange(model: SmartVoiceSeparationModel) {
        _state.update { it.copy(smartVoiceSettings = it.smartVoiceSettings.copy(model = model)) }
        addLog("Модель разделения голоса: ${model.title}", LogLevel.INFO)
    }

    override fun onAudioStreamChange(index: Int) {
        _state.update { it.copy(selectedAudioStreamIndex = index) }
    }

    override fun onPreviewSelection() {
        if (_state.value.preview.isPlaying || _state.value.preview.isPreparing) {
            stopPreview(resetPosition = false)
        } else {
            startPreview(_state.value.preview.positionMs)
        }
    }

    override fun onStopPreview() = stopPreview(resetPosition = true)

    override fun onPreviewVolumeChange(volume: Int) {
        _state.update { it.copy(preview = it.preview.copy(volume = volume.coerceIn(0, 100))) }
    }

    override fun onPreviewVolumeCommitted() {
        val preview = _state.value.preview
        if (preview.isPlaying) {
            stopPreview(resetPosition = false)
            startPreview(preview.positionMs)
        }
    }

    override fun onPreviewPositionChange(positionMs: Long) {
        if (_state.value.preview.isPlaying) stopPreview(resetPosition = false)
        val durationMs = (_state.value.totalDurationUs / 1_000L).coerceAtLeast(0L)
        val position = positionMs.coerceIn(0L, durationMs)
        _state.update { it.copy(preview = it.preview.copy(positionMs = position)) }
        requestCurrentFrame(position)
    }

    private fun requestCurrentFrame(positionMs: Long) {
        val input = _state.value.inputFile ?: return
        if (_state.value.mediaInfo?.streams?.none { it.codecType == "video" } != false) return
        currentFrameJob?.cancel()
        currentFrameJob = scope.launch {
            delay(160)
            ffmpegExecutor.createPreviewFrame(input, positionMs).onSuccess { frame ->
                _state.update { it.copy(preview = it.preview.copy(currentFrame = frame)) }
            }
        }
    }

    private fun startPreview(requestedStartMs: Long) {
        val current = _state.value
        val input = current.inputFile ?: return
        val rangeStart = current.trimParams.trimStartMs ?: 0L
        val rangeEnd = current.trimParams.trimEndMs ?: (current.totalDurationUs / 1_000L)
        val startMs = requestedStartMs.coerceIn(rangeStart, rangeEnd.coerceAtLeast(rangeStart))
        previewJob?.cancel()
        previewJob = scope.launch {
            _state.update { it.copy(preview = it.preview.copy(isPreparing = true, positionMs = startMs)) }
            val startedAt = System.currentTimeMillis()
            val ticker = launch {
                delay(250)
                _state.update { it.copy(preview = it.preview.copy(isPreparing = false, isPlaying = true)) }
                while (true) {
                    val position = (startMs + System.currentTimeMillis() - startedAt).coerceAtMost(rangeEnd)
                    _state.update { it.copy(preview = it.preview.copy(positionMs = position)) }
                    delay(200)
                }
            }
            val result = ffmpegExecutor.preview(
                inputFile = input,
                startMs = startMs,
                endMs = rangeEnd,
                volume = current.preview.volume,
                hasVideo = current.mediaInfo?.streams?.any { it.codecType == "video" } == true,
                onFrame = { frame ->
                    _state.update { state -> state.copy(preview = state.preview.copy(currentFrame = frame)) }
                },
            )
            ticker.cancel()
            result.onFailure {
                if (it !is CancellationException) addLog("Предпросмотр недоступен: ${it.message}", LogLevel.ERROR)
            }
            _state.update {
                it.copy(preview = it.preview.copy(
                    isPreparing = false,
                    isPlaying = false,
                    positionMs = rangeStart,
                ))
            }
        }
    }

    private fun stopPreview(resetPosition: Boolean) {
        ffmpegExecutor.stopPreview()
        previewJob?.cancel()
        previewJob = null
        _state.update {
            val start = it.trimParams.trimStartMs ?: 0L
            it.copy(preview = it.preview.copy(
                isPreparing = false,
                isPlaying = false,
                positionMs = if (resetPosition) start else it.preview.positionMs,
            ))
        }
    }

    private fun minTrimGapMs(state: HomeUiState): Long {
        val fps = state.mediaInfo
            ?.streams
            ?.firstOrNull { it.codecType == "video" }
            ?.let { video -> video.avgFrameRate.toFrameRate() ?: video.frameRate.toFrameRate() }

        return fps
            ?.takeIf { it > 0.0 && it < 1_000.0 }
            ?.let { (1_000.0 / it).roundToLong().coerceAtLeast(1L) }
            ?: MIN_TRIM_GAP_MS
    }

    private fun snapToTrimStep(positionMs: Long, state: HomeUiState, durationMs: Long): Long {
        val stepMs = minTrimGapMs(state)
        if (stepMs <= 1L) return positionMs.coerceIn(0L, durationMs)
        val snapped = (positionMs.toDouble() / stepMs).roundToLong() * stepMs
        return snapped.coerceIn(0L, durationMs)
    }

    private companion object {
        const val MIN_TRIM_GAP_MS = 1L
    }
}
