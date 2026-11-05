pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "NeuralKeyboard"

// The prediction engine is plain Kotlin and builds anywhere.
include(":core")

// The Android app needs an Android SDK. Skip it (with a warning) when none is
// configured so that `gradle :core:test` still works on machines without one.
val localProps = file("local.properties")
val sdkConfigured = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))

if (sdkConfigured) {
    include(":app")
} else {
    logger.warn("Android SDK not found: building :core only. Set ANDROID_HOME or sdk.dir in local.properties to build :app.")
}
