package com.arny.ffmpegcompose.data

import com.arny.ffmpegcompose.data.models.ProcessingPhase
import com.arny.ffmpegcompose.data.models.SmartVoiceDevice
import com.arny.ffmpegcompose.data.models.SmartVoiceSeparationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class SmartVoiceExecutor {
    @Volatile
    private var currentProcess: Process? = null

    suspend fun separateVocals(
        audioFile: String,
        workDir: File,
        device: SmartVoiceDevice,
        model: SmartVoiceSeparationModel,
        onProgress: (ProcessingPhase, Float?, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<SeparatedVoiceStems> = withContext(Dispatchers.IO) {
        runCatching {
            val python = prepareRuntime(device, onProgress, onLog)
            val actualDevice = resolveDevice(python, device, onLog)
            ensureKnownModel(model, onProgress, onLog)
            val input = File(audioFile).absoluteFile
            val separatedDir = File(workDir, "demucs").apply { mkdirs() }
            val command = buildList {
                add(python.absolutePath)
                add("-m")
                add("demucs.separate")
                add("--two-stems=vocals")
                add("--name")
                add(model.cliName)
                actualDevice.cliName?.let {
                    add("--device")
                    add(it)
                }
                add("--out")
                add(separatedDir.absolutePath)
                add(input.absolutePath)
            }
            onLog("Модель Demucs: ${model.title} (${model.cliName})")
            onLog("Устройство Demucs: ${actualDevice.title}")
            onLog("$ ${command.joinToString(" ")}")
            onProgress(ProcessingPhase.SEPARATING_VOICE, null, "Разделение оригинала на голос и фон")
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(false)
                .apply {
                    environment()["PYTHONIOENCODING"] = "utf-8"
                    environment()["PYTHONUTF8"] = "1"
                }
                .start()
            currentProcess = process

            coroutineScope {
                val stdout = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) onLog(line)
                        }
                    }
                }
                val stderr = async(Dispatchers.IO) {
                    readDemucsProgress(process, onProgress, onLog)
                }
                val exitCode = process.waitFor()
                stdout.await()
                stderr.await()
                check(exitCode == 0) { "Demucs завершился с кодом $exitCode" }
            }

            currentProcess = null
            val stemsDir = File(separatedDir, "${model.cliName}/${input.nameWithoutExtension}")
            val vocals = File(stemsDir, "vocals.wav")
            val background = File(stemsDir, "no_vocals.wav")
            require(vocals.isFile) { "Demucs не создал vocals.wav" }
            require(background.isFile) { "Demucs не создал no_vocals.wav" }
            SeparatedVoiceStems(vocalsFile = vocals.absolutePath, backgroundFile = background.absolutePath)
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

    private fun prepareRuntime(
        device: SmartVoiceDevice,
        onProgress: (ProcessingPhase, Float?, String) -> Unit,
        onLog: (String) -> Unit,
    ): File {
        val projectDir = File(System.getProperty("user.dir"))
        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
        val existingPython = listOfNotNull(projectDir, resourcesDir?.resolve("python"), resourcesDir)
            .asSequence()
            .flatMap { root ->
                sequenceOf(
                    File(root, ".venv/Scripts/python.exe"),
                    File(root, ".venv/bin/python"),
                    File(root, ".venv/bin/python3"),
                )
            }
            .firstOrNull(File::isFile)
        if (existingPython != null && hasDemucs(existingPython, onLog)) {
            if (device == SmartVoiceDevice.CUDA && !isCudaAvailable(existingPython, onLog)) {
                onLog("В найденном окружении Demucs CUDA недоступна, будет использовано отдельное окружение smart-voice")
            } else {
                return existingPython
            }
        }

        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".ffmpeg-media-workshop")
        val targetDir = File(localAppData, "FFmpegMediaWorkshop/smart-voice")
        val venvDir = File(targetDir, ".venv")
        val python = File(venvDir, if (File.separatorChar == '\\') "Scripts/python.exe" else "bin/python")
        val readyMarker = File(targetDir, ".runtime-demucs-4.0.1-ready")
        if (python.isFile && readyMarker.isFile && hasDemucs(python, onLog)) {
            if (device == SmartVoiceDevice.CUDA) ensureCudaTorch(python, targetDir, uvExecutable = null, environment = emptyMap(), onProgress, onLog)
            return python
        }

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
                    onProgress(ProcessingPhase.LOADING_MODEL, fraction?.times(0.2f), "Загрузка компонента Demucs")
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
        onProgress(ProcessingPhase.LOADING_MODEL, 0.2f, "Установка Python 3.11")
        runCommand(listOf(uvExecutable.absolutePath, "python", "install", "3.11"), targetDir, environment, onLog)
        onProgress(ProcessingPhase.LOADING_MODEL, 0.35f, "Создание окружения Demucs")
        if (python.isFile) {
            onLog("Окружение Demucs уже существует: ${venvDir.absolutePath}")
        } else {
            runCommand(
                listOf(uvExecutable.absolutePath, "venv", "--clear", "--python", "3.11", venvDir.absolutePath),
                targetDir,
                environment,
                onLog,
            )
        }
        onProgress(ProcessingPhase.LOADING_MODEL, 0.55f, "Установка Demucs / PyTorch")
        runCommand(
            listOf(uvExecutable.absolutePath, "pip", "install", "--python", python.absolutePath, "demucs==4.0.1"),
            targetDir,
            environment,
            onLog,
        )
        if (device == SmartVoiceDevice.CUDA) {
            ensureCudaTorch(python, targetDir, uvExecutable, environment, onProgress, onLog)
        }
        readyMarker.writeText("ready\n")
        onProgress(ProcessingPhase.LOADING_MODEL, 1f, "Компонент Demucs установлен")
        return python.also { require(it.isFile) { "Python runtime Demucs не найден" } }
    }

    private fun resolveDevice(python: File, device: SmartVoiceDevice, onLog: (String) -> Unit): SmartVoiceDevice =
        when (device) {
            SmartVoiceDevice.CUDA -> {
                require(isCudaAvailable(python, onLog)) { "CUDA недоступна для PyTorch. Проверьте NVIDIA-драйвер или выберите CPU." }
                SmartVoiceDevice.CUDA
            }
            SmartVoiceDevice.CPU -> SmartVoiceDevice.CPU
            SmartVoiceDevice.AUTO -> if (isCudaAvailable(python, onLog)) SmartVoiceDevice.CUDA else SmartVoiceDevice.CPU
        }

    private fun ensureCudaTorch(
        python: File,
        targetDir: File,
        uvExecutable: File?,
        environment: Map<String, String>,
        onProgress: (ProcessingPhase, Float?, String) -> Unit,
        onLog: (String) -> Unit,
    ) {
        val cudaAvailable = isCudaAvailable(python, onLog)
        if (cudaAvailable && hasTorchaudioSoundfilePatch(python, onLog)) return
        val uv = uvExecutable ?: File(targetDir, "tools/${if (File.separatorChar == '\\') "uv.exe" else "uv"}")
        require(uv.isFile) { "uv.exe не найден для установки CUDA PyTorch" }
        if (!cudaAvailable) {
            onProgress(ProcessingPhase.LOADING_MODEL, null, "Установка CUDA 12.8 PyTorch для Demucs")
            runCommand(
                listOf(
                    uv.absolutePath,
                    "pip", "install",
                    "--python", python.absolutePath,
                    "--reinstall",
                    "--upgrade",
                    "--pre",
                    "--index-url", PYTORCH_CUDA_INDEX_URL,
                    "torch",
                    "torchaudio",
                ),
                targetDir,
                environment,
                onLog,
            )
        }
        onProgress(ProcessingPhase.LOADING_MODEL, null, "Установка soundfile для сохранения WAV")
        runCommand(
            listOf(uv.absolutePath, "pip", "install", "--python", python.absolutePath, "--upgrade", "soundfile"),
            targetDir,
            environment,
            onLog,
        )
        installTorchaudioSoundfilePatch(python, onLog)
        require(isCudaAvailable(python, onLog)) {
            "CUDA PyTorch установлен, но видеокарта всё ещё не поддерживается этим PyTorch. " +
                    "Для RTX 50xx/sm_120 нужен PyTorch с поддержкой CUDA 12.8+; выберите CPU/Авто или обновите PyTorch позже."
        }
        require(hasTorchaudioSoundfilePatch(python, onLog)) { "Не удалось включить soundfile fallback для torchaudio.save" }
    }

    private fun installTorchaudioSoundfilePatch(python: File, onLog: (String) -> Unit) {
        val sitePackages = getPythonOutput(
            python,
            "import site; print(site.getsitepackages()[0])",
            timeoutSec = 15,
        ).trim().let(::File)
        require(sitePackages.isDirectory) { "site-packages не найден: ${sitePackages.absolutePath}" }
        val patch = File(sitePackages, "sitecustomize.py")
        patch.writeText(
            """
            import os
            if os.environ.get("FFMW_DISABLE_TORCHAUDIO_SOUNDFILE_PATCH") != "1":
                try:
                    import torch
                    import torchaudio
                    import soundfile as sf

                    def _ffmw_save(uri, src, sample_rate, channels_first=True, format=None,
                                   encoding=None, bits_per_sample=None, buffer_size=4096,
                                   compression=None, **kwargs):
                        data = src.detach().cpu()
                        if data.dim() > 1 and channels_first:
                            data = data.transpose(0, 1)
                        subtype = None
                        if bits_per_sample == 16 or encoding in ("PCM_S", "PCM_S16LE"):
                            subtype = "PCM_16"
                        sf.write(str(uri), data.numpy(), int(sample_rate), subtype=subtype)

                    torchaudio.save = _ffmw_save
                    torchaudio._ffmw_soundfile_patch = True
                except Exception:
                    pass
            """.trimIndent(),
            Charsets.UTF_8,
        )
        onLog("Включён soundfile fallback для torchaudio.save: ${patch.absolutePath}")
    }

    private fun hasTorchaudioSoundfilePatch(python: File, onLog: (String) -> Unit): Boolean = runCatching {
        val output = getPythonOutput(
            python,
            "import torchaudio; print('soundfile_patch=' + str(getattr(torchaudio, '_ffmw_soundfile_patch', False)))",
            timeoutSec = 15,
        )
        onLog(output.trim().takeIf(String::isNotBlank) ?: "soundfile_patch=False")
        output.contains("soundfile_patch=True", ignoreCase = true)
    }.getOrElse {
        onLog("Soundfile fallback не найден: ${it.message}")
        false
    }

    private fun getPythonOutput(python: File, code: String, timeoutSec: Long): String {
        val process = ProcessBuilder(python.absolutePath, "-c", code)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val completed = process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            error("Python command timeout")
        }
        check(process.exitValue() == 0) { output.ifBlank { "Python command failed" } }
        return output
    }

    private fun isCudaAvailable(python: File, onLog: (String) -> Unit): Boolean = runCatching {
        val process = ProcessBuilder(
            python.absolutePath,
            "-c",
            "import torch; ok=torch.cuda.is_available(); " +
                    "print(f'torch={torch.__version__}; torch_cuda={torch.version.cuda}; cuda={ok}; devices={torch.cuda.device_count()}'); " +
                    "torch.ones(1, device='cuda').cpu() if ok else None",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val completed = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return@runCatching false
        }
        onLog(output.trim().takeIf(String::isNotBlank) ?: "cuda=false")
        process.exitValue() == 0 && output.contains("cuda=True", ignoreCase = true)
    }.getOrElse {
        onLog("Проверка CUDA не удалась: ${it.message}")
        false
    }

    private fun ensureKnownModel(
        model: SmartVoiceSeparationModel,
        onProgress: (ProcessingPhase, Float?, String) -> Unit,
        onLog: (String) -> Unit,
    ) {
        if (model != SmartVoiceSeparationModel.HTDEMUCS) {
            onLog("Для ${model.title} модель скачает Demucs. Если скачивание нестабильно, используйте HTDemucs.")
            return
        }
        val checkpointsDir = File(System.getProperty("user.home"), ".cache/torch/hub/checkpoints").apply { mkdirs() }
        val modelFile = File(checkpointsDir, HTDEMUCS_MODEL_NAME)
        if (modelFile.isFile && modelFile.length() > 0L) {
            onLog("Модель Demucs уже скачана: ${modelFile.absolutePath}")
            return
        }
        onLog("Скачивание модели Demucs: ${HTDEMUCS_MODEL_URLS.joinToString(" или ")}")
        downloadModelWithResume(
            urls = HTDEMUCS_MODEL_URLS,
            destination = modelFile,
            onProgress = onProgress,
            onLog = onLog,
        )
        onProgress(ProcessingPhase.SEPARATING_VOICE, 1f, "Модель Demucs скачана")
        onLog("Модель Demucs скачана: ${modelFile.absolutePath}")
    }

    private fun downloadModelWithResume(
        urls: List<String>,
        destination: File,
        onProgress: (ProcessingPhase, Float?, String) -> Unit,
        onLog: (String) -> Unit,
    ) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var lastError: Exception? = null
        repeat(MODEL_DOWNLOAD_RETRIES) { attempt ->
            urls.forEachIndexed { urlIndex, url ->
                try {
                    onLog("Источник модели Demucs ${urlIndex + 1}/${urls.size}: $url")
                    val existing = temporary.takeIf(File::isFile)?.length() ?: 0L
                    val connection = URL(url).openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = MODEL_DOWNLOAD_READ_TIMEOUT_MS
                        if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                        setRequestProperty("User-Agent", "FFmpegMediaWorkshop/1.0")
                    }
                    val http = connection as? HttpURLConnection
                    val responseCode = http?.responseCode
                    val append = existing > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
                    if (existing > 0L && append) {
                        onLog("Продолжаем скачивание модели Demucs с ${existing.toMiB()} MB")
                    } else if (existing > 0L) {
                        temporary.delete()
                        onLog("Источник не поддержал продолжение скачивания, начинаем модель заново")
                    }
                    val startBytes = if (append) existing else 0L
                    val responseSize = connection.contentLengthLong
                    val total = if (responseSize > 0L) responseSize + startBytes else -1L
                    FileOutputStream(temporary, append).buffered().use { output ->
                        connection.getInputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = startBytes
                            var lastPercent = -1
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                val fraction = if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
                                val percent = fraction?.let { (it * 100).toInt() }
                                val detail = if (total > 0) {
                                    "Скачивание модели Demucs ${percent ?: 0}% (${downloaded.toMiB()} / ${total.toMiB()} MB)"
                                } else {
                                    "Скачивание модели Demucs ${downloaded.toMiB()} MB"
                                }
                                onProgress(ProcessingPhase.SEPARATING_VOICE, fraction, detail)
                                if (percent != null && percent != lastPercent && (percent % 5 == 0 || percent == 100)) {
                                    onLog(detail)
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                    if (destination.exists()) destination.delete()
                    require(temporary.renameTo(destination)) { "Не удалось сохранить модель Demucs: ${destination.absolutePath}" }
                    return
                } catch (error: Exception) {
                    lastError = error
                    onLog(
                        "Ошибка скачивания модели Demucs из источника ${urlIndex + 1}, " +
                                "попытка ${attempt + 1}/$MODEL_DOWNLOAD_RETRIES: ${error.message}"
                    )
                }
            }
            Thread.sleep(2_000L * (attempt + 1))
        }
        throw lastError ?: IllegalStateException("Не удалось скачать модель Demucs")
    }

    private fun Long.toMiB(): Long = this / (1024 * 1024)

    private fun hasDemucs(python: File, onLog: (String) -> Unit): Boolean = runCatching {
        val process = ProcessBuilder(python.absolutePath, "-m", "demucs.separate", "--help")
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrElse {
        onLog("Demucs не найден в ${python.absolutePath}: ${it.message}")
        false
    }

    private fun readDemucsProgress(
        process: Process,
        onProgress: (ProcessingPhase, Float?, String) -> Unit,
        onLog: (String) -> Unit,
    ) {
        val token = StringBuilder()
        var lastLoggedProgress = -1
        fun flush() {
            val text = token.toString().trim()
            token.clear()
            if (text.isBlank()) return
            val fraction = parseDemucsProgress(text)
            if (fraction != null) {
                val percent = (fraction * 100).toInt()
                val detail = if (text.contains("B/s") || text.contains(Regex("/\\d+(\\.\\d+)?[KMG]"))) {
                    "Скачивание модели Demucs $percent%"
                } else {
                    "Demucs $percent%"
                }
                onProgress(ProcessingPhase.SEPARATING_VOICE, fraction, detail)
                if (percent != lastLoggedProgress && (percent % 5 == 0 || percent == 100)) {
                    onLog(detail)
                    lastLoggedProgress = percent
                }
            } else {
                onLog(text)
            }
        }
        process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val code = reader.read()
                if (code < 0) break
                val ch = code.toChar()
                if (ch == '\r' || ch == '\n') {
                    flush()
                } else {
                    token.append(ch)
                }
            }
        }
        flush()
    }

    private fun parseDemucsProgress(line: String): Float? {
        val match = Regex("(\\d{1,3})%").find(line) ?: return null
        return (match.groupValues[1].toIntOrNull() ?: return null).coerceIn(0, 100) / 100f
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
        onLog("$ ${command.joinToString(" ")}")
        val recentOutput = ArrayDeque<String>()
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        currentProcess = process
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (recentOutput.size == 20) recentOutput.removeFirst()
                recentOutput.addLast(line)
                onLog(line)
            }
        }
        val exitCode = process.waitFor()
        currentProcess = null
        check(exitCode == 0) {
            buildString {
                append("Установка компонента завершилась с кодом $exitCode")
                if (recentOutput.isNotEmpty()) {
                    append("\nПоследние строки:\n")
                    append(recentOutput.joinToString("\n"))
                }
            }
        }
    }

    private companion object {
        const val UV_WINDOWS_URL =
            "https://github.com/astral-sh/uv/releases/download/0.11.26/uv-x86_64-pc-windows-msvc.zip"
        const val UV_WINDOWS_SHA256 =
            "4e1278ede866be6c0bf32d2f466cc6de7a9fb399ecf20c9ce2d186e52424be47"
        const val PYTORCH_CUDA_INDEX_URL = "https://download.pytorch.org/whl/nightly/cu128"
        const val MODEL_DOWNLOAD_RETRIES = 5
        const val MODEL_DOWNLOAD_READ_TIMEOUT_MS = 300_000
        const val HTDEMUCS_MODEL_NAME = "955717e8-8726e21a.th"
        val HTDEMUCS_MODEL_URLS = listOf(
            "https://huggingface.co/Politrees/UVR_resources/resolve/main/models/Demucs/Demucs_v4/$HTDEMUCS_MODEL_NAME?download=true",
        )
    }
}

data class SeparatedVoiceStems(
    val vocalsFile: String,
    val backgroundFile: String,
)
