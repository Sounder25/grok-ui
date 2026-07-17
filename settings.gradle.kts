pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Declaring versions here (rather than in the root build.gradle.kts `plugins {}`
    // block) means they're only resolved when a project actually applies the plugin by
    // ID, not the moment the root project itself is configured. That in turn means
    // `:core` (a plain Kotlin/JVM module, see core/build.gradle.kts) can be built and
    // tested on its own without ever touching AGP or needing the Android SDK/NDK/network
    // access AGP resolution requires -- only `:app` pays that cost, and only when you
    // actually build `:app`.
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "1.9.24"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OfflineReader"
include(":app", ":core")
