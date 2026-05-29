// :harness:testing — fixtures and helpers for unit-testing Weft tools,
// capabilities, and stores. Originally JVM-only (test-fixture scope).
// Now KMP-published so iOS hosts can use [FakeOsCapabilities] as their
// production stub when composing an OsCapabilities surface — most iOS
// hosts need only a few capabilities (location, keyVault, clipboard);
// the rest can stay as `Fake*` no-ops until an iOS-native impl lands.
//
// What's in scope:
//   • FakeOsCapabilities — every capability replaced with a recording/
//     programmable stub. Production iOS hosts override individual
//     subsystems (e.g. CLLocationManager-backed `Location`) and let
//     the rest default to no-ops.
//   • FakeUiBridge — captures pending askUser / confirmDestructive /
//     showInfo and lets tests answer programmatically.
//   • toolTestContext(...) — convenience builder for a WeftContext that
//     wires the fakes + an in-memory ScriptStorage.
//   • CaptureBridge (jvmMain) — file-backed dump tooling for trace
//     capture; stays JVM because `java.io.File` is JVM-only.
//
// What's deliberately deferred:
//   • MockPromptExecutor — full end-to-end agent mock requires implementing
//     Koog's PromptExecutor contract, which is heavier than v1 needs.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-testing") }

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    explicitApi()

    sourceSets {
        commonMain.dependencies {
            api(project(":contracts"))
            api(project(":tools"))
            // Pulled in for FakeWeftLLM — a scripted PromptExecutor
            // suitable for hermetic agent-loop tests + replaying captured
            // wire dumps as fixtures. KMP-published by Koog.
            api(libs.koog.agents)
            // Reader for WireDumper captures (the JSON-lines fixture format).
            api(project(":harness:observability"))
            // Apps usually pair tests with kotlinx.coroutines.test; we don't
            // force it but expose enough hooks (FakeUiBridge.answer, etc.)
            // that runTest works.
            api(libs.kotlinx.coroutines.core)
            // kotlinx.atomicfu — KMP replacement for
            // java.util.concurrent.atomic primitives the fakes used.
            implementation(libs.kotlinx.atomicfu)
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.kotest.runner.junit5)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "dev.weft.harness.testing"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
