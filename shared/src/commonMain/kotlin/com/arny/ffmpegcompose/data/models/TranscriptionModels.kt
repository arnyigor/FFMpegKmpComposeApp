package com.arny.ffmpegcompose.data.models

enum class ProcessingPhase(val title: String) {
    IDLE("Готово к запуску"),
    PROBING("Анализ файла"),
    CONVERTING("Обработка FFmpeg"),
    EXTRACTING_AUDIO("Извлечение звука"),
    LOADING_MODEL("Загрузка модели Whisper"),
    TRANSCRIBING("Распознавание речи"),
    EXPORTING("Сохранение результата"),
    COMPLETED("Готово"),
    CANCELLED("Отменено"),
    FAILED("Ошибка")
}

enum class WhisperModelOption(val modelId: String, val title: String, val hint: String) {
    TINY("tiny", "Tiny", "Самая быстрая, минимальная точность"),
    BASE("base", "Base", "Быстро, подходит для черновика"),
    SMALL("small", "Small", "Рекомендуется для большинства файлов"),
    MEDIUM("medium", "Medium", "Выше точность, больше памяти"),
    LARGE_V3("large-v3", "Large v3", "Максимальная точность, требуется мощный GPU")
}

data class WhisperSettings(
    val model: WhisperModelOption = WhisperModelOption.SMALL,
    val language: String = "auto",
    val device: String = "auto",
    val wordTimestamps: Boolean = false,
)

data class ProcessingProgress(
    val phase: ProcessingPhase = ProcessingPhase.IDLE,
    val phaseProgress: Float? = null,
    val overallProgress: Float? = null,
    val elapsedMs: Long = 0L,
    val estimatedRemainingMs: Long? = null,
    val detail: String = "",
)

data class TranscriptionResult(
    val outputFiles: List<String>,
    val detectedLanguage: String? = null,
)
