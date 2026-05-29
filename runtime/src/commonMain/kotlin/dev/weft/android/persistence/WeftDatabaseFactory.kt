@file:Suppress("EXPECT_ACTUAL_CLASSES_ARE_IN_BETA_WARNING")

package dev.weft.android.persistence

import app.cash.sqldelight.db.SqlDriver
import dev.weft.android.db.WeftDatabase

/**
 * Per-platform handle the SQLDelight driver factory needs. Carries
 * whatever the actual driver requires (the Android `Context` on
 * androidTarget, nothing on iOS, etc.) without leaking platform types
 * into the commonMain API.
 *
 * Hosts construct one at composition time and thread it through to
 * [WeftDatabaseFactory.create]. Android hosts pass the application
 * context; iOS hosts pass `WeftPlatform()`.
 */
public expect class WeftPlatform

/**
 * Build the SQLDelight driver for the current platform. Internal —
 * call sites go through [WeftDatabaseFactory.create] instead.
 *
 * androidMain wires `AndroidSqliteDriver` + Osmerion's bundled SQLite
 * (FTS5 always available). iosMain wires `NativeSqliteDriver` which
 * links the system libsqlite3 on Kotlin/Native.
 */
internal expect fun createWeftDriver(
    platform: WeftPlatform,
    name: String,
): SqlDriver

/**
 * Builds the singleton SQLDelight [WeftDatabase] used by every
 * persistent store the substrate ships: MemoryStore, TraceStore,
 * UsageStore, ConversationStore, and the small KeyValue/Script
 * scratchpads.
 *
 * One database file per app, in the platform's standard app-private
 * storage (`/data/data/<pkg>/databases/` on Android,
 * `~/Library/Application Support/<bundleId>/databases/` on iOS via the
 * Native driver). The schema lives in the `.sq` files under
 * `src/commonMain/sqldelight/dev/weft/android/db/`; migration workflow
 * + rules live in `MIGRATIONS.md` in that same directory.
 *
 * Versioning: SQLDelight derives the schema version from
 * `1 + count(*.sqm files)`. On Android, [AndroidSqliteDriver] passes
 * the schema to SQLiteOpenHelper, which calls `Schema.create()` on
 * first install and `Schema.migrate(driver, from, to)` on subsequent
 * upgrades. The iOS Native driver wires the same callback through
 * its own `DatabaseConfiguration`. Either way, no manual migration
 * plumbing needed.
 *
 * App authors don't normally call this directly — [WeftRuntime] (on
 * Android) or `IosWeftAgentFactory` (on iOS) builds the database once
 * and threads it into each store.
 */
public object WeftDatabaseFactory {
    /** Default DB filename. Apps wanting a different name pass their own. */
    public const val DEFAULT_DB_NAME: String = "substrate.db"

    public fun create(platform: WeftPlatform, name: String = DEFAULT_DB_NAME): WeftDatabase =
        WeftDatabase(createWeftDriver(platform, name))
}
