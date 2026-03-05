val nativeSrcDir = File(projectDir, "src/main/c")
val nativeResDir = File(projectDir, "src/main/resources/nativelibs")
val isCI = System.getenv("GITHUB_ACTIONS") == "true"

plugins {
    `maven-publish`
    kotlin("jvm")
    id("java-library")
}

group = "auraplayer-core"
version = "1.0-SNAPSHOT"
val auraVersion = project.properties["aura_version"] as String

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.mossip"
            artifactId = "auraplayer-core"
            version = auraVersion
        }
    }
}

val compileNative by tasks.registering(Exec::class) {
    description = "Compiles the C JNI bridge for the current OS."
    group = "build"

    onlyIf { !isCI }

    val jdkHome = System.getProperty("java.home")
    val os = org.gradle.internal.os.OperatingSystem.current()

    val (outputName, osInclude) = when {
        os.isWindows -> "native_render.dll" to "win32"
        os.isMacOsX -> "native_render.dylib" to "darwin"
        else -> "native_render.so" to "linux"
    }

    val outputFile = File(nativeResDir, "${getCurrentPlatform()}/$outputName")

    doFirst {
        outputFile.parentFile.mkdirs()
    }

    commandLine(
        "gcc", "-shared", "-fPIC",
        "-o", outputFile.absolutePath,
        "${nativeSrcDir.absolutePath}/native_render.c",
        "-I", "C:/deps/mpv-sdk/include",
        "-I", "$jdkHome/include",
        "-I", "$jdkHome/include/$osInclude",
        "C:/deps/mpv-sdk/libmpv.dll.a",
        "$jdkHome/lib/jawt.lib"
    )
}

val copyMpvBinaries by tasks.registering(Copy::class) {
    description = "Copies the MPV DLL from the SDK to the resources folder."
    group = "build"

    val sourceFile = file("C:/deps/mpv-sdk/libmpv-2.dll")

    doFirst {
        if (!sourceFile.exists()) {
            throw GradleException("FATAL: Could not find libmpv-2.dll at ${sourceFile.absolutePath}. Check your C:/deps/ folder!")
        }
        println("AuraPlayer: Copying engine from ${sourceFile.absolutePath}...")
    }

    from(sourceFile)
    into(File(nativeResDir, "windows-x64"))
}

fun getCurrentPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch")
    return when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (arch == "aarch64") "macos-arm64" else "macos-x64"
        else -> "linux-x64"
    }
}

tasks.processResources {
    dependsOn(copyMpvBinaries)
    dependsOn(compileNative)
}

tasks.withType<Jar> {
    dependsOn(compileNative)
    dependsOn(copyMpvBinaries)
    from("src/main/resources") {
        include("nativelibs/**")
    }
}


tasks.test {
    useJUnitPlatform()
}