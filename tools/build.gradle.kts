// :tools — WeftTool catalog. Each tool wraps Koog's ToolDescriptor and
// implements its action against the OsCapabilities interface (in
// :contracts); concrete platform impls of those capabilities are wired
// by the app at runtime via :os-bridge. This module therefore does
// not depend on :os-bridge.
//
// KMP-published since Koog 1.0.0 ships iosArm64 + iosSimulatorArm64
// + iosX64 artifacts. 47 of 50 source files are KMP-clean (use Koog
// + :contracts + kotlinx-datetime only). The three holdouts —
// TextTransformTool, UtilityTools, NetworkFetchTool — got `java.*`
// imports swapped for `kotlin.io.encoding.Base64` + custom percent-
// encoding helpers + an `expect/actual` `computeDigest` so the whole
// module compiles against iOS.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-tools") }

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
        // Intermediate source set so jvm and androidTarget share the
        // `computeDigest` actual that wraps java.security.MessageDigest
        // — both platforms have it, no need to duplicate the impl.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }

        commonMain.dependencies {
            api(project(":contracts"))
            api(libs.koog.agents)
            implementation(project(":security"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        jvmMain { dependsOn(jvmCommonMain) }
        androidMain { dependsOn(jvmCommonMain) }
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
    namespace = "dev.weft.tools"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// JUnit Platform — Kotest's runner-junit5 lives there.
tasks.named<Test>("jvmTest") { useJUnitPlatform() }
