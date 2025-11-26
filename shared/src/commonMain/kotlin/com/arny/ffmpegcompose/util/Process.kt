package com.arny.ffmpegcompose.util

/**
 * Инкапсулирует результат выполнения процесса.
 *
 * @property exitCode Код возврата из программы.
 * @property stdout   Стандартный вывод (stdout).
 * @property stderr   Ошибки/стандартный поток ошибок (stderr).
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)
