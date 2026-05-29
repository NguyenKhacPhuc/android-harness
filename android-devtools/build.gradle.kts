// :android-devtools — opt-in debug panel for inspecting a live WeftRuntime.
//
// Apps wrap their root composable with `WeftDevTools(runtime = ...) { App() }`
// in debug builds. A floating action button opens a bottom-sheet with tabs:
//   • Live      — recent tool calls from the TraceStore
//   • Prompt    — assembled system prompt with section breakdown
//   • Tools     — every registered tool (catalog) + click into Playground
//   • Playground — invoke any tool manually with JSON args
//   • Cost      — today / lifetime / per-conversation usage totals
//
// Strictly opt-in. Apps that don't add this dep don't ship the debug code.
//
// **KMP status — partial.** This module is now KMP-published with empty
// iOS targets. The sources stay in androidMain because the devtools panel
// reads from a `WeftRuntime` (Context-bound, androidMain-only), and the
// UI uses Android-only Compose helpers (`LocalClipboardManager`,
// `SimpleDateFormat`). A future iOS port would need to: (a) wait for the
// WeftRuntime composition root to lift into commonMain, and (b) swap
// the Compose Android-isms for Compose Multiplatform equivalents.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

// Same `:foo:android-suffix` cycle guard as the other Android libraries —
// keeps Maven coords distinct from any app's own `:android` module.
group = "dev.weft.devtools"
base { archivesName.set("weft-android-devtools") }

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    explicitApi()

    sourceSets {
        androidMain.dependencies {
            // Weft runtime — devtools reads systemPrompt, traceStore, tools
            // list, usageStore. Reaches across module boundaries on purpose;
            // this is debug surface, not production code.
            implementation(project(":android"))
            implementation(project(":tools"))
            implementation(project(":contracts"))
            implementation(project(":harness:observability"))
            implementation(project(":harness:cost"))

            // Compose UI — bottom sheet, tabs, lists. We use the
            // Compose Multiplatform artifacts (not the Android BOM)
            // because the KMP source-set DSL no longer allows
            // `platform(...)` inside `dependencies { }` blocks. On the
            // androidTarget these resolve to the same androidx.compose
            // bits at runtime, so the Android-only helpers
            // (LocalClipboardManager, …) the panel uses still link.
            implementation(libs.compose.multiplatform.runtime)
            implementation(libs.compose.multiplatform.foundation)
            implementation(libs.compose.multiplatform.ui)
            implementation(libs.compose.multiplatform.material3)
            implementation(libs.compose.multiplatform.material.icons.core)

            implementation(libs.kotlinx.serialization.json)
        }
    }
}

android {
    namespace = "dev.weft.devtools"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
