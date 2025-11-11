package com.arny.ffmpegcompose.di


import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val desktopDataModule = module {
    single { Dispatchers.IO }
}

val desktopLlmModule = module {
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true // может помочь с JSON, содержащим непредсказуемые поля
                })
            }
        }
    }
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

val desktopModules = listOf(desktopDataModule, desktopLlmModule)