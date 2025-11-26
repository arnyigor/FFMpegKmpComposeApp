package com.arny.ffmpegcompose.components.utils

import com.arny.ffmpegcompose.runPythonScript

class PythonRunner {

    suspend fun analyzeData() {
        val result = runPythonScript(
            executable = "python3",          // или полный путь, если нужен конкретный интерпретатор
            scriptPath = "/opt/scripts/score.py",
            args = listOf("--input", "/tmp/data.csv")
        )

        if (result.exitCode == 0) {
            println("Python output:\n${result.stdout}")
        } else {
            System.err.println("Python error (${result.exitCode}):\n${result.stderr}")
        }
    }
}