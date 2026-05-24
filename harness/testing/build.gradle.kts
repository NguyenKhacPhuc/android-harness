// :harness:testing — fixtures and helpers for unit-testing Weft tools,
// capabilities, and stores. NOT a production dep — apps reach for this
// only in `testImplementation`.
//
// What's in scope (v1):
//   • FakeOsCapabilities — every capability replaced with a recording/
//     programmable stub. No Android dependency; runs on the JVM.
//   • FakeUiBridge — captures pending askUser / confirmDestructive /
//     showInfo and lets tests answer programmatically.
//   • toolTestContext(...) — convenience builder for a WeftContext that
//     wires the fakes + an in-memory ScriptStorage.
//
// What's deliberately deferred to v2:
//   • MockPromptExecutor — full end-to-end agent mock requires implementing
//     Koog's PromptExecutor contract, which is heavier than v1 needs.
//     Tests today validate individual tools + stores; the agent loop's
//     glue layer is thin enough that tool-level tests cover most behavior.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-testing") }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    explicitApi()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":contracts"))
    api(project(":tools"))
    // Apps usually pair tests with kotlinx.coroutines.test; we don't force it
    // but expose enough hooks (FakeUiBridge.answer, etc.) that runTest works.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
