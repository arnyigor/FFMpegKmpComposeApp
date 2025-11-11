import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    // Desktop таргет
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Compose
                api(libs.compose.runtime)
                api(libs.compose.foundation)
                api(libs.compose.material3)
                api(libs.compose.ui)
                api(libs.compose.material.icons.extended)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                api(libs.compose.components.resources)
                api("com.arkivanov.decompose:decompose:${libs.versions.decompose.get()}")
                api("com.arkivanov.decompose:extensions-compose:${libs.versions.decompose.get()}")
                api("com.arkivanov.essenty:lifecycle-coroutines:${libs.versions.lifecycle.coroutines.get()}")
                api("androidx.room:room-runtime:${libs.versions.room.get()}")
                api("io.insert-koin:koin-compose:${libs.versions.koin.get()}")
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                api(project.dependencies.platform(libs.ktor.bom))
                api(libs.ktor.client.core)
                api(libs.ktor.client.content.negotiation)
                api(libs.ktor.client.logging)
                api(libs.ktor.serialization.kotlinx.json)
                api(libs.sqlite.bundled)
                api(libs.koin.core)
                implementation(libs.compose.markdown.render.m3)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
            }
        }
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    dependencies {
        add("kspDesktop", libs.room.compiler)
    }
}
