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
        val signingKey = findProperty("signingInMemoryKey") as String?
        val signingPassword = findProperty("signingInMemoryKeyPassword") as String?

        if (signingKey != null) {
            println("DEBUG: GPG Key length: ${signingKey.length}")
            println("DEBUG: GPG Key starts with: ${signingKey.take(20)}")
        }
        println("DEBUG: GPG Password present: ${!signingPassword.isNullOrEmpty()}")


        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications)
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