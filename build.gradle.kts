import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    // 2.16.0+ 才支持 IntelliJ Platform 262 的新模块描述格式（$legacy_jps_module namespace）
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("com.diffplug.spotless") version "7.2.1"
}

group = "com.gto.datasynclib"
version = "26.10.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPlatformPath = providers.gradleProperty("localPlatformPath")
        if (localPlatformPath.isPresent) {
            local(localPlatformPath.get())
        } else {
            intellijIdea("2026.1.1") {
                useInstaller = false
            }
        }
        bundledPlugin("com.intellij.java")
        providers.gradleProperty("localRuntimePath").orNull?.let {
            jetbrainsRuntimeLocal(it)
        }
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint().setEditorConfigPath("$rootDir/spotless.ktlint")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

if (providers.gradleProperty("localRuntimePath").isPresent) {
    // 2.10.x also adds a downloadable runtime when a local runtime is configured.
    configurations.named("jetbrainsRuntime") {
        setExtendsFrom(listOf(configurations.getByName("jetbrainsRuntimeLocalInstance")))
    }
}

intellijPlatform {
    // No custom settings UI to index; packaging does not need to launch an IDE.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            // 2026.2 平台 (build 262)；本机 SDK 通过 localPlatformPath 指向 IDEA 2026.2.1
            sinceBuild.set("262")
        }
    }

    signing {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
