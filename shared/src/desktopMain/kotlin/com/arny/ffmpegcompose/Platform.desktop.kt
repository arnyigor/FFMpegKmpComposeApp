package com.arny.ffmpegcompose

import com.arny.ffmpegcompose.util.ProcessResult
import java.awt.GraphicsEnvironment
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.swing.JOptionPane
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Запускает внешнюю программу и возвращает её выходные данные.
 *
 * @param executable   путь к исполняемому файлу (например, "python3").
 * @param scriptPath  путь к скрипту Python.
 * @return [ProcessResult] – код завершения и выводы stdout/stderr.
 */
actual fun runPythonScript(
    executable: String,
    scriptPath: String,
    args: List<String>
): ProcessResult {
    // Безопасно формируем команду: каждый аргумент – отдельный элемент списка.
    val command = mutableListOf(executable, scriptPath)
    command.addAll(args)

    val process = ProcessBuilder(command)
        .redirectErrorStream(false)  // будем читать stdout и stderr отдельно
        .start()

    // Считываем потоки в отдельных потоках, чтобы избежать блокировки.
    val stdoutFuture = java.util.concurrent.Executors.newSingleThreadExecutor()
        .submit { readAll(process.inputStream) }
    val stderrFuture = java.util.concurrent.Executors.newSingleThreadExecutor()
        .submit { readAll(process.errorStream) }

    // Ожидаем завершения процесса (таймаут 30 секунд).
    val finished = process.waitFor(30, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        throw RuntimeException("Python script timed out")
    }

    return ProcessResult(
        exitCode = process.exitValue(),
        stdout   = (stdoutFuture.get() ?: "") as String,
        stderr   = (stderrFuture.get() ?: "") as String
    )
}

private fun readAll(stream: java.io.InputStream): String {
    val reader = BufferedReader(InputStreamReader(stream))
    val sb = StringBuilder()
    var line: String? = reader.readLine()
    while (line != null) {
        sb.append(line).append('\n')
        line = reader.readLine()
    }
    return sb.toString().trimEnd()
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
    Runtime.getRuntime().exec(arrayOf(
        "powershell", "-Command",
        "[reflection.assembly]::loadwithpartialname('System.Windows.Forms');" +
                "[reflection.assembly]::loadwithpartialname('System.Drawing');" +
                "\$notify = new-object system.windows.forms.notifyicon;" +
                "\$notify.icon = [System.Drawing.SystemIcons]::Information;" +
                "\$notify.visible = \$true;" +
                "\$notify.showballoontip(10,'KMP Notification','$message',[system.windows.forms.tooltipicon]::Info)"
    ))
}

private fun showMacNotification(message: String) {
    Runtime.getRuntime().exec(arrayOf(
        "osascript", "-e",
        "display notification \"$message\" with title \"KMP Notification\""
    ))
}

private fun showLinuxNotification(message: String) {
    Runtime.getRuntime().exec(arrayOf(
        "notify-send", "KMP Notification", message
    ))
}

// composeApp/src/desktopMain/kotlin/platform/Platform.desktop.kt

actual fun generateId(): String = UUID.randomUUID().toString()