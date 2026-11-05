import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.neuralkeyboard.ime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neuralkeyboard.ime"
        minSdk = 24
        targetSdk = 35
        // Every CI run gets a higher versionCode, so a new APK installs as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    signingConfigs {
        // Fixed key committed to the repo so every build (local or CI) has the same
        // signature and can update the installed app without uninstalling it.
        // Test builds only: use a private key kept out of git for Play Store releases.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}
