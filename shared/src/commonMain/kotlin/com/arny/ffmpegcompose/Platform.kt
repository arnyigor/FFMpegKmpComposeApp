package com.arny.ffmpegcompose

import com.arny.ffmpegcompose.util.ProcessResult

expect fun showNotification(message: String)

expect fun getPlatformName(): String

expect fun runPythonScript(
    executable: String,
    scriptPath: String,
    args: List<String>
): ProcessResult

expect fun generateId(): String
