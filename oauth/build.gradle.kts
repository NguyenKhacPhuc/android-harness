// :oauth — OAuth 2.0 Authorization Code + PKCE client for the substrate.
//
// The MCP client takes a static bearer token; for any connector that
// requires per-user OAuth (Linear, Gmail, GitHub, Notion, …) the substrate
// needs to:
//   1. Open the provider's authorize URL in a Custom Tab,
//   2. Receive the OAuth redirect back into the app (deep link),
//   3. Exchange the auth code for an access + refresh token,
//   4. Persist tokens (KeyVault) and refresh them when the access token
//      expires.
//
// This module ships the protocol + storage layer. The UI half — settings
// screens, "Connect Linear" buttons, status indicators — belongs in the
// app since it's UX-shaped, not protocol-shaped.
//
// Why Android-only (for now): Custom Tabs is `androidx.browser.customtabs`,
// which is Android-specific. The OAuth protocol logic itself is pure JVM
// and can be lifted to common code once iOS lands.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Group inherited from root (`dev.weft`); override artifact name so
// composite-build consumers can resolve us as `dev.weft:weft-oauth`.
base { archivesName.set("weft-oauth") }

android {
    namespace = "dev.weft.oauth"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
    // KeyVault lives in :contracts (interface) — :os-bridge owns the impl.
    api(project(":contracts"))
    implementation(project(":security"))

    // Custom Tabs for the authorize step. Provides the trust-cookie-share
    // benefits of running OAuth in the user's existing browser session.
    api(libs.androidx.browser)

    api(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.android)
}
