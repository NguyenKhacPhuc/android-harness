// :harness:reliability — retry policy + circuit breaker.
//
// Pure coroutines + kotlin.time. No Koog, no JVM-only types — KMP-clean
// since inception, just hadn't gotten the multiplatform plugin yet.
//
// KMP-published (jvm + androidTarget + iosArm64 + iosSimulatorArm64) so
// iosMain consumers can pull RetryPolicy / CircuitBreaker / withRetry
// without going through a mirror or porting copies.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

base { archivesName.set("weft-harness-reliability") }

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
        }
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

android {
    namespace = "dev.weft.harness.reliability"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
