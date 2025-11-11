package com.arny.ffmpegcompose.components.setup

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.arny.ffmpegcompose.data.config.ConfigManager
import com.arny.ffmpegcompose.data.models.DownloadPhase
import com.arny.ffmpegcompose.data.models.DownloadProgress
import com.arny.ffmpegcompose.data.models.FFmpegManager
import com.arny.ffmpegcompose.data.models.FfmpegVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Paths

interface SetupComponent {
    val state: StateFlow<SetupUiState>

    fun onSelectPath()
    fun onDownload()
    fun onRetry()
}

data class SetupUiState(
    val phase: SetupPhase = SetupPhase.INITIAL,
    val verification: FfmpegVerification? = null,
    val downloadProgress: DownloadProgress? = null,
    val error: String? = null
)

enum class SetupPhase {
    INITIAL,        // Выбор: путь или загрузка
    CHECKING,       // Проверка пути
    DOWNLOADING,    // Загрузка
    SUCCESS,        // Готово
    ERROR           // Ошибка
}

class DefaultSetupComponent(
    componentContext: ComponentContext,
    private val ffmpegManager: FFmpegManager,
    private val configManager: ConfigManager,
    private val onComplete: () -> Unit
) : SetupComponent, ComponentContext by componentContext {

    private val scope = coroutineScope(SupervisorJob())

    private val _state = MutableStateFlow(SetupUiState())
    override val state: StateFlow<SetupUiState> = _state.asStateFlow()

    override fun onSelectPath() {
        val fileDialog = FileDialog(null as Frame?, "Выберите ffmpeg.exe", FileDialog.LOAD)
        fileDialog.file = "ffmpeg.exe"
        fileDialog.isVisible = true

        val directory = fileDialog.directory
        val file = fileDialog.file

        if (directory != null && file != null) {
            val path = Paths.get(directory, file)
            verifyPath(path)
        }
    }

    override fun onDownload() {
        scope.launch {
            _state.update {
                it.copy(
                    phase = SetupPhase.DOWNLOADING,
                    downloadProgress = DownloadProgress(
                        phase = DownloadPhase.DOWNLOADING,
                        percent = 0
                    ),
                    error = null
                )
            }

            val result = ffmpegManager.downloadFFmpeg { progress ->
                _state.update { it.copy(downloadProgress = progress) }
            }

            result.onSuccess { path ->
                configManager.saveFfmpegPath(path)

                val verification = FfmpegVerification(
                    ffmpegPath = path,
                    ffprobeExists = path.parent.resolve("ffprobe.exe").toFile().exists(),
                    version = "Downloaded"
                )

                _state.update {
                    it.copy(
                        phase = SetupPhase.SUCCESS,
                        verification = verification,
                        downloadProgress = null
                    )
                }

                // Переход на главный экран через 1 секунду
                delay(1000)
                withContext(Dispatchers.Main) {
                    onComplete()
                }

            }.onFailure { error ->
                _state.update {
                    it.copy(
                        phase = SetupPhase.ERROR,
                        downloadProgress = null,
                        error = error.message
                    )
                }
            }
        }
    }

    override fun onRetry() {
        _state.value = SetupUiState()
    }

    private fun verifyPath(path: java.nio.file.Path) {
        scope.launch {
            _state.update { it.copy(phase = SetupPhase.CHECKING, error = null) }

            val result = ffmpegManager.verifyPath(path)

            result.onSuccess { verification ->
                configManager.saveFfmpegPath(verification.ffmpegPath)

                _state.update {
                    it.copy(
                        phase = SetupPhase.SUCCESS,
                        verification = verification
                    )
                }

                // Переход на главный экран
                delay(500)
                withContext(Dispatchers.Main) {
                    onComplete()
                }

            }.onFailure { error ->
                _state.update {
                    it.copy(
                        phase = SetupPhase.ERROR,
                        error = error.message
                    )
                }
            }
        }
    }
}
