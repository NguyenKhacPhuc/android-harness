package dev.weft.osbridge.apps

import dev.weft.contracts.AppInfo
import dev.weft.contracts.Apps

/**
 * iOS stub for [Apps]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: iOS has no installed-apps enumeration API.
 * [isInstalled] can only check apps that declare a URL scheme — wrap
 * `UIApplication.shared.canOpenURL(URL(string: "<scheme>://"))` after
 * registering the scheme in `LSApplicationQueriesSchemes`. [listLaunchable]
 * is fundamentally impossible on stock iOS and should return an empty
 * list (or a host-curated whitelist of known schemes).
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosApps : Apps {
    override suspend fun isInstalled(packageName: String): Boolean =
        TODO("IosApps.isInstalled — wrap UIApplication.shared.canOpenURL(URL(string: \"<scheme>://\")) with LSApplicationQueriesSchemes registered")

    override suspend fun listLaunchable(limit: Int): List<AppInfo> =
        TODO("IosApps.listLaunchable — iOS exposes no enumeration API; return emptyList() or a host-curated scheme whitelist")
}
