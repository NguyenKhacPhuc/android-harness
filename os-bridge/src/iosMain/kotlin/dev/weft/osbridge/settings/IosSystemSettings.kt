package dev.weft.osbridge.settings

import dev.weft.contracts.SettingsPanel
import dev.weft.contracts.SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import kotlin.coroutines.resume

/**
 * iOS [SystemSettings]. iOS restricts third-party deep links to this
 * app's OWN Settings page via `UIApplicationOpenSettingsURLString` —
 * there is no public API to open a system-wide panel (Wi-Fi, Bluetooth,
 * etc.). App-scoped panels open that page; every system-wide panel
 * returns false.
 *
 * Open so hosts can subclass and override individual methods.
 */
public open class IosSystemSettings : SystemSettings {

    override suspend fun open(panel: SettingsPanel): Boolean = when (panel) {
        SettingsPanel.APP_DETAILS,
        SettingsPanel.APP_NOTIFICATIONS,
        SettingsPanel.NOTIFICATIONS,
        -> openAppSettings()
        else -> false
    }

    private suspend fun openAppSettings(): Boolean {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return false
        return withContext(Dispatchers.Main) {
            val app = UIApplication.sharedApplication
            if (!app.canOpenURL(url)) return@withContext false
            suspendCancellableCoroutine { cont ->
                app.openURL(url, options = emptyMap<Any?, Any?>()) { success ->
                    if (cont.isActive) cont.resume(success)
                }
            }
        }
    }
}
