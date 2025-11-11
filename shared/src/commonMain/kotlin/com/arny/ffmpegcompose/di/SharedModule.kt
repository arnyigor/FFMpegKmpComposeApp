package com.arny.ffmpegcompose.di

import com.arny.ffmpegcompose.data.FFmpegExecutor
import com.arny.ffmpegcompose.data.config.ConfigManager
import com.arny.ffmpegcompose.data.models.FFmpegManager
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val dataModule = module {

    // JSON
    single {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
        }
    }

    // Config Manager
    single { ConfigManager() }

    // FFmpeg Manager
    single { FFmpegManager() }

    single {
        FFmpegExecutor(configManager = get(), json = get())
    }
}

val commonModules = listOf(dataModule)