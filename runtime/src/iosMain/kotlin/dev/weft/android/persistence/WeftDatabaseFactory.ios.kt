@file:Suppress("EXPECT_ACTUAL_CLASSES_ARE_IN_BETA_WARNING")

package dev.weft.android.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.weft.android.db.WeftDatabase

/**
 * iOS actual for [WeftPlatform]. Empty — the Native driver doesn't
 * need a platform context. iOS hosts construct `WeftPlatform()` once
 * at composition time and thread it through to
 * [WeftDatabaseFactory.create].
 */
public actual class WeftPlatform

internal actual fun createWeftDriver(platform: WeftPlatform, name: String): SqlDriver =
    NativeSqliteDriver(WeftDatabase.Schema, name)
