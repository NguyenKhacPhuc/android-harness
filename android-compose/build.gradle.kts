// :android-compose — Compose layer for the substrate's UI protocol.
//
// The contract layer (`:contracts`) defines what the substrate's UI half
// is in framework-agnostic terms: `UiBridge`, `ComponentMetadata`,
// `ComponentNode`, `UIUpdate`, `TreeValidationResult`. This module
// provides the Compose framework apps subclass when they want to plug
// into the substrate's UI protocol:
//
//   • `WeftComponent<TProps>` — the abstract base every renderable
//     component extends. Hosts `propsSerializer` + `decode(JsonObject)` so
//     the bridge can validate prop shapes before render.
//   • `WeftComponentRegistry` — concrete `ComponentRegistry` impl that
//     keeps the `decode` typing the bridge needs.
//   • `ComposeUiBridge` — Compose-state-backed `UiBridge` impl. Exposes
//     `pending`, `lastUpdate`, `currentOverlay` for app surfaces to consume.
//   • `TreeRenderer` — turns a `ComponentNode` tree into Compose.
//
// Now KMP-published (jvm + androidTarget + iosArm64 + iosSimulatorArm64).
// Sources moved from `src/main/kotlin/` to `src/commonMain/kotlin/` —
// every type the module exposes was already Compose-only (no
// `android.*`, no `androidx.compose.ui.platform.*`), so the move is
// mechanical. The Compose runtime + foundation come from Compose
// Multiplatform; consumers on Android still get the Android-Compose
// variants of those artifacts via the KMP targets.
//
// Deliberately NOT in this module:
//   • Material 3 — TreeRenderer's error placeholder uses foundation-only
//     `BasicText` so the framework stays palette-agnostic.
//   • The default M3 palette (Text / Button / Column / …) — lives in
//     `:android-compose-defaults`. That module stays Android-only for
//     now (its WebView component pulls `android.webkit.WebView`).
//   • Default surfaces (`PendingRequestRenderer`, `WeftOverlayHost`,
//     `AgentRenderedTreePanel`, `AgentRenderedTreeScreen`) — also in
//     `-defaults`, because they're M3-flavored too.
//
// Apps with a different UI framework (SwiftUI, web, headless tests)
// don't depend on this module either — they implement `UiBridge` + their
// own renderer directly against the `:contracts` interfaces.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

// Unique Maven coordinate — same trick as :android, to keep Gradle from
// confusing this with the consuming app on resources tasks. Composite-
// build consumers resolve us as `dev.weft:weft-android-compose`.
group = "dev.weft"
base { archivesName.set("weft-android-compose") }

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
            // Contract layer — defines the UiBridge / ComponentMetadata / UIUpdate
            // surface this module implements.
            api(project(":contracts"))

            // BindingEvaluator — KMP-published resolver for `$binding`
            // sentinels embedded in agent-emitted ComponentNode props.
            // Used by `BindingAwareRenderer` to replace bindings with
            // live values before component decode + render.
            api(project(":harness:bindings"))

            // Compose Multiplatform runtime + foundation (no Material 3).
            // The framework needs @Composable, Modifier, Column/padding (for
            // error placeholder), and BasicText. Everything else (M3
            // surfaces, palette components) is in `:android-compose-defaults`.
            api(libs.compose.multiplatform.runtime)
            api(libs.compose.multiplatform.foundation)
            api(libs.compose.multiplatform.ui)

            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "dev.weft.compose"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
