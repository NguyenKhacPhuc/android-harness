package dev.weft.osbridge.shortcuts

import dev.weft.contracts.AppShortcuts
import dev.weft.contracts.ShortcutSpec

/**
 * iOS [AppShortcuts] — honest no-op. The natural analogue is Home-Screen
 * Quick Actions via `UIApplication.shortcutItems`, but that property is
 * not reachable through the current Kotlin/Native UIKit binding (it
 * surfaces as an unresolved reference). [push] / [remove] return false
 * and [list] returns empty until a host wires Quick Actions from Swift
 * (or static `UIApplicationShortcutItems` in Info.plist).
 *
 * Open so hosts can subclass and override individual methods.
 */
public open class IosAppShortcuts : AppShortcuts {

    override suspend fun push(spec: ShortcutSpec): Boolean = false

    override suspend fun remove(id: String): Boolean = false

    override suspend fun list(): List<ShortcutSpec> = emptyList()
}
