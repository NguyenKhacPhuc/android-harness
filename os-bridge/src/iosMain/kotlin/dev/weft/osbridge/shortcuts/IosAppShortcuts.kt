package dev.weft.osbridge.shortcuts

import dev.weft.contracts.AppShortcuts
import dev.weft.contracts.ShortcutSpec

/**
 * iOS stub for [AppShortcuts]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIKit.UIApplication.shared.shortcutItems` —
 * an array of `UIApplicationShortcutItem(type:localizedTitle:localizedSubtitle:icon:userInfo:)`
 * for the long-press-app-icon shortcuts (Home-screen Quick Actions).
 * Set the array to push/replace, filter and reassign for remove, read
 * for list. iOS caps the count at 4 visible items.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosAppShortcuts : AppShortcuts {
    override suspend fun push(spec: ShortcutSpec): Boolean =
        TODO("IosAppShortcuts.push — wrap UIApplication.shared.shortcutItems mutation with a UIApplicationShortcutItem")

    override suspend fun remove(id: String): Boolean =
        TODO("IosAppShortcuts.remove — wrap UIApplication.shared.shortcutItems filter-and-reassign by .type == id")

    override suspend fun list(): List<ShortcutSpec> =
        TODO("IosAppShortcuts.list — wrap UIApplication.shared.shortcutItems read")
}
