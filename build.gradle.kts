// Top-level build file. Per-plugin versions for :app (AGP, Kotlin Android) live in
// settings.gradle.kts's pluginManagement block instead of a `plugins {}` block here --
// see the comment there for why that matters for building :core standalone.

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
