package com.arny.ffmpegcompose.components.utils

import com.arny.ffmpegcompose.data.models.LLMModel
import com.arny.ffmpegcompose.runPythonScript
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.collections.emptyList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Пути к скриптам могут быть изменены на относительные или через конфигурацию
// Для тестов используем ресурсы или временные пути
private const val DUBBER_SCRIPT_NAME = "dubber_gpu_tts.py"
private const val MODELS_SCRIPT_NAME = "list_lm_models.py"

class PythonRunnerTest {
    @Test
    fun `tts dubbing success`() = runBlocking {
        // Проверяем наличие необходимых ресурсов перед запуском теста
        val testAudioPath = System.getProperty("user.dir") + "/Desktop/Convert/test_audio.wav"
        val ffmpegPath = System.getProperty("user.dir") + "/Desktop/ffmpeg/bin/ffmpeg.exe"
        
        // Если ресурсы недоступны, пропускаем тест
        if (!java.io.File(testAudioPath).exists()) {
            println("Пропускаем тест: тестовый аудиофайл не найден")
            return@runBlocking
        }
        
        if (!java.io.File(ffmpegPath).exists()) {
            println("Пропускаем тест: ffmpeg не найден")
            return@runBlocking
        }
        
        val args = listOf(testAudioPath) +
                listOf("--verbose") +
                listOf("--ffmpeg=$ffmpegPath")
        val result = runPythonScript(
            scriptPath = DUBBER_SCRIPT_NAME,
            args = listOf<String>("--llm=gpt-oss-20b") + args
        )
        println("EXIT: ${result.exitCode}")
        println("STDOUT: '${result.stdout.take(500)}...'")
        println("STDERR: '${result.stderr.take(500)}...'")

        assertEquals(0, result.exitCode, "TTS должен завершиться успешно")
        assertTrue("Всё готово!" in result.stdout || "Готово!" in result.stdout, "Ожидаем сообщение успеха")
        assertTrue(result.stdout.contains(Regex("final_dubbed\\.wav|готовый файл|output.*\\.wav|файл.*создан", RegexOption.IGNORE_CASE)), "Файл дубляжа создан")
    }

    @Test
    fun `list_lm_models returns expected JSON`() = runBlocking {
        // Проверяем наличие скрипта перед запуском теста
        val scriptPath = System.getProperty("user.dir") + "/" + MODELS_SCRIPT_NAME
        
        // Если скрипт недоступен, пропускаем тест
        if (!java.io.File(scriptPath).exists()) {
            println("Пропускаем тест: скрипт $MODELS_SCRIPT_NAME не найден")
            return@runBlocking
        }
        
        // Arrange
        val result = runPythonScript(
            scriptPath = MODELS_SCRIPT_NAME,
            args = listOf<String>("--type=llm")
        )
        println("EXIT: ${result.exitCode}")
        println("STDOUT preview: ${result.stdout.take(200)}…")

        // Act – parse JSON
        val json = Json { ignoreUnknownKeys = true }   // tolerate future fields
        val models: List<LLMModel> = try {
            json.decodeFromString(result.stdout)
        } catch (e: Exception) {
            fail("Failed to parse JSON:\n${result.stdout}\nError: ${e.message}")
        }

        // Assert – exit code & content
        assertEquals(0, result.exitCode, "Script should finish successfully")
        assertTrue(models.isNotEmpty(), "Resulting list must not be empty")

        // Example assertion: at least one model has a non‑empty key
        val containsKey = models.any { it.key.isNotBlank() }
        assertTrue(containsKey, "At least one model must have a non‑blank key")

        // Optional – validate specific keys you expect
        val expectedKeys = setOf(
            "qwen3-next-80b-a3b-instruct",
            "qwen/qwen3-vl-8b",
            "lfm2.5-1.2b-instruct",
            "gpt-oss-20b"
        )
        val actualKeys = models.map { it.key }.toSet()
        assertTrue(expectedKeys.all(actualKeys::contains), "All expected model keys must be present")
    }
}