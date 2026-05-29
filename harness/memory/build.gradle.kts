// :harness:memory — MemoryStore + memory tools.
//
// Pure Kotlin + Koog (no SQLDelight here; the SQL-backed impl lives in
// :runtime). Single java.util.UUID call swapped for
// kotlin.uuid.Uuid so the module goes KMP.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-memory") }

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
            api(project(":contracts"))
            implementation(project(":tools"))
            implementation(libs.kotlinx.datetime)
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
    namespace = "dev.weft.harness.memory"
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
