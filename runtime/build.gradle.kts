// :runtime — the SDK that consumer apps depend on.
//
// Contains everything app-agnostic that an Android app needs to host the
// substrate: the WeftRuntime composition root, the Android-specific
// device snapshot, persistence (SQLDelight + Android Keystore), and the
// MCP / Koog plumbing the agent loop needs.
//
// App authors `implementation(project(":runtime"))` and write
// their own ChatScreen + theming + navigation against the substrate's
// state flows.
//
// **KMP status — partial.** This module is now KMP-published. Sources
// today live in androidMain (the composition root is Context-bound, the
// SQLDelight stores use AndroidSqliteDriver, the device snapshot reads
// `android.os.Build`). The iOS targets are deliberately empty —
// undercurrent's iOS host wires its own composition via
// `IosWeftAgentFactory`, mirroring the pattern we use for the
// EmbedComponents palette. Lifting parts of WeftRuntime / the
// SQLDelight stores to commonMain is future work; that's the path to
// sharing the agent-loop wiring across both targets.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// One database for the whole substrate. Schemas in
// src/commonMain/sqldelight/. Each store gets its own .sq file but they
// all compile into `WeftDatabase`.
//
// The .sq files live in commonMain because they're pure SQL with no
// Android coupling — only the driver instantiation
// (`AndroidSqliteDriver`) is Android-bound. SQLDelight 2.x's KMP mode
// auto-picks up `src/commonMain/sqldelight/` and generates classes for
// every target in the hierarchy, so the store implementations sitting
// in androidMain see the same `WeftDatabase` type. iOS hosts that want
// to share these schemas can either depend on this module's commonMain
// directly or rewrite the stores against the native SQLite driver.
//
// Dialect: SQLite 3.25 — opts into `ON CONFLICT(...) DO UPDATE` (3.24+)
// and window functions (3.25+). AndroidSqliteDriver ships bundled
// SQLite, so the dialect target is decoupled from the device's system
// SQLite version.
//
// Migrations: `.sqm` files live next to the `.sq` files. The workflow +
// rules are documented in
// `src/commonMain/sqldelight/dev/weft/android/db/MIGRATIONS.md`. Schema
// snapshots get written to `src/commonMain/sqldelight/databases/` when
// you run `./gradlew generateWeftDatabaseSchema`. Check those snapshots
// in alongside each new `.sqm` so `verifyMigrations` can prove the
// migration leaves the schema in the shape the latest `.sq` defines.
sqldelight {
    databases {
        create("WeftDatabase") {
            packageName.set("dev.weft.android.db")
            dialect("app.cash.sqldelight:sqlite-3-25-dialect:${libs.versions.sqldelight.get()}")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

// Module path was `:substrate:android` historically, where it shared
// the "android" suffix with the consuming app and tripped AGP's
// resource processing into a self-referential dep cycle. After the KMP
// migration the path is just `:runtime`; the archives name follows.
// Composite-build consumers resolve us as `dev.weft:weft-runtime`.
group = "dev.weft"
base { archivesName.set("weft-runtime") }

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()


    androidTarget {
        // We intentionally omit the per-target `compilerOptions { jvmTarget = … }`
        // block here. SQLDelight 2.0.2's Gradle plugin pulls an older
        // Kotlin Gradle Plugin onto the buildscript classpath, and that
        // version doesn't expose the target-level compilerOptions DSL —
        // referencing it makes the build script fail to compile.
        // `jvmToolchain(17)` above handles the toolchain pinning; the
        // android.compileOptions block below covers Java's
        // source/targetCompatibility.
    }
    // iOS targets exist so the module ships KMP klibs alongside its
    // KMP-published peers. The composition root + persistence layer
    // stay androidMain; iOS hosts wire their own composition. If you
    // need to consume something from this module on iOS, lift the
    // relevant chunk into commonMain first.
    iosArm64()
    iosSimulatorArm64()

    explicitApi()

    sourceSets {
        commonMain.dependencies {
            // Contracts + everything WeftRuntime's class body references.
            // WeftRuntime itself now lives in commonMain (Phase 2); only
            // the Android-specific `WeftRuntime.create(...)` convenience
            // factory + `AndroidOsCapabilities` wiring + the OkHttp
            // engine wiring live in androidMain.
            api(project(":contracts"))
            api(project(":tools"))
            api(project(":security"))
            api(project(":harness:reliability"))
            api(project(":harness:behavior"))
            api(project(":harness:cost"))
            api(project(":harness:memory"))
            api(project(":harness:conversation"))
            api(project(":harness:observability"))
            api(project(":harness:agents"))
            api(project(":harness:prompt"))
            // Skills — app-handled slash-command primitives. Surfaced as
            // `api` because consumer apps reference Skill / SkillRegistry
            // / withHelp directly when wiring their chat surface.
            api(project(":harness:skills"))
            // Optional MCP support — the suspend `createWithMcpServers`
            // factory lives here. Apps that don't configure MCP servers
            // still pay for the small type surface, but the network code
            // path stays cold.
            api(project(":mcp"))

            api(libs.koog.agents)
            // Extra Koog provider client not bundled with koog-agents.
            // DeepSeek rides on OpenAILLMClient (api.deepseek.com) since
            // the dedicated Koog DeepSeek client isn't published at 1.0.0.
            api(libs.koog.prompt.executor.openrouter.client)

            // SQLDelight — schema + queries live in commonMain/sqldelight,
            // so the generated `WeftDatabase` type is commonMain. The
            // store classes operate on `WeftDatabase` + the coroutines
            // extensions; the per-platform driver is wired by the
            // `expect fun createWeftDriver` actuals below.
            implementation(libs.sqldelight.coroutines.extensions)

            // kotlinx-datetime + kotlin.time — replaces System.currentTimeMillis()
            // and java.time.LocalDate in the stores.
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)

            // Ktor — WeftRuntime's commonMain class body takes an
            // HttpClient via constructor and threads it into MCP +
            // `network_fetch` plumbing. The engine (OkHttp / Darwin /
            // CIO / …) is wired per-platform in the host factory.
            api(libs.ktor.client.core)
        }

        androidMain.dependencies {
            // Android-only OS bridge — wires `AndroidOsCapabilities.create`
            // from the Android `WeftRuntime.create(...)` factory.
            api(project(":os-bridge"))

            implementation(libs.kotlinx.coroutines.android)
            // OkHttp engine for the substrate's whitelisting HTTP client
            // used by network_fetch.
            implementation(libs.ktor.client.okhttp)
            // Force okhttp-sse onto the same major as the resolved core
            // OkHttp (Koog drags 5.3.2 in; ktor would otherwise leave
            // okhttp-sse at 4.12.0 and we get a NoClassDefFoundError at
            // the first streaming call).
            implementation(libs.okhttp.sse)

            // Android-specific SQLDelight driver — wires the commonMain
            // `createWeftDriver` actual. The driver itself is
            // androidMain-only because it depends on `Context`.
            implementation(libs.sqldelight.android.driver)

            // Bundled SQLite — guarantees FTS5 + modern features
            // regardless of device. Wired via SupportSQLiteOpenHelper.Factory
            // in the androidMain `createWeftDriver` actual.
            implementation(libs.osmerion.sqlite.android)
        }

        val iosMain by getting {
            dependencies {
                // SQLDelight's Native driver — wires the iosMain
                // `createWeftDriver` actual. Bundles its own SQLite
                // (Kotlin/Native links libsqlite3 from the system) so
                // we don't need an Osmerion-equivalent on iOS.
                implementation(libs.sqldelight.native.driver)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                // JDBC SQLite driver for unit tests — lets migration tests
                // run on the JVM without needing an Android instrumentation
                // context. Same schema bytes; same FTS5 support (the
                // bundled sqlite-jdbc enables FTS5).
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.kotlinx.coroutines.test)
                implementation(kotlin("test"))
                // Fake OsCapabilities + UiBridge + weftToolContext() — used
                // by prompt-scoping tests that need to construct a real
                // WeftTool without bringing up an Android Context.
                implementation(project(":harness:testing"))
            }
        }
    }
}

android {
    namespace = "dev.weft.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
