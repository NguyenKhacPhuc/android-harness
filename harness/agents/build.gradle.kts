// :harness:agents — WeftAgent, sub-agents, model routing, multimodal
// handling, streaming.
//
// KMP-published now that every transitive (:contracts, :tools, the
// :harness:* utility chain, Koog) ships iOS klibs at Kotlin 2.3.10
// ABI. Source uses only `java.util.UUID` + one `AtomicInteger` —
// both swap to `kotlin.uuid.Uuid` + `kotlinx.atomicfu.atomic`.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-agents") }

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
            api(project(":tools"))
            api(project(":harness:behavior"))
            api(project(":harness:conversation"))
            api(project(":harness:cost"))
            api(project(":harness:memory"))
            api(project(":harness:observability"))
            api(project(":harness:reliability"))
            api(project(":harness:prompt"))
            api(libs.koog.agents)
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
    namespace = "dev.weft.harness.agents"
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
