plugins {
    `maven-publish`
    kotlin("jvm")
    id("java-library")
    kotlin("plugin.serialization")
    java
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "auraplayer-compose"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://packages.jetbrains.team/maven/p/kpm/public")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs) {
        exclude(compose.material)
    }
    api(project(":auraplayer-core"))
}

kotlin {
    jvmToolchain(21)
}

val jdkLevel = "21"

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(jdkLevel)
    }
}
val auraVersion = project.properties["aura_version"] as String

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.mossip"
            artifactId = "auraplayer-compose"
            version = auraVersion
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}