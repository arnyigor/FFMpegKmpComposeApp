package com.arny.ffmpegcompose

import com.arny.ffmpegcompose.util.ProcessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JOptionPane

fun isWindows(): Boolean = File.separatorChar == '\\'

/**
 * Находит Python в venv рядом со скриптом.
 * @param scriptPath путь к .py
 * @param venvFolder "env" или "venv"
 * @return полный путь к python.exe/python, или null если не найден
 */
fun getVenvPython(scriptPath: String, venvFolder: String = "env"): String? {
    val scriptDir = File(scriptPath).parentFile ?: return null
    val venvDir = File(scriptDir, venvFolder)

    println("DEBUG: scriptDir=$scriptDir, venvDir=$venvDir")

    return when {
        isWindows() -> {
            val pyExe = File(venvDir, "Scripts/python.exe")
            if (pyExe.exists()) pyExe.absolutePath.also { println("DEBUG: Found Windows venv: $it") } else null
        }

        else -> {  // Linux/Mac
            val pyExe = File(venvDir, "bin/python")
            if (pyExe.exists()) pyExe.absolutePath.also { println("DEBUG: Found Unix venv: $it") } else
                File(venvDir, "bin/python3").takeIf { it.exists() }?.absolutePath
        }
    }
}

/**
 * Запускает внешнюю программу и возвращает её выходные данные.
 *
 * @param executable   путь к исполняемому файлу (например, "python3").
 * @param scriptPath  путь к скрипту Python.
 * @param args        аргументы скрипта.
 * @return ProcessResult – код завершения и выводы stdout/stderr.
 * @throws RuntimeException если таймаут или ошибка запуска.
 */
actual suspend fun runPythonScript(
    scriptPath: String,
    args: List<String>,
): ProcessResult = withContext(Dispatchers.IO) {
    val scriptFile = File(scriptPath)
    val finalWorkingDir = scriptFile.parentFile ?: File(".")
    val venvDir = File(finalWorkingDir, ".venv")

    println("DEBUG: venvDir=${venvDir.absolutePath}, exists=${venvDir.exists()}")
    println("DEBUG: scriptFile=${scriptFile.absolutePath}, exists=${scriptFile.exists()}")

    // Относительный путь к скрипту (для shell, но здесь direct — absolute OK)
    val relativeScriptPath = if (scriptFile.isAbsolute) {
        finalWorkingDir.toPath().relativize(scriptFile.toPath()).toString().replace("/", "\\")
    } else {
        scriptPath
    }
    println("DEBUG: relativeScriptPath=$relativeScriptPath")

    // 🔥 Direct venv python.exe (твой подход — работает!)
    val pyExePath = if (isWindows()) {
        File(venvDir, "Scripts/python.exe").absolutePath
    } else {
        File(venvDir, "bin/python").absolutePath
    }
    val command = listOf(pyExePath, scriptPath) + args  // scriptPath absolute OK с workingDir

    println("DEBUG: Full command: ${command.joinToString(" ")} | Dir: $finalWorkingDir")

    val processBuilder = ProcessBuilder(command)
        .directory(finalWorkingDir)
        .redirectErrorStream(false)

    // 🔥 ФИКС UTF-8 + EMOJI!
    val env = processBuilder.environment()
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    env["LC_ALL"] = "C.UTF-8"
    env["LANG"] = "en_US.UTF-8"

    val process = processBuilder.start()

    // 🔥 ИСПОЛЬЗУЕМ async ВМЕСТО CompletableFuture
    // Это позволяет корутинам управлять потоками чтения
    val stdoutDeferred = async(Dispatchers.IO) {
        readStream(process.inputStream, "STDOUT")
    }
    val stderrDeferred = async(Dispatchers.IO) {
        readStream(process.errorStream, "STDERR")
    }

    // Ждем завершения процесса (без таймаута runTest, используем нативный wait)
    // Можно добавить withTimeout() обертку при желании
    val exitCode = try {
        // waitFor блокирует поток, но мы внутри Dispatchers.IO, это безопасно
        if (process.waitFor(10, TimeUnit.MINUTES)) {
            process.exitValue()
        } else {
            process.destroy()
            -1
        }
    } catch (e: InterruptedException) {
        process.destroy()
        -1
    }

    // Получаем результаты чтения потоков
    val stdout = stdoutDeferred.await()
    val stderr = stderrDeferred.await()

    println("🎉 FINAL: exit=$exitCode | STDOUT len=${stdout.length} | STDERR len=${stderr.length}")
    println("STDOUT preview: ${stdout.take(300)}...")

    ProcessResult(exitCode, stdout, stderr)
}

// Хелпер для чтения потока
private fun readStream(stream: InputStream, name: String): String {
    return stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
        val builder = StringBuilder()
        // Читаем построчно, чтобы сразу выводить в лог
        reader.forEachLine { line ->
            synchronized(System.out) { // Синхронизация, чтобы логи не перемешивались
                println("DEBUG $name: $line")
            }
            builder.append(line).append("\n")
        }
        builder.toString()
    }
}

private suspend fun readAll(stream: InputStream): String = withContext(Dispatchers.IO) {
    try {
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        "" // или log и throw, в зависимости от нужд
    }
}

actual fun getPlatformName(): String {
    val osName = System.getProperty("os.name")
    val osVersion = System.getProperty("os.version")
    return "Desktop $osName $osVersion"
}

actual fun showNotification(message: String) {
    if (GraphicsEnvironment.isHeadless()) {
        println("🖥️ Desktop (headless): $message")
        return
    }

    val os = System.getProperty("os.name").lowercase()

    try {
        when {
            os.contains("win") -> {
                // Windows уведомления
                showWindowsNotification(message)
            }

            os.contains("mac") -> {
                // macOS уведомления
                showMacNotification(message)
            }

            os.contains("nix") || os.contains("nux") -> {
                // Linux уведомления
                showLinuxNotification(message)
            }

            else -> {
                // Универсальный fallback
                JOptionPane.showMessageDialog(null, message)
            }
        }
    } catch (e: Exception) {
        println("🖥️ Desktop: $message")
    }
}

private fun showWindowsNotification(message: String) {
    // Можно использовать PowerShell или WinAPI
    Runtime.getRuntime().exec(
        arrayOf(
            "powershell", "-Command",
            "[reflection.assembly]::loadwithpartialname('System.Windows.Forms');" +
                    "[reflection.assembly]::loadwithpartialname('System.Drawing');" +
                    "\$notify = new-object system.windows.forms.notifyicon;" +
                    "\$notify.icon = [System.Drawing.SystemIcons]::Information;" +
                    "\$notify.visible = \$true;" +
                    "\$notify.showballoontip(10,'KMP Notification','$message',[system.windows.forms.tooltipicon]::Info)"
        )
    )
}

private fun showMacNotification(message: String) {
    Runtime.getRuntime().exec(
        arrayOf(
            "osascript", "-e",
            "display notification \"$message\" with title \"KMP Notification\""
        )
    )
}

private fun showLinuxNotification(message: String) {
    Runtime.getRuntime().exec(
        arrayOf(
            "notify-send", "KMP Notification", message
        )
    )
}

actual fun generateId(): String = UUID.randomUUID().toString()