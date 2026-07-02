package com.arny.ffmpegcompose.data

import com.arny.ffmpegcompose.data.models.ProcessingPhase
import com.arny.ffmpegcompose.data.models.TranscriptionResult
import com.arny.ffmpegcompose.data.models.WhisperSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class WhisperExecutor(private val json: Json) {
    @Volatile
    private var currentProcess: Process? = null

    suspend fun transcribe(
        audioFile: String,
        outputBase: String,
        settings: WhisperSettings,
        onProgress: (ProcessingPhase, Float?, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val projectDir = File(System.getProperty("user.dir"))
            val resourcesDir = System.getProperty("compose.application.resources.dir")
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
            val runtimeRoots = listOfNotNull(
                projectDir,
                resourcesDir?.resolve("python"),
                resourcesDir,
            )
            var python = runtimeRoots.asSequence().flatMap { root ->
                sequenceOf(
                    File(root, ".venv/Scripts/python.exe"),
                    File(root, ".venv/bin/python"),
                    File(root, ".venv/bin/python3"),
                )
            }.firstOrNull(File::isFile)
            if (python == null && resourcesDir != null) {
                python = prepareOnDemandRuntime(resourcesDir, onProgress, onLog)
            }
            val pythonExecutable = python ?: error("Python runtime Whisper не найден")
            val script = runtimeRoots.asSequence()
                .map { File(it, "whisper_transcribe.py") }
                .firstOrNull(File::isFile)
                ?: error("Скрипт whisper_transcribe.py не найден")

            val command = buildList {
                add(pythonExecutable.absolutePath)
                add(script.absolutePath)
                add(File(audioFile).absolutePath)
                add("--output-base")
                add(File(outputBase).absolutePath)
                add("--model")
                add(settings.model.modelId)
                add("--language")
                add(settings.language.ifBlank { "auto" })
                add("--device")
                add(settings.device)
                add("--formats")
                add("txt,srt,vtt,json")
                if (settings.wordTimestamps) add("--word-timestamps")
            }

            val process = ProcessBuilder(command)
                .directory(script.parentFile)
                .redirectErrorStream(false)
                .apply {
                    environment()["PYTHONIOENCODING"] = "utf-8"
                    environment()["PYTHONUTF8"] = "1"
                }
                .start()
            currentProcess = process

            var result: TranscriptionResult? = null
            coroutineScope {
                val stdout = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            if (line.isBlank()) return@forEach
                            val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                            if (event == null) {
                                onLog(line)
                                return@forEach
                            }
                            when (event["event"]?.jsonPrimitive?.contentOrNull) {
                                "phase" -> {
                                    val phase = when (event["phase"]?.jsonPrimitive?.contentOrNull) {
                                        "loading_model" -> ProcessingPhase.LOADING_MODEL
                                        "transcribing" -> ProcessingPhase.TRANSCRIBING
                                        else -> ProcessingPhase.TRANSCRIBING
                                    }
                                    onProgress(
                                        phase,
                                        null,
                                        event["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    )
                                }
                                "progress" -> {
                                    val processed = event["processed_seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                                    val total = event["total_seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                                    val fraction = if (total > 0) (processed / total).toFloat().coerceIn(0f, 1f) else null
                                    onProgress(
                                        ProcessingPhase.TRANSCRIBING,
                                        fraction,
                                        event["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    )
                                }
                                "completed" -> {
                                    val outputs = event["outputs"]?.jsonArray?.mapNotNull {
                                        it.jsonPrimitive.contentOrNull
                                    }.orEmpty()
                                    result = TranscriptionResult(
                                        outputFiles = outputs,
                                        detectedLanguage = event["language"]?.jsonPrimitive?.contentOrNull,
                                    )
                                }
                                "error" -> onLog(event["message"]?.jsonPrimitive?.contentOrNull ?: line)
                            }
                        }
                    }
                }
                val stderr = async(Dispatchers.IO) {
                    process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { if (it.isNotBlank()) onLog(it) }
                    }
                }
                val exitCode = process.waitFor()
                stdout.await()
                stderr.await()
                check(exitCode == 0) { "Whisper завершился с кодом $exitCode" }
            }
            result ?: error("Whisper не вернул список результатов")
        }.also {
            currentProcess = null
        }
    }

    fun cancel() {
        currentProcess?.let { process ->
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        currentProcess = null
    }

    private fun prepareOnDemandRuntime(
        resourcesDir: File,
        onProgress: (ProcessingPhase, Float?, String) -> Unit,
        onLog: (String) -> Unit,
    ): File? {
        val requirements = File(resourcesDir, "python/requirements-whisper.txt")
        if (!requirements.isFile) return null
        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".ffmpeg-media-workshop")
        val targetDir = File(localAppData, "FFmpegMediaWorkshop/whisper")
        val venvDir = File(targetDir, ".venv")
        val python = File(venvDir, if (File.separatorChar == '\\') "Scripts/python.exe" else "bin/python")
        val readyMarker = File(targetDir, ".runtime-1.2.1-ready")
        if (python.isFile && readyMarker.isFile) return python

        targetDir.mkdirs()
        val toolsDir = File(targetDir, "tools").apply { mkdirs() }
        val uvExecutable = File(toolsDir, if (File.separatorChar == '\\') "uv.exe" else "uv")
        if (!uvExecutable.isFile) {
            val archive = File(targetDir, "uv-0.11.26.zip")
            onProgress(ProcessingPhase.LOADING_MODEL, 0f, "Загрузка установщика Python")
            downloadFile(
                url = UV_WINDOWS_URL,
                destination = archive,
                expectedSha256 = UV_WINDOWS_SHA256,
                onProgress = { fraction ->
                    onProgress(ProcessingPhase.LOADING_MODEL, fraction?.times(0.2f), "Загрузка компонента Whisper")
                },
            )
            extractZip(archive, toolsDir)
            require(uvExecutable.isFile) { "uv.exe не найден после распаковки" }
        }

        val environment = mapOf(
            "UV_PYTHON_INSTALL_DIR" to File(targetDir, "python").absolutePath,
            "UV_CACHE_DIR" to File(targetDir, "cache").absolutePath,
            "UV_NO_MODIFY_PATH" to "1",
            "UV_PYTHON_PREFERENCE" to "only-managed",
        )
        onProgress(ProcessingPhase.LOADING_MODEL, 0.2f, "Установка Python 3.12")
        runCommand(
            listOf(uvExecutable.absolutePath, "venv", "--python", "3.12", venvDir.absolutePath),
            targetDir,
            environment,
            onLog,
        )
        onProgress(ProcessingPhase.LOADING_MODEL, 0.45f, "Установка faster-whisper")
        runCommand(
            listOf(
                uvExecutable.absolutePath,
                "pip", "install",
                "--python", python.absolutePath,
                "-r", requirements.absolutePath,
            ),
            targetDir,
            environment,
            onLog,
        )
        readyMarker.writeText("ready\n")
        onProgress(ProcessingPhase.LOADING_MODEL, 1f, "Компонент Whisper установлен")
        return python.takeIf(File::isFile)
    }

    private fun downloadFile(
        url: String,
        destination: File,
        expectedSha256: String,
        onProgress: (Float?) -> Unit,
    ) {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        val total = connection.contentLengthLong
        val temporary = File(destination.parentFile, "${destination.name}.part")
        connection.getInputStream().use { input ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onProgress(if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else null)
                }
            }
        }
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(temporary.readBytes())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        require(actualSha256.equals(expectedSha256, ignoreCase = true)) { "Контрольная сумма uv не совпала" }
        if (destination.exists()) destination.delete()
        require(temporary.renameTo(destination)) { "Не удалось сохранить ${destination.absolutePath}" }
    }

    private fun extractZip(archive: File, targetDir: File) {
        val canonicalTarget = targetDir.canonicalFile.toPath()
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val output = File(targetDir, entry.name).canonicalFile
                require(output.toPath().startsWith(canonicalTarget)) { "Некорректный путь в Python runtime" }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun runCommand(
        command: List<String>,
        workingDir: File,
        environment: Map<String, String>,
        onLog: (String) -> Unit,
    ) {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        currentProcess = process
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach(onLog)
        }
        val exitCode = process.waitFor()
        currentProcess = null
        check(exitCode == 0) { "Установка компонента завершилась с кодом $exitCode" }
    }

    private companion object {
        const val UV_WINDOWS_URL =
            "https://github.com/astral-sh/uv/releases/download/0.11.26/uv-x86_64-pc-windows-msvc.zip"
        const val UV_WINDOWS_SHA256 =
            "4e1278ede866be6c0bf32d2f466cc6de7a9fb399ecf20c9ce2d186e52424be47"
    }
}
