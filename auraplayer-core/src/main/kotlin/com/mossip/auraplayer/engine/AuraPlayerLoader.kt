package com.mossip.auraplayer.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AuraPlayerLoader {
    private var isLoaded = false
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "auraplayer_cache")
    fun load() {
        if (isLoaded) return

        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch")

        val (folder, extension) = when {
            os.contains("win") -> "windows-x64" to ".dll"
            os.contains("mac") -> (if (arch == "aarch64") "macos-arm64" else "macos-x64") to ".dylib"
            else -> "linux-x64" to ".so"
        }

        try {
            if (!tempDir.exists()) tempDir.mkdirs()

            // 1. Register the Cleanup Hook once
            registerCleanupHook()

            // 2. Load JAWT
            System.loadLibrary("jawt")

            // 3. Load MPV and Renderer
            val mpvName = if (os.contains("win")) "libmpv-2" else "libmpv"
            extractAndLoad("/nativelibs/$folder/$mpvName$extension")
            extractAndLoad("/nativelibs/$folder/native_render$extension")

            isLoaded = true
        } catch (e: Exception) {
            throw RuntimeException("AuraPlayer: Loading failed", e)
        }
    }

    private fun extractAndLoad(resourcePath: String) {
        val fileName = resourcePath.substringAfterLast("/")
        val targetFile = File(tempDir, fileName)

        javaClass.getResourceAsStream(resourcePath)?.use { input ->
            Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        targetFile.setExecutable(true)
        System.load(targetFile.absolutePath)
    }

    private fun registerCleanupHook() {
        Runtime.getRuntime().addShutdownHook(Thread {
            tempDir.listFiles()?.forEach { it.delete() }
        })
    }
}