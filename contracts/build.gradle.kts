// Pure-Kotlin interface module — no Compose, no Koin, no agent code.
// Now KMP-published so iOS consumers (undercurrent's :composeApp/iosMain)
// can reference the same `WeftCredentialProvider` / `KeyVault` / `Permission` /
// `OsCapabilities` / etc. types as JVM + Android consumers, without going
// through mirror types.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// Coordinate: dev.weft:weft-contracts. The included-build substitution in
// downstream composite builds (undercurrent) resolves by Maven coordinate
// not project path, so archivesName must stay stable.
base { archivesName.set("weft-contracts") }

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
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "dev.weft.contracts"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
