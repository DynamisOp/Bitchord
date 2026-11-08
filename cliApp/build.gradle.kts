plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

val javacppVersion = "1.5.12"
val ffmpegVersion = "7.1.1-$javacppVersion"

dependencies {
    implementation(project(":shared"))

    // Stream extraction (NewPipe and JS engine)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

    // Decoding and audio playback
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:linux-x86_64")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:linux-x86_64")

    // Terminal handling & JLine 3
    implementation("org.jline:jline:3.28.0")

    // Coroutines & JSON
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.music.bitchord.cli.CliAppKt")
    applicationName = "bitchord-cli"
    applicationDefaultJvmArgs = listOf(
        "-Xms16m",
        "-Xmx192m",
        "-XX:+UseG1GC",
        "-XX:G1PeriodicGCInterval=5000",
        "-XX:G1PeriodicGCSystemLoadThreshold=0",
        "-XX:+UseStringDeduplication",
    )
}
