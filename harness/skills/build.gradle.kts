// Skills — user-typed input the app handles directly, bypassing the LLM.
// Slash-commands like `/note buy milk`, `/help`, app-defined shortcuts.
//
// Pure Kotlin, zero deps (not even :contracts). Now KMP-published so
// commonMain consumers (undercurrent's :composeApp/commonMain via :feature:chat)
// can use Skill / SkillRegistry / SkillResult without going through a mirror.
//
// Contents:
//   - `Skill` — a single named handler with `name`, `aliases`, `description`,
//      `usage`, and an `execute(payload)` lambda.
//   - `SkillResult` — sealed type for `Ok(text)` / `Fail(text)` returns.
//   - `SkillRegistry` — lookup-by-name + match-by-input helper.
//   - `withHelp()` — convenience extension that adds a built-in `/help` skill
//      enumerating the rest of the registry.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

base { archivesName.set("weft-harness-skills") }

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
}

android {
    namespace = "dev.weft.harness.skills"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
