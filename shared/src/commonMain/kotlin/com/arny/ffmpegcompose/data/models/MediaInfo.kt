package com.arny.ffmpegcompose.data.models

import com.arny.ffmpegcompose.components.home.ConvertType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Стратегия обрезки видео
 */
enum class TrimStrategy {
    /**
     * Автоматический выбор (рекомендуется)
     * - Для STREAM_COPY: быстрая обрезка по keyframe
     * - Для CONVERT: точная обрезка с реенкодированием
     */
    AUTO,

    /**
     * Быстрая обрезка по keyframe
     * ⚡ Мгновенно, но неточно (±1-2 секунды)
     * 📌 Использует -ss ДО -i + stream copy
     */
    FAST,

    /**
     * Точная обрезка до кадра
     * 🎯 Точность до миллисекунд
     * ⏱️ Медленнее: требует декодирования
     * 📌 Использует -ss ПОСЛЕ -i
     */
    ACCURATE
}

/**
 * Параметры для конфигурации FFmpeg команды
 */
data class ConversionParams(
    val inputFile: String,
    val outputFile: String,
    val audioFile: String? = null,
    val convertType: ConvertType,
    val replaceAudio: Boolean = false,
    val videoCodec: VideoCodec = VideoCodec.LIBX264,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val preset: String = "medium",
    val crf: Int = 23,
    val totalDurationMs: Long = 0L,

    // ========== ПАРАМЕТРЫ ОБРЕЗКИ ==========

    /**
     * Время начала обрезки в миллисекундах
     * null = начать с начала файла
     */
    val trimStartMs: Long? = null,

    /**
     * Время окончания обрезки в миллисекундах
     * null = обрезать до конца файла
     *
     * Примечание: если задано и trimStartMs и trimEndMs,
     * длительность = trimEndMs - trimStartMs
     */
    val trimEndMs: Long? = null,

    /**
     * Стратегия обрезки
     * По умолчанию AUTO выбирает оптимальную стратегию
     */
    val trimStrategy: TrimStrategy = TrimStrategy.AUTO
) {
    /**
     * Проверяет, нужна ли обрезка
     */
    fun shouldTrim(): Boolean = trimStartMs != null || trimEndMs != null

    /**
     * Вычисляет длительность обрезанного фрагмента в миллисекундах
     * Возвращает null если невозможно вычислить
     */
    fun getTrimDurationMs(): Long? {
        return when {
            trimStartMs != null && trimEndMs != null -> trimEndMs - trimStartMs
            trimEndMs != null -> trimEndMs
            else -> null
        }
    }

    /**
     * Форматирует миллисекунды в FFmpeg формат HH:MM:SS.mmm
     */
    fun formatTimeMs(timeMs: Long): String {
        val totalSeconds = timeMs / 1000.0
        val hours = (totalSeconds / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = totalSeconds % 60
        return "%02d:%02d:%06.3f".format(hours, minutes, seconds)
    }

    /**
     * Определяет эффективную стратегию обрезки на основе AUTO
     */
    fun getEffectiveTrimStrategy(): TrimStrategy {
        if (!shouldTrim()) return TrimStrategy.FAST

        return when (trimStrategy) {
            TrimStrategy.AUTO -> {
                // Автоматический выбор на основе режима конвертации
                when (convertType) {
                    ConvertType.STREAM_COPY -> TrimStrategy.FAST
                    ConvertType.CONVERT -> TrimStrategy.ACCURATE
                }
            }
            else -> trimStrategy
        }
    }
}

enum class VideoCodec(val codecName: String) {
    COPY("copy"),
    LIBX264("libx264"),
    LIBX265("libx265"),
    VP9("libvpx-vp9")
}

enum class AudioCodec(val codecName: String) {
    COPY("copy"),
    AAC("aac"),
    MP3("libmp3lame"),
    OPUS("libopus")
}

@Serializable
data class MediaInfo(
    val streams: List<StreamInfo> = emptyList(),
    val format: FormatInfo
)

@Serializable
data class StreamInfo(
    val index: Int,
    @SerialName("codec_name")
    val codecName: String,
    @SerialName("codec_long_name")
    val codecLongName: String? = null,
    @SerialName("codec_type")
    val codecType: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("r_frame_rate")
    val frameRate: String? = null,
    val duration: String? = null,
    @SerialName("bit_rate")
    val bitRate: String? = null,
    @SerialName("sample_rate")
    val sampleRate: String? = null,
    val channels: Int? = null,
    @SerialName("avg_frame_rate")
    val avgFrameRate: String? = null,
    @SerialName("nb_frames")
    val nbFrames: String? = null,
)

@Serializable
data class FormatInfo(
    val filename: String,
    @SerialName("nb_streams")
    val nbStreams: Int,
    @SerialName("format_name")
    val formatName: String,
    @SerialName("format_long_name")
    val formatLongName: String? = null,
    val duration: String,
    val size: String,
    @SerialName("bit_rate")
    val bitRate: String,
    val formatInfo: String = ""
)

/**
 * Прогресс конвертации из key=value формата
 */
data class ConversionProgress(
    val frame: Int = 0,
    val fps: Float = 0f,
    val outTimeMs: Long = 0L,
    val totalSize: Long = 0L,
    val bitrate: Float = 0f,
    val speed: Float = 0f,
    val progress: String = "continue" // continue / end
) {
    val outTimeSeconds: Double
        get() = outTimeMs / 1_000_000.0

    fun formatTime(): String {
        val seconds = outTimeSeconds
        val hours = (seconds / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()
        return "%02d:%02d:%02d".format(hours, minutes, secs)
    }

    fun formatSize(): String {
        return when {
            totalSize < 1024 -> "$totalSize B"
            totalSize < 1024 * 1024 -> "${totalSize / 1024} KB"
            totalSize < 1024 * 1024 * 1024 -> "${totalSize / (1024 * 1024)} MB"
            else -> "${totalSize / (1024 * 1024 * 1024)} GB"
        }
    }
}
