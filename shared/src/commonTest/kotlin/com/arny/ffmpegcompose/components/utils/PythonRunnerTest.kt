package com.arny.ffmpegcompose.components.utils

import com.arny.ffmpegcompose.runPythonScript
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

const val script = "G:\\Android\\OpenideProjects\\AutoDubbing\\dubber_gpu_tts.py"

class PythonRunnerTest {
    @Test
    fun `tts dubbing success`() = runBlocking {
        val args =
            listOf("f:\\Install\\Learning\\UnrealEngine\\Unreal Engine 5 Realistic Automotive Rendering Masterclass\\1. Introduction\\output_wav.wav")
        val result = runPythonScript(
            scriptPath = script,
            args = listOf<String>("--llm-model-name=qwen/qwen3-14b") //+ args
        )
        println("EXIT: ${result.exitCode}")
        println("STDOUT: '${result.stdout.take(500)}...'")
        println("STDERR: '${result.stderr.take(500)}...'")

        assertEquals(0, result.exitCode, "TTS должен завершиться успешно")
        assertTrue("Готово!" in result.stdout, "Ожидаем сообщение успеха")
        assertTrue("final_dubbed.wav" in result.stdout, "Файл дубляжа создан")
    }
}