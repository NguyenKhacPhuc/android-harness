// :compose-defaults — Material 3 palette + default surfaces.
//
// The framework module (`:compose`) ships the Compose abstraction layer
// apps subclass to add components: `WeftComponent`, the registry, the
// bridge, the renderer. It knows nothing about Material 3.
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
//   • `WeftUi` convenience holder — bundles the default Coil 3 image
//     loader, the default palette + any extras, and the registry.
//   • `ImageLoading.buildWeftImageLoader` — the default Coil 3 setup.
//
// Now KMP-published (jvm + androidTarget + iosArm64 + iosSimulatorArm64).
// Everything that's pure Compose Multiplatform (Material 3 + foundation +
// icons + Coil 3) lives in commonMain. Only the WebView-backed components
// (Embed.kt) stay in androidMain — WKWebView wrappers can land later as
// iosMain when somebody needs `WebView` / `Html` on iOS.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

// Unique Maven coordinate — same trick as :runtime / :compose, to keep
// Gradle from confusing this with the consuming app on resources tasks.
// Composite-build consumers resolve us as `dev.weft:weft-compose-defaults`.
group = "dev.weft"
base { archivesName.set("weft-compose-defaults") }

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
            // Framework module — exposes WeftComponent base, registry,
            // ComposeUiBridge, TreeRenderer. Re-exported so apps don't
            // have to depend on both modules just to register custom
            // components.
            api(project(":compose"))

            // Compose Multiplatform — Material 3 + foundation + ui +
            // material-icons-core (the core icon set the default palette
            // pulls: Check, Close, Info, Warning, ArrowBack, …).
            api(libs.compose.multiplatform.runtime)
            api(libs.compose.multiplatform.foundation)
            api(libs.compose.multiplatform.ui)
            api(libs.compose.multiplatform.material3)
            api(libs.compose.multiplatform.material.icons.core)

            // Coil 3 — KMP image loading for the Image component. The
            // network engine is target-specific (OkHttp on Android,
            // Ktor on iOS — see the androidMain / iosMain blocks).
            api(libs.coil.compose)

            // kotlinx-datetime — replaces java.time.Instant / ZoneId in
            // the Input / Macro date components.
            implementation(libs.kotlinx.datetime)

            implementation(libs.kotlinx.coroutines.core)

            // Napier — KMP logger for the WeftBindings diagnostic tag
            // emitted by AgentRenderedTreeScreen on `$exec` action
            // interception. Host opts in via `Napier.base(DebugAntilog())`.
            implementation(libs.napier)
        }
        commonTest.dependencies {
            // Pure-logic tests for the mini-app surfaces (theme, bridge).
            // kotlin.test @Test + kotest matchers — runs on every target;
            // execute via :compose-defaults:jvmTest.
            implementation(kotlin("test"))
            implementation(libs.kotest.assertions.core)
        }
        androidMain.dependencies {
            // Embed.kt's WebViewComponent + HtmlComponent depend on
            // android.webkit.WebView. iOS gets to skip these two
            // components until a WKWebView wrapper lands.
            // Coil 3's OkHttp engine — pairs with the OkHttp Koog
            // already pulls into the Android dep graph.
            implementation(libs.coil.network.okhttp)
        }
        val iosMain by getting {
            dependencies {
                // Coil 3's KMP-native Ktor engine — uses the Darwin
                // engine on iOS for HTTP fetches.
                implementation(libs.coil.network.ktor3)
                implementation(libs.ktor.client.core)
            }
        }
    }
}

android {
    namespace = "dev.weft.compose.defaults"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
