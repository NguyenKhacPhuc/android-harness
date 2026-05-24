// Root build. Each module configures itself; this file holds only top-level plugin declarations and shared conventions.

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
}

allprojects {
    group = "dev.weft"
    version = "0.0.1-SNAPSHOT"
}

// Detekt across the whole repo
detekt {
    parallel = true
    buildUponDefaultConfig = true
    autoCorrect = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

// AGP 8.7.3 lint has a JDK 25 incompatibility (intellij messagebus dispose).
// Disable lint-vital release tasks repo-wide until AGP catches up.
// Track upstream: https://issuetracker.google.com/issues?q=lint%20JDK%2025
subprojects {
    tasks.matching { it.name == "lintVitalRelease" || it.name == "lintVitalAnalyzeRelease" }
        .configureEach { enabled = false }
}

