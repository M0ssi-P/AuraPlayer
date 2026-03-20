plugins {
    `maven-publish`
    kotlin("jvm")
    id("java-library")
    kotlin("plugin.serialization")
    java
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "io.github.m0ssi-p"
val auraVersion = project.properties["aura_version"] as String
version = auraVersion

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

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    repositories {
        maven {
            name = "Sonatype"
            url = uri("https://central.sonatype.com/api/v1/publisher/upload")
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
            artifactId = "auraplayer-compose"
            version = auraVersion

            pom {
                name.set("AuraPlayer Compose")
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

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}