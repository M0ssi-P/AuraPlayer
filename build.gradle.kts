plugins {
    kotlin("jvm")
    id("java-library")
    id("maven-publish")
    id("signing")
    java
}

group = "io.github.m0ssi-p"
version = project.properties["aura_version"] as String

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://packages.jetbrains.team/maven/p/kpm/public")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    signing {
        val signingKey = System.getenv("ORG_GRADLE_PROJECT_signingKey")
        val signingPassword = System.getenv("ORG_GRADLE_PROJECT_signingPassword")

        if (signingKey != null) {
            println("DEBUG: GPG Key length: ${signingKey.length}")
            println("DEBUG: GPG Key starts with: ${signingKey.take(20)}")
        }
        println("DEBUG: GPG Password present: ${!signingPassword.isNullOrEmpty()}")


        if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            signing {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(publishing.publications)
            }
            println("DEBUG: Signing configured for ${project.name}")
        } else {
            println("DEBUG: Signing SKIPPED for ${project.name} - Key present: ${!signingKey.isNullOrBlank()}, Pwd present: ${!signingPassword.isNullOrBlank()}")
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

val jdkLevel = "21"

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(jdkLevel)
    }
}

tasks.test {
    useJUnitPlatform()
}