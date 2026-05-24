// :substrate:android-compose-defaults — Material 3 palette + default surfaces.
//
// The framework module (`:substrate:android-compose`) ships the Compose
// abstraction layer apps subclass to add components: `WeftComponent`,
// the registry, the bridge, the renderer. It deliberately knows nothing
// about Material 3.
//
// This module bolts on the substrate's *default* implementations of every
// app-facing surface so an app can be plug-and-play without rebuilding
// the palette from scratch:
//
//   • Tier-1 / Tier-2 / Tier-3 / Macro component palette under
//     `components/` (TextComponent, ButtonComponent, ColumnComponent,
//     CardComponent, ImageComponent, WebViewComponent, Tabs, BottomSheet,
//     Form, Wizard, Countdown, …). Roughly 30 components. Apps extend or
//     replace via the `extraComponents` parameter on `WeftUi`.
//   • Default surfaces — `PendingRequestRenderer` (M3 dialogs for
//     `askUser` / `confirmDestructive` / `showInfo`),
//     `WeftOverlayHost` (toast + banner), `AgentRenderedTreePanel`
//     and `AgentRenderedTreeScreen` (host the LLM's `ui_render` tree).
//   • `WeftUi` convenience holder — bundles the default Coil
//     image loader, the default palette + any extras, and the registry.
//   • `ImageLoading.buildWeftImageLoader` — the default Coil setup.
//
// Apps that want a custom palette omit this module entirely and assemble
// their own `WeftComponentRegistry` from custom components against
// the framework module. They lose the M3 surfaces too — apps in that
// position are usually building their own design language anyway.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Same `:foo:android-suffix` cycle guard as :substrate:android. Composite-
// build consumers resolve us as `dev.weft:weft-android-compose-defaults`.
group = "dev.weft"
base { archivesName.set("weft-android-compose-defaults") }

android {
    namespace = "dev.weft.compose.defaults"
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
    // Framework module — exposes WeftComponent base, registry,
    // ComposeUiBridge, TreeRenderer. Re-exported so apps don't have to
    // depend on both modules just to register custom components.
    api(project(":android-compose"))

    // Material 3 — the entire raison d'être of this module. Default
    // surfaces render M3 dialogs / snackbars / sheets; the palette wraps
    // M3 components.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)

    // Coil — only the ImageComponent uses it. If we ever split Image out,
    // this dep moves with it.
    api(libs.coil.compose)
}
