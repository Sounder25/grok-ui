// Plain Kotlin/JVM module: the sentence chunker, text sanitizer, and WAV encoder have no
// Android framework dependency, so they live here instead of in :app. That means they can
// be built and unit-tested (`./gradlew :core:test`) on any machine with a JDK and network
// access to Maven Central -- no Android SDK/NDK required -- which is exactly what let this
// logic be verified in a sandbox that had neither.
//
// The Kotlin Gradle plugin is applied via the legacy buildscript-classpath mechanism
// rather than the `plugins { id(...) }` DSL: the latter resolves through the Gradle Plugin
// Portal's CDN (plugins-artifacts.gradle.org), which is blocked by some restrictive
// network policies (including the one this module was originally verified under) even
// when Maven Central itself is reachable. This form only needs Maven Central.
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    }
}

apply(plugin = "org.jetbrains.kotlin.jvm")

// Module dependency resolution (as opposed to the buildscript classpath above) comes
// from the root settings.gradle.kts `dependencyResolutionManagement` block, which is set
// to FAIL_ON_PROJECT_REPOS -- so no `repositories {}` block belongs here.

dependencies {
    "testImplementation"("org.jetbrains.kotlin:kotlin-test:1.9.24")
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
