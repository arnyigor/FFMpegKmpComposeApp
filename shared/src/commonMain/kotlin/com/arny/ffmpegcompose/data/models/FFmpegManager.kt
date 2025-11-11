package com.arny.ffmpegcompose.data.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

class FFmpegManager {

    private val downloadDir = getDownloadDirectory()

    init {
        downloadDir.createDirectories()
    }

    /**
     * Проверка пользовательского пути
     */
    suspend fun verifyPath(path: Path): Result<FfmpegVerification> = withContext(Dispatchers.IO) {
        try {
            if (!path.exists()) {
                return@withContext Result.failure(Exception("Файл не найден: $path"))
            }

            if (!path.fileName.toString().equals("ffmpeg.exe", ignoreCase = true)) {
                return@withContext Result.failure(Exception("Выбранный файл не является ffmpeg.exe"))
            }

            val ffprobePath = path.parent.resolve("ffprobe.exe")
            val version = getVersion(path)

            val verification = FfmpegVerification(
                ffmpegPath = path,
                ffprobeExists = ffprobePath.exists(),
                version = version
            )

            if (version == null) {
                return@withContext Result.failure(Exception("FFmpeg не отвечает корректно"))
            }

            Result.success(verification)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Загрузка FFmpeg
     */
    suspend fun downloadFFmpeg(
        onProgress: (DownloadProgress) -> Unit
    ): Result<Path> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"

            onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING, percent = 0))

            val zipFile = downloadDir.resolve("ffmpeg-temp.zip")
            downloadFile(url, zipFile) { percent ->
                onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING, percent = percent))
            }

            onProgress(DownloadProgress(phase = DownloadPhase.EXTRACTING, percent = 0))

            val extractedPath = extractFFmpeg(zipFile, downloadDir) { percent ->
                onProgress(DownloadProgress(phase = DownloadPhase.EXTRACTING, percent = percent))
            }

            zipFile.deleteIfExists()

            onProgress(DownloadProgress(phase = DownloadPhase.VERIFYING, percent = 100))

            val version = getVersion(extractedPath)
            if (version == null) {
                return@withContext Result.failure(Exception("Загруженный FFmpeg не работает"))
            }

            Result.success(extractedPath)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadFile(url: String, destination: Path, onProgress: (Int) -> Unit) {
        val connection = URL(url).openConnection()
        val fileSize = connection.contentLengthLong

        connection.getInputStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var totalBytesRead = 0L
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (fileSize > 0) {
                        val percent = ((totalBytesRead * 100) / fileSize).toInt()
                        onProgress(percent)
                    }
                }
            }
        }
    }

    private fun extractFFmpeg(zipFile: Path, destination: Path, onProgress: (Int) -> Unit): Path {
        val targetDir = destination.resolve("ffmpeg")
        targetDir.createDirectories()

        var ffmpegPath: Path? = null
        var processedEntries = 0

        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                val fileName = entry.name

                // Извлекаем ffmpeg.exe и ffprobe.exe
                if (fileName.endsWith("ffmpeg.exe") || fileName.endsWith("ffprobe.exe")) {
                    val targetFile = targetDir.resolve(fileName.substringAfterLast("/"))

                    targetFile.outputStream().use { output ->
                        zip.copyTo(output)
                    }

                    if (fileName.endsWith("ffmpeg.exe")) {
                        ffmpegPath = targetFile
                    }

                    onProgress(if (fileName.endsWith("ffprobe.exe")) 100 else 50)
                }

                processedEntries++
                entry = zip.nextEntry
            }
        }

        return ffmpegPath ?: throw Exception("ffmpeg.exe не найден в архиве")
    }

    private fun getVersion(ffmpegPath: Path): String? {
        return try {
            val process = ProcessBuilder(ffmpegPath.toString(), "-version")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

            if (completed && process.exitValue() == 0 && output.contains("ffmpeg version")) {
                output.lines().first().removePrefix("ffmpeg version ").take(50)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getDownloadDirectory(): Path {
        val home = System.getProperty("user.home")
        val appName = "FFmpegConverter"

        return when {
            System.getProperty("os.name").lowercase().contains("win") -> {
                Paths.get(home, "AppData", "Local", appName, "ffmpeg")
            }

            else -> {
                Paths.get(home, ".local", "share", appName, "ffmpeg")
            }
        }
    }
}

