package com.arny.ffmpegcompose.data

/**
 * Утилиты для конвертации времени
 */
object TimeUtils {

    /**
     * Парсит строку времени в миллисекунды.
     * Если строка некорректна — возвращает 0L.
     * Поддерживаемые форматы:
     * - "HH:MM:SS" → 01:30:45
     *
     * @return миллисекунды или 0L при ошибке
     */
    fun parseToMs(timeString: String): Long {
        require(timeString.isNotBlank()) { "Время не задано" }

        val parts = timeString.split(":")
        require(parts.size == 3) { "Используйте формат ЧЧ:ММ:СС" }
        val hours = parts[0].toLongOrNull() ?: error("Некорректные часы")
        val minutes = parts[1].toLongOrNull() ?: error("Некорректные минуты")
        val secondsParts = parts[2].split(".", limit = 2)
        val seconds = secondsParts[0].toLongOrNull() ?: error("Некорректные секунды")
        val millis = secondsParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        require(hours >= 0 && minutes in 0..59 && seconds in 0..59) { "Минуты и секунды должны быть от 00 до 59" }
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }


    /**
     * Форматирует миллисекунды в читаемую строку HH:MM:SS
     */
    fun formatMsToReadable(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "%02d:%02d:%02d".format(hours, minutes, seconds)
            minutes > 0 -> "%02d:%02d".format(minutes, seconds)
            seconds > 0 -> "%02d".format( seconds)
            else -> ""
        }
    }
}
