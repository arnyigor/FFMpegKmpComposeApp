package com.arny.ffmpegcompose

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

expect fun showNotification(message: String)

expect fun getPlatformName(): String

@OptIn(ExperimentalTime::class)
 fun getCurrentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
fun getCurrentDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

fun formatDateTime(dateTime: LocalDateTime): String {
    return "${dateTime.hour.toString().padStart(2, '0')}:" +
            "${dateTime.minute.toString().padStart(2, '0')}:" +
            dateTime.second.toString().padStart(2, '0')
}

expect fun generateId(): String
