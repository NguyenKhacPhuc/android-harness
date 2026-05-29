// :harness:prompt — prompt-shaping primitives.
//
// Pure functions + data types that build the LLM-facing prompt without
// holding any agent state:
//
//   - `assembleSystemPrompt` — static system prompt assembly (app
//      preamble + tool catalog + UI component catalog + trailing notes).
//   - `CacheBinder` + provider impls — translate provider-agnostic
//      CacheTier markers (STATIC / SESSION / VOLATILE) into Koog's
//      cache_control directives. Anthropic/Bedrock get real markers;
//      OpenAI/Ollama get a no-op binder.
//   - `WeftUserInput` + `Attachments` factories — the user-input data
//      type that carries text + multimodal attachments.
//   - `buildUserParts` — converts WeftUserInput into Koog
//      List<MessagePart.RequestPart> for emission into a user message.
//   - `composeEffectiveText` — assembles the live user message text
//      from volatile prefix + memory hits + the user's own text.
//
// KMP-published — every file is KMP-clean (zero java.* imports), and
// Koog's iOS klibs resolve cleanly at Kotlin 2.3.10. The migration
// is just a plugin swap + source-set move.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-prompt") }

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
            api(libs.koog.agents)
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
    namespace = "dev.weft.harness.prompt"
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
