package dev.weft.android.persistence

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.osmerion.android.database.sqlite.OsmerionSQLiteOpenHelperFactory
import dev.weft.android.db.WeftDatabase

/**
 * Android actual for [WeftPlatform]. Wraps the application [Context]
 * the Android SQLite driver needs. Construct one at composition time
 * (typically inside a Context-bound DI module) and thread it through
 * to [WeftDatabaseFactory.create]; existing callers that pass a
 * [Context] directly continue to work via the
 * [WeftDatabaseFactory.create] extension below.
 *
 * **Bundled SQLite**: the Android `createWeftDriver` opens the DB
 * through Osmerion's `sqlite-android` (a maintained fork of
 * requery/sqlite-android) which ships a modern SQLite compiled with
 * FTS5 and every other feature we use. The default
 * `FrameworkSQLiteOpenHelperFactory` would route through Android's
 * framework SQLite, whose feature set varies by device — notably,
 * FTS5 isn't reliably present below API 27, and some vendor builds
 * strip it even higher. The bundled driver removes that uncertainty
 * entirely.
 */
public actual class WeftPlatform(internal val context: Context)

internal actual fun createWeftDriver(platform: WeftPlatform, name: String): SqlDriver =
    AndroidSqliteDriver(
        schema = WeftDatabase.Schema,
        context = platform.context.applicationContext,
        name = name,
        factory = OsmerionSQLiteOpenHelperFactory(),
    )

