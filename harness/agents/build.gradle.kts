// The agent core — WeftAgent, sub-agents, model routing, cache binders,
// multimodal handling, streaming. Pure-JVM, no Android dependencies.
// Apps using the substrate consume this transitively via `:android`'s
// `api(project(":harness:agents"))` declaration.
//
// Lives here (instead of inside `:android`) so:
//   - Tests can exercise WeftAgent / SubAgentRunner without an emulator.
//   - A future iOS / desktop port can reuse the agent logic against
//     non-Android stores + tools.
//   - The dependency boundary "agent logic vs Android wiring" is enforced
//     by the build, not just by package naming.
//
// Android-specific composition root + persistence stays in `:android`:
// WeftRuntime, SqlDelight* stores, AndroidOsCapabilities, etc.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

base { archivesName.set("weft-harness-agents") }

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
    // Shared interfaces (ContextProvider, MemoryProvider, etc.).
    api(project(":contracts"))
    // WeftTool base + tool context — sub-agents and delegate tools build on these.
    api(project(":tools"))

    // Cross-cutting harness modules WeftAgent uses directly. `api` exposure
    // because callers constructing a WeftAgent need to import these types
    // (TraceStore, UsageStore, QuotaPolicy, …) to wire it up.
    api(project(":harness:behavior"))
    api(project(":harness:conversation"))
    api(project(":harness:cost"))
    api(project(":harness:memory"))
    api(project(":harness:observability"))
    api(project(":harness:reliability"))
    // Prompt-shaping primitives (assembleSystemPrompt, CacheBinder,
    // WeftUserInput, buildUserParts, composeEffectiveText). The agent
    // module consumes them; downstream consumers (`:android`) also need
    // them when building the runtime so we surface as api().
    api(project(":harness:prompt"))

    // Koog (LLM client + strategy graph). `api` because WeftAgent's
    // constructor surfaces Koog types directly.
    api(libs.koog.agents)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
