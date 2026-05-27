// :substrate:android — the SDK that consumer apps depend on.
//
// Contains everything app-agnostic that an Android app needs to host the
// substrate: the WeftRuntime composition root, the WeftAgent
// (multi-turn wrapper around Koog's AIAgent), the Android-specific device
// snapshot, and a set of Compose building blocks (ComposeUiBridge,
// PendingRequestRenderer, default Trace and Memory viewers).
//
// App authors `implementation(project(":android"))` and write
// their own ChatScreen + theming + navigation against the substrate's
// state flows. They can use the default Trace / Memory screens or replace
// them.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// One database for the whole substrate. Schemas in src/main/sqldelight/.
// Each store gets its own .sq file but they all compile into `WeftDatabase`.
//
// Dialect: SQLite 3.25 — opts into `ON CONFLICT(...) DO UPDATE` (3.24+) and
// window functions (3.25+). AndroidSqliteDriver ships bundled SQLite, so the
// dialect target is decoupled from the device's system SQLite version.
//
// Migrations: `.sqm` files live next to the `.sq` files. The workflow + rules
// are documented in `src/main/sqldelight/dev/mas/substrate/android/db/MIGRATIONS.md`.
// Schema snapshots get written to `src/main/sqldelight/databases/` when you run
// `./gradlew generateWeftDatabaseSchema`. Check those snapshots in alongside
// each new `.sqm` so `verifyMigrations` can prove the migration leaves the
// schema in the shape the latest `.sq` defines.
sqldelight {
    databases {
        create("WeftDatabase") {
            packageName.set("dev.weft.android.db")
            dialect("app.cash.sqldelight:sqlite-3-25-dialect:${libs.versions.sqldelight.get()}")
            // Schema snapshots used by the verifyMigrations check below. The
            // task `generateWeftDatabaseSchema` writes a snapshot of the
            // current schema as a versioned `.db` file here; check those in.
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            // verifyMigrations runs each `.sqm` against the previous schema
            // snapshot and asserts the result matches the next snapshot.
            // Enabled once we shipped 1.sqm (FTS5 upgrade) + 1.db baseline
            // + 2.db post-migration snapshot. Any future `.sqm` that
            // doesn't leave the DB in the shape the `.sq` files define
            // will fail the build.
            verifyMigrations.set(true)
        }
    }
}

// Module path is `:substrate:android` and the Undercurrent app is at
// `:apps:undercurrent:android` — both end in "android". Without a unique
// Maven coordinate Gradle treats them as producing the same group:name
// and reports a self-referential dep cycle on processDebugResources.
// Both `:substrate:android` and (historically) `:apps:undercurrent:android`
// end in "android". With identical `group:name` AGP's resource processing
// detects a self-referential cycle. The `substrate` group qualifier keeps
// them distinct as long as both are in the same build; once the app moves
// out via composite build the discriminator stops mattering but doesn't
// hurt. Composite-build consumers resolve us as
// `dev.weft:weft-android`.
group = "dev.weft"
base { archivesName.set("weft-android") }

android {
    namespace = "dev.weft.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // No Compose here — that lives in `:substrate:android-ui`. This module
    // is the agent + persistence + streaming core, Android-coupled (it uses
    // Context and the bundled SQLite) but not Compose-coupled.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    explicitApi()
}

dependencies {
    // Weft layers
    api(project(":contracts"))
    api(project(":tools"))
    api(project(":os-bridge"))
    api(project(":security"))
    api(project(":harness:reliability"))
    api(project(":harness:observability"))
    api(project(":harness:cost"))
    api(project(":harness:behavior"))
    api(project(":harness:memory"))
    api(project(":harness:conversation"))
    // Agent core (WeftAgent, sub-agents, routing, multimodal, cache,
    // streaming). Pure-JVM module hosting all LLM-orchestration code;
    // :android keeps the composition root (WeftRuntime), persistence
    // (SqlDelight*), credentials (Android Keystore), and OS-bridge wiring.
    api(project(":harness:agents"))
    // Skills — app-handled slash-command primitives. Surfaced as `api`
    // because consumer apps reference Skill / SkillRegistry / withHelp
    // directly when wiring their chat surface.
    api(project(":harness:skills"))

    // Optional MCP support — the suspend `createWithMcpServers` factory lives
    // here. Apps that don't configure MCP servers still pay for the small
    // type surface, but the network code path stays cold.
    api(project(":mcp"))

    api(libs.koog.agents)
    // Extra Koog provider client not bundled with koog-agents. DeepSeek
    // rides on OpenAILLMClient (api.deepseek.com) since the dedicated
    // Koog DeepSeek client isn't published at 1.0.0.
    api(libs.koog.prompt.executor.openrouter.client)

    implementation(libs.kotlinx.coroutines.android)
    // OkHttp engine for the substrate's whitelisting HTTP client used by network_fetch.
    implementation(libs.ktor.client.okhttp)
    // Force okhttp-sse onto the same major as the resolved core OkHttp
    // (Koog drags 5.3.2 in; ktor would otherwise leave okhttp-sse at 4.12.0
    // and we get a NoClassDefFoundError at the first streaming call).
    implementation(libs.okhttp.sse)

    // SQLDelight — persistence backend for MemoryStore, TraceStore, ScriptStorage,
    // ScheduledNotificationStore, conversation history, UsageStore.
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines.extensions)

    // Bundled SQLite — guarantees FTS5 + modern features regardless of device.
    // Wired via SupportSQLiteOpenHelper.Factory in WeftDatabaseFactory.
    implementation(libs.osmerion.sqlite.android)

    // JDBC SQLite driver for unit tests — lets migration tests run on the
    // JVM without needing an Android instrumentation context. Same schema
    // bytes; same FTS5 support (the bundled sqlite-jdbc enables FTS5).
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
    // Fake OsCapabilities + UiBridge + weftToolContext() — used by
    // prompt-scoping tests that need to construct a real WeftTool
    // without bringing up an Android Context.
    testImplementation(project(":harness:testing"))
}
