// Root build. Each module configures itself; this file holds only top-level plugin declarations and shared conventions.

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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
    // Fixed release version by default (GitHub Packages handles SNAPSHOTs poorly).
    // Bump per published change, or override with -PweftVersion=x.y.z in CI.
    version = (findProperty("weftVersion") as String?) ?: "0.0.1"
}

// Publishing — every KMP module is published to GitHub Packages so host apps
// (undercurrent) can consume `dev.weft:weft-<module>` artifacts instead of a
// composite `includeBuild`. The artifactId is rewritten from the project name
// (`runtime`, `harness:agents`) to the consumed coordinate (`weft-runtime`,
// `weft-harness-agents`).
subprojects {
    pluginManager.withPlugin("com.android.library") {
        apply(plugin = "maven-publish")

        // devtools keeps its own group to match the consumer coordinate
        // `dev.weft.devtools:weft-devtools`; every other module is `dev.weft`.
        if (name == "devtools") group = "dev.weft.devtools"

        extensions.configure<KotlinMultiplatformExtension> {
            androidTarget { publishLibraryVariants("release") }
        }

        // Only declare the remote repo when credentials exist — Gradle rejects a
        // maven repo with a null username, which would break non-publish builds
        // and IDE sync. publishToMavenLocal is unaffected (separate repo).
        val gprUser = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
        val gprKey = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
        if (gprUser != null && gprKey != null) {
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/NguyenKhacPhuc/android-harness")
                        credentials {
                            username = gprUser
                            password = gprKey
                        }
                    }
                }
            }
        }

        // Rewrite artifactIds AFTER the KMP plugin finalizes them, so the
        // platform publications (…-jvm, …-android, …-iosarm64) get prefixed too.
        val publishName = "weft-" + path.removePrefix(":").replace(":", "-")
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    if (!artifactId.startsWith("weft-")) {
                        artifactId = artifactId.replaceFirst(project.name, publishName)
                    }
                }
            }
        }
    }
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

