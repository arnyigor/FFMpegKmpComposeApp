package com.arny.ffmpegcompose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arny.ffmpegcompose.components.home.DefaultHomeComponent
import com.arny.ffmpegcompose.components.root.DefaultRootComponent
import com.arny.ffmpegcompose.data.FFmpegExecutor
import com.arny.ffmpegcompose.data.WhisperExecutor
import com.arny.ffmpegcompose.data.config.ConfigManager
import com.arny.ffmpegcompose.data.models.FFmpegManager
import com.arny.ffmpegcompose.di.commonModules
import com.arny.ffmpegcompose.di.desktopModules
import com.arny.ffmpegcompose.ui.RootContent
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.inject
import java.awt.Dimension

private val WorkshopColors = lightColorScheme(
    primary = Color(0xFF5B4AB5),
    secondary = Color(0xFF356A63),
    tertiary = Color(0xFF7A5260),
    surface = Color(0xFFFFFBFF),
    background = Color(0xFFFFFBFF),
)

fun main() {
    try {
        // Инициализация Koin
        startKoin {
            modules(
                commonModules + desktopModules
            )
        }
        application {
            val windowState = rememberWindowState(
                width = 1200.dp,
                height = 800.dp
            )

            val lifecycle = LifecycleRegistry()

            // Получаем зависимости из Koin
            val configManager: ConfigManager by inject(ConfigManager::class.java)
            val ffmpegManager: FFmpegManager by inject(FFmpegManager::class.java)
            val json: Json by inject(Json::class.java)
            val ffmpegExecutor: FFmpegExecutor by inject(FFmpegExecutor::class.java)
            val whisperExecutor: WhisperExecutor by inject(WhisperExecutor::class.java)

            val root = DefaultRootComponent(
                componentContext = DefaultComponentContext(lifecycle),
                configManager = configManager,
                ffmpegManager = ffmpegManager,
                homeComponentFactory = { context ->
                    DefaultHomeComponent(context, ffmpegExecutor, whisperExecutor)
                }
            )
            Window(
                onCloseRequest = {
                    ffmpegExecutor.stopPreview()
                    ffmpegExecutor.cancel()
                    whisperExecutor.cancel()
                    exitApplication()
                },
                title = "FFmpeg Media Workshop",
                state = windowState
            ) {
                window.minimumSize = Dimension(900, 680)
                MaterialTheme(colorScheme = WorkshopColors) {
                    Surface {
                        RootContent(root)
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
