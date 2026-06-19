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
    // 0.0.2 ships WeftAgent.ask() (#36). GitHub Packages versions are
    // immutable, so each published change needs a fresh version here —
    // republishing the same version 409s.
    version = (findProperty("weftVersion") as String?) ?: "0.0.2"
}

// Lint baseline — ALWAYS applied (independent of publishing). CI gates only
// NEW lint errors. Regenerate with `./gradlew updateLintBaseline`.
subprojects {
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint { baseline = file("lint-baseline.xml") }
        }
    }
}

// Publishing — every KMP module is published to GitHub Packages so host apps
// (undercurrent) can consume `dev.weft:weft-<module>` artifacts. ONLY applied
// when actually publishing (`-PweftVersion`). Applying maven-publish
// unconditionally makes KMP resolve inter-module deps by GAV, and the
// artifactId rewrite (`security` -> `weft-security`) then breaks composite-build
// consumers (undercurrent local dev) whose auto-substitution can't match the
// renamed coordinate. So gate it — composite consumers use plain project deps.
if (providers.gradleProperty("weftVersion").isPresent) {
    subprojects {
        pluginManager.withPlugin("com.android.library") {
            apply(plugin = "maven-publish")

            // devtools keeps its own group to match the consumer coordinate
            // `dev.weft.devtools:weft-devtools`; every other module is `dev.weft`.
            if (name == "devtools") group = "dev.weft.devtools"

            extensions.configure<KotlinMultiplatformExtension> {
                androidTarget { publishLibraryVariants("release") }
            }

            // Only declare the remote repo when credentials exist — Gradle rejects
            // a maven repo with a null username. publishToMavenLocal is unaffected.
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

// Gradle 9 fails a JVM test task that has sources but discovers no tests.
// Across these KMP modules the kotest suites run via jvmTest; other JVM test
// tasks (e.g. androidUnitTest) compile the shared specs but legitimately run
// zero. Don't fail on that.
subprojects {
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests = false
    }
}

