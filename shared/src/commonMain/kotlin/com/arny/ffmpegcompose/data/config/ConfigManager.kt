package com.arny.ffmpegcompose.data.config

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

class ConfigManager {

    private val configDir = getConfigDirectory()
    private val configFile = configDir.resolve("config.ini")
    private val properties = Properties()

    init {
        configDir.createDirectories()
        loadConfig()
    }

    /**
     * Сохранение пути к FFmpeg
     */
    fun saveFfmpegPath(path: Path) {
        properties.setProperty(KEY_FFMPEG_PATH, path.toString())
        saveConfig()
    }

    /**
     * Получение сохранённого пути к FFmpeg
     */
    fun getFfmpegPath(): Path? {
        val pathString = properties.getProperty(KEY_FFMPEG_PATH) ?: return null
        val path = Paths.get(pathString)
        return if (path.exists()) path else null
    }

    /**
     * Проверка наличия FFmpeg и ffprobe
     */
    fun isFfmpegConfigured(): Boolean {
        val ffmpegPath = getFfmpegPath() ?: return false
        val ffprobePath = ffmpegPath.parent.resolve("ffprobe.exe")
        return ffmpegPath.exists() && ffprobePath.exists()
    }

    /**
     * Очистка конфигурации
     */
    fun clearConfig() {
        properties.clear()
        saveConfig()
    }

    private fun loadConfig() {
        if (configFile.exists()) {
            configFile.inputStream().use { input ->
                properties.load(input)
            }
        }
    }

    private fun saveConfig() {
        configFile.outputStream().use { output ->
            properties.store(output, "FFmpeg Converter Configuration")
        }
    }

    private fun getConfigDirectory(): Path {
        val home = System.getProperty("user.home")
        val appName = "FFmpegConverter"

        return when {
            System.getProperty("os.name").lowercase().contains("win") -> {
                Paths.get(home, "AppData", "Local", appName)
            }

            System.getProperty("os.name").lowercase().contains("mac") -> {
                Paths.get(home, "Library", "Application Support", appName)
            }

            else -> {
                Paths.get(home, ".config", appName)
            }
        }
    }

    private companion object {
        const val KEY_FFMPEG_PATH = "ffmpeg.path"
    }
}
