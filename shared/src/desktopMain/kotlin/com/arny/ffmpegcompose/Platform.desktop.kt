package com.arny.ffmpegcompose

import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import java.util.UUID

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