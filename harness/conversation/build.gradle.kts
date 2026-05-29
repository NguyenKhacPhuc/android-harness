// :harness:conversation — ConversationStore interface + in-memory impl.
//
// Pure data + flows. `java.util.UUID` swapped for `kotlin.uuid.Uuid` so
// the module goes KMP. Same `conv-XXXX` / `msg-XXXX` id shapes — only
// the underlying random source changes.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-conversation") }

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
            api(libs.kotlinx.coroutines.core)
            // `synchronized(SynchronizedObject)` for the InMemory store's
            // critical sections — replaces the JVM-only `kotlin.synchronized`.
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
    namespace = "dev.weft.harness.conversation"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
