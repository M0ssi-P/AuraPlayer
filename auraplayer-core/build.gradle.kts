val nativeSrcDir = File(projectDir, "src/main/c")
val nativeResDir = File(projectDir, "src/main/resources/nativelibs")
val isCI = System.getenv("GITHUB_ACTIONS") == "true"

plugins {
    `maven-publish`
    kotlin("jvm")
    id("java-library")
}

val auraVersion = project.properties["aura_version"] as String

group = "auraplayer-core"
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

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.javadoc)
}

publishing {
    repositories {
        maven {
            name = "Sonatype"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("SONATYPE_USERNAME")
                password = System.getenv("SONATYPE_PASSWORD")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "io.github.m0ssi-p"
            artifactId = "auraplayer-core"
            version = auraVersion
            artifact(sourcesJar)
            artifact(javadocJar)

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

    onlyIf { !isCI }

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
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    if (!isCI) {
        dependsOn(compileNative)
        dependsOn(copyMpvBinaries)
    }
}

tasks.withType<Jar> {
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    if (!isCI) {
        dependsOn(compileNative)
        dependsOn(copyMpvBinaries)
    }

//    from("src/main/resources") {
//        include("nativelibs/**")
//    }
}


tasks.test {
    useJUnitPlatform()
}