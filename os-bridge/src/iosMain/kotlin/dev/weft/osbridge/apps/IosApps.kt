package dev.weft.osbridge.apps

import dev.weft.contracts.AppInfo
import dev.weft.contracts.Apps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS [Apps]. iOS exposes no installed-app catalog, so [isInstalled]
 * treats [packageName] as a URL scheme and probes `canOpenURL`; this
 * only returns true for schemes the host whitelists in
 * `LSApplicationQueriesSchemes`. [listLaunchable] is impossible on
 * stock iOS (no enumeration API) and returns an empty list.
 *
 * Open so hosts can subclass and override individual methods.
 */
public open class IosApps : Apps {

    override suspend fun isInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val url = NSURL.URLWithString("$packageName://") ?: return false
        return withContext(Dispatchers.Main) {
            UIApplication.sharedApplication.canOpenURL(url)
        }
    }

    override suspend fun listLaunchable(limit: Int): List<AppInfo> = emptyList()
}
