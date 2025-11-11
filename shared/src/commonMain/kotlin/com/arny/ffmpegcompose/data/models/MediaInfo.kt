package com.arny.ffmpegcompose.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

    val percentage: Double
        get() = 0.0 // Требует знания общей длительности

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
