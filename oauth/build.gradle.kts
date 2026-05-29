// :oauth — OAuth 2.0 Authorization Code + PKCE client for the substrate.
//
// commonMain houses the protocol layer: PKCE helpers, OAuthTypes,
// OAuthTokenStore. androidMain keeps the Custom Tabs flow launcher
// and the deep-link callback channel (both depend on
// `androidx.browser.customtabs` + `android.net.Uri`). iosMain ships
// nothing yet — host writes its own launcher against
// `ASWebAuthenticationSession` when it wants OAuth on iOS.
//
// PKCE crypto goes through small `expect/actual` helpers:
//   - `oauthSha256(bytes)` — SHA-256 digest. JVM uses MessageDigest;
//     iOS uses CommonCrypto's CC_SHA256.
//   - `oauthSecureRandom(size)` — high-entropy random bytes. JVM uses
//     java.security.SecureRandom; iOS uses Security.SecRandomCopyBytes.
//
// Base64 url-safe encoding uses `kotlin.io.encoding.Base64.UrlSafe`
// (stable in Kotlin 1.9+).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// Composite-build consumers resolve us as `dev.weft:weft-oauth`.
base { archivesName.set("weft-oauth") }

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
        // Shared between jvm + androidTarget for the
        // java.security.MessageDigest + SecureRandom-backed actuals.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }

        commonMain.dependencies {
            api(project(":contracts"))
            implementation(project(":security"))
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        jvmMain { dependsOn(jvmCommonMain) }
        androidMain {
            dependsOn(jvmCommonMain)
            dependencies {
                api(libs.androidx.browser)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.android)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

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

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
