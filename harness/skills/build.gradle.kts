// Skills — user-typed input the app handles directly, bypassing the LLM.
// Slash-commands like `/note buy milk`, `/help`, app-defined shortcuts.
//
// Pure Kotlin, zero dependencies (not even :contracts). Lives in :harness/
// because skills are a substrate concept — apps register them once, the
// chat surface dispatches them — but they have no Android-specific bits.
//
// Contents:
//   - `Skill` — a single named handler with `name`, `aliases`, `description`,
//      `usage`, and an `execute(payload)` lambda.
//   - `SkillResult` — sealed type for `Ok(text)` / `Fail(text)` returns.
//   - `SkillRegistry` — lookup-by-name + match-by-input helper.
//   - `withHelp()` — convenience extension that adds a built-in `/help` skill
//      enumerating the rest of the registry.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

base { archivesName.set("weft-harness-skills") }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
