// :harness:observability — Redactor + TraceStore + WireDumper.
//
// commonMain houses Redactor + Trace data + TraceStore (pure Kotlin
// once UUID + atomic + System.currentTimeMillis are swapped for KMP
// equivalents). jvmCommonMain houses the WireDumper / DumpSink pair
// because they're dev-only HTTP-wire dumpers that write to local
// files via java.io — iOS gets nothing for those (the wire-dump
// surface is opt-in and Android-developer-only anyway).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-observability") }

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
        // Shared intermediate so jvm and androidTarget can host the
        // wiredump piece (java.io.File + java.util.concurrent.atomic).
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }

        commonMain.dependencies {
            api(project(":contracts"))
            api(libs.koog.agents)
            implementation(libs.kotlinx.atomicfu)
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
    namespace = "dev.weft.harness.observability"
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
