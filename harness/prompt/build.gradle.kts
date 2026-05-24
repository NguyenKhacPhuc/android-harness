// Prompt-shaping primitives — pure functions and data types that build
// the LLM-facing prompt without holding any agent state.
//
// Contents:
//   - `assembleSystemPrompt` — static system prompt assembly (app preamble +
//      tool catalog + UI component catalog + trailing notes).
//   - `CacheBinder` + provider impls — translate provider-agnostic
//      [CacheTier] markers (STATIC / SESSION / VOLATILE) into Koog's
//      `cache_control` directives. Anthropic/Bedrock get real markers;
//      OpenAI/Ollama get a no-op binder.
//   - `WeftUserInput` + `Attachments` factories — the user-input data type
//      that carries text + multimodal attachments.
//   - `buildUserParts` — converts [WeftUserInput] into Koog
//      `List<MessagePart.RequestPart>` for emission into a user message.
//   - `composeEffectiveText` — assembles the live user message text from
//      volatile prefix + memory hits + the user's own text.
//
// Lives separately from `:harness:agents` so:
//   - Apps that want to format prompts outside the agent loop (custom
//     LLM calls, test fixtures, dev tooling) can use it standalone.
//   - The boundary "prompt shape vs agent execution" is enforced by the
//     build, not by package naming.
//   - Future cache tier additions or new modality support land here
//     without touching the agent module.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-prompt") }

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
    // Shared interfaces (MemoryHit, ComponentMetadata).
    api(project(":contracts"))
    // WeftTool — needed by `assembleSystemPrompt` to iterate the catalog.
    api(project(":tools"))

    // Koog — `Prompt`, `MessagePart`, `CacheControl`, `AnthropicCacheControl`,
    // `ToolDescriptor` all surface in this module's public API.
    api(libs.koog.agents)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
