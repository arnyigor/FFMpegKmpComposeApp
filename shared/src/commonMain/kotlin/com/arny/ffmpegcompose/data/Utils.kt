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
        if (timeString.isBlank()) return 0L

        val parts = timeString.split(":")

        return when (parts.size) {
            3 -> {
                val hours = parts[0].toLongOrNull() ?: 0
                val minutes = parts[1].toLongOrNull() ?: 0
                val secondsParts = parts[2].split(".")
                val seconds = secondsParts[0].toLongOrNull() ?: 0

                // Обработка переполнения секунд и минут
                var totalSeconds = seconds
                var totalMinutes = minutes
                var totalHours = hours

                // Перенос секунд в минуты
                totalSeconds %= 60
                totalMinutes += seconds / 60

                // Перенос минут в часы
                val hoursOverflow  = totalMinutes / 60
                totalMinutes %= 60
                totalHours += hoursOverflow

                // Конвертация в миллисекунды
                val totalMillis = totalHours * 3600 * 1000 +
                        totalMinutes * 60 * 1000 +
                        totalSeconds * 1000
                totalMillis
            }
            else -> error("Не поддерживаемый формат ")
        }
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
