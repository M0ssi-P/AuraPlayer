import com.vanniktech.maven.publish.SonatypeHost

val nativeSrcDir = File(projectDir, "src/main/c")
val nativeResDir = File(projectDir, "src/main/resources/nativelibs")
val isCI = System.getenv("GITHUB_ACTIONS") == "true"

plugins {
    `maven-publish`
    id("com.vanniktech.maven.publish")
    kotlin("jvm")
    id("java-library")
}

group = "io.github.m0ssi-p"
val auraVersion = project.properties["aura_version"] as String
version = auraVersion

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

sourceSets {
    main {
        resources {
            srcDir("src/main/resources")
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = group as String,
        artifactId = "auraplayer-core",
        version = version as String
    )

    pom {
        name.set("AuraPlayer Core")
        description.set("High-performance JNI/libmpv video engine for Kotlin/Compose")
        url.set("https://github.com/M0ssi-P/AuraPlayer")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("M0ssi-P")
                name.set("Pacifique Mossi")
            }
        }
        scm {
            connection.set("scm:git:github.com/M0ssi-P/AuraPlayer.git")
            url.set("https://github.com/M0ssi-P/AuraPlayer")
        }
    }

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
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
        println(outputFile.parentFile)
        outputFile.parentFile.mkdirs()
    }

    val args = when {
        os.isWindows -> listOf(
            "gcc", "-shared", "-fPIC",
            "-o", outputFile.absolutePath,
            "${nativeSrcDir.absolutePath}/jawt_win.c",
            "${nativeSrcDir.absolutePath}/native_render.c",
            "-I", "C:/deps/mpv-sdk/include",
            "-I", "$jdkHome/include",
            "-I", "$jdkHome/include/win32",
            "C:/deps/mpv-sdk/libmpv.dll.a",
            "$jdkHome/lib/jawt.lib",
            "-lgdi32", "-luser32"
        )

        os.isMacOsX -> listOf(
            "clang", "-dynamiclib", "-fPIC",
            "-fobjc-arc",
            "-o", outputFile.absolutePath,
            "${nativeSrcDir.absolutePath}/native_render.c",
            "${nativeSrcDir.absolutePath}/jawt_macos.m",
            "-I", "/Users/user1/Downloads/libmpv-macos/include",
            "-I", "$jdkHome/include",
            "-I", "$jdkHome/include/darwin",
            "-L", "/Users/user1/Downloads/libmpv-macos",  // <-- directory
            "-lmpv",
            "-L", "$jdkHome/lib",
            "-ljawt",
            "-framework", "AppKit",
            "-framework", "QuartzCore",
            "-framework", "Foundation",
            "-framework", "OpenGL",
            "-framework", "CoreGraphics",
            "-framework", "CoreVideo",
            "-framework", "Metal",
        )

        else -> listOf(
            "gcc", "-shared", "-fPIC",
            "-o", outputFile.absolutePath,
            "${nativeSrcDir.absolutePath}/native_render.c",
            "-I", "/usr/include",
            "-I", "$jdkHome/include",
            "-I", "$jdkHome/include/linux",
            "-L$jdkHome/lib", "-ljawt", "-lmpv"
        )
    }

    commandLine(args)
}

val copyMpvBinaries by tasks.registering(Copy::class) {
    description = "Copies the MPV DLL from the SDK to the resources folder."
    group = "build"

    onlyIf { !isCI }

    val os = org.gradle.internal.os.OperatingSystem.current()

    val filePath = when {
        os.isWindows -> "C:/deps/mpv-sdk/libmpv-2.dll"
        os.isMacOsX -> "/Users/user1/Downloads/libmpv-macos/libmpv.dylib"
        else -> "C:/deps/mpv-sdk/libmpv-2.dll"
    }

    val sourceFile = file(filePath)

    doFirst {
        if (!sourceFile.exists()) {
            throw GradleException("FATAL: Could not find libmpv at ${sourceFile.absolutePath}. Check your C:/deps/ folder!")
        }
        println("AuraPlayer: Copying engine from ${sourceFile.absolutePath}...")
    }

    from(sourceFile)
    into(File(nativeResDir, getCurrentPlatform()))
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
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    if (!isCI) {
        dependsOn(compileNative)
        dependsOn(copyMpvBinaries)
    }
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    if (!isCI) {
        dependsOn(compileNative)
        dependsOn(copyMpvBinaries)
    }

    from("src/main/resources") {
        include("nativelibs/**")
    }
}


tasks.test {
    useJUnitPlatform()
}