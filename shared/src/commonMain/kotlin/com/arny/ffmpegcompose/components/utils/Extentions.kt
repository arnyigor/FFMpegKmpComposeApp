package com.arny.ffmpegcompose.components.utils


fun String?.toFrameRate(): Double? {
    if (this.isNullOrBlank() || this == "0/0" || this == "N/A") return null
    return try {
        if (contains('/')) {
            val (num, den) = split('/', limit = 2)
            num.toDouble() / den.toDouble()
        } else {
            toDouble()
        }
    } catch (e: Exception) {
        null
    }
}

fun Double?.formatFps(): String = when {
    this == null -> "—"
    this < 1_000_000 -> "%.2f".format(this) // обычный FPS
    else -> "∞" // бесконечность (редко, но бывает)
}

fun String?.toDurationSeconds(): Double? = this?.toDoubleOrNull()

fun Double?.toReadableDuration(): String {
    val sec = this ?: return "—"
    val hours = (sec / 3600).toInt()
    val minutes = ((sec % 3600) / 60).toInt()
    val seconds = (sec % 60).toInt()
    return buildString {
        if (hours > 0) append("%02d:".format(hours))
        append("%02d:%02d".format(minutes, seconds))
    }
}

fun Long?.toReadableSize(): String {
    if (this == null || this <= 0) return "—"
    return when {
        this < 1_000 -> "${this} B"
        this < 1_000_000 -> "%.1f KB".format(this / 1024.0)
        this < 1_000_000_000 -> "%.1f MB".format(this / 1_048_576.0)
        else -> "%.1f GB".format(this / 1_073_741_824.0)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}