package com.arny.ffmpegcompose

import com.arny.ffmpegcompose.util.ProcessResult

expect fun showNotification(message: String)

expect fun getPlatformName(): String

expect suspend fun runPythonScript(
    scriptPath: String,
    args: List<String>
): ProcessResult

expect fun generateId(): String
