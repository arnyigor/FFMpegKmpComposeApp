package com.arny.ffmpegcompose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arny.ffmpegcompose.components.home.DefaultHomeComponent
import com.arny.ffmpegcompose.components.root.DefaultRootComponent
import com.arny.ffmpegcompose.data.FFmpegExecutor
import com.arny.ffmpegcompose.data.config.ConfigManager
import com.arny.ffmpegcompose.data.models.FFmpegManager
import com.arny.ffmpegcompose.di.commonModules
import com.arny.ffmpegcompose.di.desktopModules
import com.arny.ffmpegcompose.ui.RootContent
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.inject

fun main() {
    println("Starting Desktop App...")  // ✅ Debug вывод

    try {
        // Инициализация Koin
        startKoin {
            modules(
                commonModules + desktopModules
            )
        }
        println("Koin initialized")  // ✅ Debug

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

            val root = DefaultRootComponent(
                componentContext = DefaultComponentContext(lifecycle),
                configManager = configManager,
                ffmpegManager = ffmpegManager,
                homeComponentFactory = { context ->
                    DefaultHomeComponent(context, FFmpegExecutor(configManager, json))
                }
            )
            println("RootComponent created")

            Window(
                onCloseRequest = ::exitApplication,
                title = "FFmpeg Desktop Converter",
                state = windowState
            ) {
                println("Window created")
                MaterialTheme {
                    Surface {
                        RootContent(root)
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        System.err.println("Error: ${e.message}")
    }
}
