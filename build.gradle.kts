// Plugins live on the root classpath so the Kotlin and Android plugins share a
// classloader. The Android Gradle Plugin is only fetched when :app is part of
// the build (i.e. an Android SDK is configured), so :core can build anywhere.
buildscript {
    repositories {
        mavenCentral()
        if (rootProject.findProject(":app") != null) google()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (rootProject.findProject(":app") != null) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
