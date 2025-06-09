plugins {
    kotlin("jvm") version "2.2.0-RC"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    testImplementation(kotlin("test"))

    val osName = System.getProperty("os.name")
    val targetOs = when {
        osName == "Mac OS X" -> "macos"
        osName.startsWith("Win") -> "windows"
        osName.startsWith("Linux") -> "linux"
        else -> error("Unsupported OS: $osName")
    }

    val osArch = System.getProperty("os.arch")
    val targetArch = when (osArch) {
        "x86_64", "amd64" -> "x64"
        "aarch64" -> "arm64"
        else -> error("Unsupported arch: $osArch")
    }

    val version = "0.8.9" // or any more recent version
    val target = "${targetOs}-${targetArch}"

    implementation(kotlin("reflect"))
    implementation("org.jetbrains.skiko:skiko-awt-runtime-$target:$version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(22)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}