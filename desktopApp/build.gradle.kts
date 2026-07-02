// desktopApp/build.gradle.kts
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        compilations.all {
            compilerOptions.configure {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.arny.ffmpegcompose.MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,    // macOS
                TargetFormat.Msi,    // Windows MSI-установщик
                TargetFormat.Exe,    // Windows EXE-установщик
                TargetFormat.Deb     // Linux
            )
            packageName = "FFmpegMediaWorkshop"
            packageVersion = "1.1.0"
            description = "Desktop media converter, trimmer and offline Whisper transcription tool"
            vendor = "Arny"
            modules("jdk.accessibility")
        }
    }
}

tasks.withType<Sync>().matching { it.name == "prepareAppResources" }.configureEach {
    from(rootProject.layout.projectDirectory.file("whisper_transcribe.py")) { into("python") }
    from(rootProject.layout.projectDirectory.file("dubber_gpu_tts.py")) { into("python") }
    from(rootProject.layout.projectDirectory.file("requirements-whisper.txt")) { into("python") }
}

tasks.register<Zip>("packagePortable") {
    description = "Builds the complete portable Windows application with bundled Java runtime"
    group = "compose desktop"
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app/FFmpegMediaWorkshop")) {
        into("FFmpegMediaWorkshop")
    }
    archiveFileName.set("FFmpegMediaWorkshop-1.1.0-windows-x64-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/portable"))
}
