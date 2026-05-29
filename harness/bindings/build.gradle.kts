// :harness:bindings — pure evaluator for the agent's data-binding DSL.
//
// Holds two pieces that the Compose layer needs to evaluate
// `${'$'}exec` / `${'$'}binding` sentinels embedded in agent-emitted
// `ComponentNode` props:
//
//   - `BindingEvaluator` — resolves `{"${'$'}binding": ...}` JSON to a
//     concrete value (number / list / formatted string) by querying a
//     `DataSourceRegistry`. Pure data over JSON; no Compose, no platform.
//   - `ActionExecutor` — runs the `{"${'$'}exec": ...}` JSON shape
//     against a `DataSourceRegistry` (data_upsert / data_delete) without
//     a round-trip through the LLM. Pure data over JSON; no Compose,
//     no platform.
//
// Lifted out of `:harness:prompt` so the `:compose` layer (and
// any future iOS-Compose / web layer) can depend on this WITHOUT
// pulling Koog transitively. Same package as before
// (`dev.weft.harness.prompt.bindings.*`) to avoid breaking existing
// imports — only the Gradle coordinate changes for consumers.
//
// Actually no — the package moves too. `dev.weft.harness.bindings.*`
// is more honest: bindings stand on their own and have nothing to do
// with prompt-shaping. Consumers update one import per file.
//
// KMP-published: jvm + androidTarget + iosArm64 + iosSimulatorArm64.
// kotlinx-datetime replaces the JVM-only `java.util.Calendar` /
// `TimeZone` calls in `BindingEvaluator`.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-bindings") }

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

    sourceSets {
        commonMain.dependencies {
            // DataSource + DataSourceRegistry + SortSpec / SortOrder —
            // the registry-query surface the evaluator operates on.
            api(project(":contracts"))
            // kotlinx-datetime — KMP-friendly replacement for the
            // java.util.Calendar usage in todayBoundary / startOfWeek /
            // startOfMonth. Public ABI not affected (millis-in, millis-out).
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

android {
    namespace = "dev.weft.harness.bindings"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
