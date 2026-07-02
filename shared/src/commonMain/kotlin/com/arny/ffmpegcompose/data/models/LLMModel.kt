package com.arny.ffmpegcompose.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LLMModel(
    val key: String,
    @SerialName("display_name") val displayName: String? = null,
    val type: String,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("last_modified") val lastModified: String? = null
)
