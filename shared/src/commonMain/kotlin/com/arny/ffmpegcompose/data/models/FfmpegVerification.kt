package com.arny.ffmpegcompose.data.models

import java.nio.file.Path

data class FfmpegVerification(
    val ffmpegPath: Path,
    val ffprobeExists: Boolean,
    val version: String?
)