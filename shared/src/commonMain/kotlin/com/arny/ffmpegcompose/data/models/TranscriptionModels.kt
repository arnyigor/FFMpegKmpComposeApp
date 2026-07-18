package com.arny.ffmpegcompose.data.models

enum class ProcessingPhase(val title: String) {
    IDLE("Готово к запуску"),
    PROBING("Анализ файла"),
    CONVERTING("Обработка FFmpeg"),
    EXTRACTING_AUDIO("Извлечение звука"),
    LOADING_MODEL("Загрузка модели"),
    TRANSCRIBING("Распознавание речи"),
    SEPARATING_VOICE("Отделение голоса"),
    MIXING_AUDIO("Сведение звука"),
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

enum class SmartVoiceDevice(val title: String, val cliName: String?) {
    AUTO("Авто", null),
    CPU("CPU", "cpu"),
    CUDA("CUDA", "cuda"),
}

enum class SmartVoiceSeparationModel(val title: String, val cliName: String, val hint: String) {
    HTDEMUCS(
        "HTDemucs",
        "htdemucs",
        "Оптимальный выбор для замены голоса: хорошо сохраняет музыку/фон и достаточно быстро работает на CUDA.",
    ),
    HTDEMUCS_FT(
        "HTDemucs FT",
        "htdemucs_ft",
        "Более качественная fine-tuned версия HTDemucs: лучше разделяет сложную музыку, но заметно медленнее.",
    ),
    MDX_EXTRA_Q(
        "MDX Extra Q",
        "mdx_extra_q",
        "Быстрее и легче для тестов/слабого ПК; может оставлять больше артефактов и хуже отделять голос.",
    ),
}

data class SmartVoiceSettings(
    val originalVoiceVolumePercent: Int = 15,
    val replacementVoiceVolumePercent: Int = 100,
    val device: SmartVoiceDevice = SmartVoiceDevice.AUTO,
    val model: SmartVoiceSeparationModel = SmartVoiceSeparationModel.HTDEMUCS,
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
