package dev.weft.osbridge.settings

import dev.weft.contracts.SettingsPanel
import dev.weft.contracts.SystemSettings

/**
 * iOS stub for [SystemSettings]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString))`
 * is the only public deep link — it always lands on THIS app's settings
 * page, regardless of [SettingsPanel]. iOS does NOT support deep-linking
 * into arbitrary Settings panels from third-party apps (the
 * `App-Prefs:` / `prefs:` URL scheme was undocumented and is rejected
 * by review). Implementations should return false for every panel
 * except APP_DETAILS / APP_NOTIFICATIONS, both of which open the
 * app's settings page.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosSystemSettings : SystemSettings {
    override suspend fun open(panel: SettingsPanel): Boolean =
        TODO("IosSystemSettings.open — wrap UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)); return false for non-app panels")
}
