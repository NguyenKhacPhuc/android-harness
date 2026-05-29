pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "weft"

// ----- Contracts (interfaces shared across layers) -----
include(":contracts")

// ----- Capabilities (OS bridges, security, tools) -----
include(":os-bridge")
include(":security")
include(":tools")
// Model Context Protocol client — exposes external MCP servers' tools as
// WeftTools at runtime. Optional; apps that don't configure MCP
// servers don't pay for the network surface.
include(":mcp")
// OAuth 2.0 + PKCE client. Used by apps that need to connect MCP servers
// or other services requiring per-user OAuth (Linear, Gmail, GitHub, …).
include(":oauth")

// ----- Harness (cross-cutting concerns wrapping the agent) -----
include(":harness:reliability")
include(":harness:observability")
include(":harness:cost")
include(":harness:behavior")
include(":harness:memory")
include(":harness:conversation")
// Prompt-shaping primitives (assembleSystemPrompt, CacheBinder, WeftUserInput,
// buildUserParts, composeEffectiveText). Pure functions + data types, no agent
// state. `:harness:agents` depends on this; standalone tooling can too.
include(":harness:prompt")
// Bindings — pure evaluator for $exec / $binding sentinels in
// agent-emitted ComponentNode props. Lifted out of :harness:prompt so
// the Compose layer can use them without taking a Koog-poisoned dep.
// KMP-published; consumers run on Android + iOS.
include(":harness:bindings")
// Skills — app-handled slash-command primitives (Skill, SkillResult,
// SkillRegistry, withHelp). Pure Kotlin, no Android deps.
include(":harness:skills")
// Agent core (WeftAgent, sub-agents, routing, streaming strategy).
// Pure-JVM, no Android deps; `:runtime` provides the composition root + the
// Android-specific persistence / OS bridge / credentials wiring on top.
include(":harness:agents")
// Testing fixtures — not a production dep. Apps use this via
// `testImplementation(project(":harness:testing"))`.
include(":harness:testing")

// ----- Weft runtime (what consumer apps depend on) -----
// Core (agent + persistence + streaming + skills + OS-capability tools)
// is Compose-free. The Compose layer is split in two so apps can opt out
// of the default Material 3 palette without rebuilding from scratch:
//
//   • `:compose`           — framework: WeftComponent abstract base,
//                            registry, ComposeUiBridge, TreeRenderer.
//                            Apps subclassing components depend on this.
//                            No M3, no Coil.
//   • `:compose-defaults`  — default M3 palette + default surfaces +
//                            WeftUi helper. Apps that want plug-and-play
//                            UI depend on this; custom-palette apps don't.
//
// Apps with a non-Compose UI depend on `:runtime` only.
//
// Module names were `:android` / `:android-compose` / `:android-compose-defaults`
// before the KMP migration; they shipped jvm + Android only at the time.
// Now that every module publishes jvm + androidTarget + iosArm64 +
// iosSimulatorArm64 the Android-claiming prefix was misleading, so the
// paths dropped it. `:os-bridge` already didn't claim a platform; its
// implementations stay androidMain-only but the contracts they
// implement live in cross-platform `:contracts`.
include(":runtime")
include(":compose")
include(":compose-defaults")

// ----- Developer tooling -----
// Opt-in debug overlay for inspecting a live WeftRuntime. Apps add
// `implementation(project(":devtools"))` only in debug builds.
include(":devtools")

// The Undercurrent reference app lives in a sibling git repo
// (`../undercurrent`) and pulls this SDK in via composite build
// (`includeBuild("../weft")` in its own settings). This repo is SDK-only.
