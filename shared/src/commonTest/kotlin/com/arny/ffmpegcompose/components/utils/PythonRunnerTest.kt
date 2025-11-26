package com.arny.ffmpegcompose.components.utils

import com.arny.ffmpegcompose.runPythonScript
import kotlin.test.Test
import kotlin.test.assertEquals

class PythonRunnerTest {
    @Test
    fun `simple echo script`() {
        // Предположим, что в корне проекта есть файл echo.py:
        //   import sys; print(' '.join(sys.argv[1:]))
        val result = runPythonScript(
            executable = "python3",
            scriptPath = "G:\\Android\\OpenideProjects\\AutoDubbing\\auto_dubbing.py",
            args = listOf("Hello", "world")
        )
        assertEquals(0, result.exitCode)
        assertEquals("Hello world", result.stdout.trim())
        assertEquals("", result.stderr)
    }
}