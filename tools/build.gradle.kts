// scripts-core implements the substrate's script catalog. Scripts call OS
// functionality via the OsCapabilities interface (defined in :contracts);
// concrete platform implementations are wired in by the app at runtime from
// :os-bridge. This module therefore does not depend on :os-bridge.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Unify under the weft-* artifact namespace so composite-build consumers
// can resolve every SDK module by `dev.weft:weft-<name>` coordinates.
base { archivesName.set("weft-tools") }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":contracts"))
    api(libs.koog.agents)
    implementation(project(":security"))
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
