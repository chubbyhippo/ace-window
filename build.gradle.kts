import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "io.github.chubbyhippo"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.4")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    minHeapSize = "2g"
    maxHeapSize = "2g"
    jvmArgs("-Djava.awt.headless=true")
}

ktlint {
    version = "1.8.0"
}

detekt {
    buildUponDefaultConfig = true
}

tasks.withType<Detekt>().configureEach {
    jdkHome.set(
        javaToolchains
            .launcherFor { languageVersion = JavaLanguageVersion.of(21) }
            .map { it.metadata.installationPath },
    )
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        extraWarnings = true
    }
}
