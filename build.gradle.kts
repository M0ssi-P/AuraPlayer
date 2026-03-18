plugins {
    kotlin("jvm")
    id("java-library")
    id("maven-publish")
    id("signing")
    java
}

group = "com.auraplayer"
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
        val signingKey = System.getenv("GPG_SIGNING_KEY")
        val signingPassword = System.getenv("GPG_PASSWORD")

        if (!signingKey.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(extensions.getByType<PublishingExtension>().publications)
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