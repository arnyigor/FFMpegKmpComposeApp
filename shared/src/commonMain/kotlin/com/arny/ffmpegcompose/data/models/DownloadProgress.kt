package com.arny.ffmpegcompose.data.models

data class DownloadProgress(
    val phase: DownloadPhase,
    val percent: Int
)