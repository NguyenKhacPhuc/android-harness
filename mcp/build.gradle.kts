// :mcp — Model Context Protocol client. Connects to external MCP servers,
// discovers their advertised tools, and exposes them as WeftTools so
// they appear in the system prompt and route through the normal agent
// loop alongside the substrate's built-in capabilities.
//
// Zero `android.*` imports in source — code was always KMP-portable,
// just hadn't gotten the multiplatform plugin. Now KMP-published so
// iOS hosts can connect to MCP servers the same way Android hosts do.
//
// What's in scope for v1:
//   • JSON-RPC over HTTP (POST request → JSON response). SSE deferred —
//     adequate for any server that doesn't initiate messages back.
//   • initialize / tools/list / tools/call. Resources, prompts, sampling
//     are deferred.
//   • Server-name-prefixed tool names so multiple servers can coexist
//     without collisions.
//   • NetworkPolicy gate on every server URL (the URL has to pass the
//     substrate's allowlist the same way `network_fetch` does).
//
// Spec target: 2024-11-05.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-mcp") }

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
            api(project(":contracts"))
            api(project(":tools"))
            implementation(project(":security"))
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
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
    namespace = "dev.weft.mcp"
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
