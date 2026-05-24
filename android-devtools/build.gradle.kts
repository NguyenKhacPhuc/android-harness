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

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Same `:foo:android-suffix` cycle guard as the other Android libraries —
// keeps Maven coords distinct from any app's own `:android` module.
group = "dev.weft.devtools"
base { archivesName.set("weft-android-devtools") }

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

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    explicitApi()
}

dependencies {
    // Weft runtime — devtools reads systemPrompt, traceStore, tools list,
    // usageStore. Reaches across module boundaries on purpose; this is
    // debug surface, not production code.
    implementation(project(":android"))
    implementation(project(":tools"))
    implementation(project(":contracts"))
    implementation(project(":harness:observability"))
    implementation(project(":harness:cost"))

    // Compose UI — bottom sheet, tabs, lists.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.kotlinx.serialization.json)
}
