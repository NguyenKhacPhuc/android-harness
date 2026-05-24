// :substrate:android-compose — framework-only Compose layer.
//
// The contract layer (`:contracts`) defines what the substrate's UI half
// is in framework-agnostic terms: `UiBridge`, `ComponentMetadata`,
// `ComponentNode`, `UIUpdate`, `TreeValidationResult`. This module provides
// the Android Compose framework apps subclass when they want to plug into
// the substrate's UI protocol:
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
// Deliberately NOT in this module:
//   • Material 3 — TreeRenderer's error placeholder uses foundation-only
//     `BasicText` so the framework stays palette-agnostic.
//   • The default M3 palette (Text / Button / Column / …) — lives in
//     `:substrate:android-compose-defaults`. Apps that want plug-and-play
//     UI add that module too; apps with a fully custom palette skip it
//     and assemble their own registry.
//   • Default surfaces (`PendingRequestRenderer`, `WeftOverlayHost`,
//     `AgentRenderedTreePanel`, `AgentRenderedTreeScreen`) — also in
//     `-defaults`, because they're M3-flavored too.
//
// Apps with a different UI framework (SwiftUI, web, headless tests) don't
// depend on this module either — they implement `UiBridge` + their own
// renderer directly against the `:contracts` interfaces.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Unique Maven coordinate — same trick as :substrate:android, to keep
// Gradle from confusing this with the consuming app on resources tasks.
// Same `:foo:android-suffix` cycle guard as :substrate:android. Composite-
// build consumers resolve us as `dev.weft:weft-android-compose`.
group = "dev.weft"
base { archivesName.set("weft-android-compose") }

android {
    namespace = "dev.weft.compose"
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
    // Contract layer — defines the UiBridge / ComponentMetadata / UIUpdate
    // surface this module implements.
    api(project(":contracts"))

    // BindingEvaluator — pure-JVM resolver for `$binding` sentinels
    // embedded in agent-emitted ComponentNode props. Used by
    // `BindingAwareRenderer` to replace bindings with live values
    // before component decode + render. Same JVM target (17), so
    // the inline reified helpers are safe to inline here.
    api(project(":harness:prompt"))

    // Compose runtime + foundation (no Material 3). The framework needs
    // @Composable, Modifier, Column/padding (for error placeholder), and
    // BasicText. Everything else (M3 surfaces, palette components) is in
    // `:substrate:android-compose-defaults`.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)

    implementation(libs.kotlinx.coroutines.android)
}
