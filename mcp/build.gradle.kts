// :mcp — Model Context Protocol client. Connects to external MCP servers,
// discovers their advertised tools, and exposes them as WeftTools so
// they appear in the system prompt and route through the normal agent
// loop alongside the substrate's built-in capabilities.
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
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-mcp") }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    explicitApi()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":contracts"))
    // :tools — McpRemoteTool extends WeftTool. api so consumers of
    // :mcp see the WeftTool type without an extra dep.
    api(project(":tools"))
    // :security — for NetworkPolicy + whitelistingHttpClient.
    implementation(project(":security"))

    api(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
