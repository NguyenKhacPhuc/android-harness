package dev.weft.osbridge.intents

import dev.weft.contracts.Intents
import kotlinx.serialization.json.JsonObject

/**
 * iOS stub for [Intents]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIKit.UIApplication.shared.open(_:options:completionHandler:)`
 * with custom URL schemes / universal links for launchApp, https:// or
 * the in-app `SFSafariViewController` for openUrl, Apple Maps URL scheme
 * (`http://maps.apple.com/?daddr=...&saddr=...&dirflg=d`) for directions,
 * and the Clock app has no public URL scheme — `openAlarmSet` likely
 * needs to return false on iOS unless a host provides a custom action.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosIntents : Intents {
    override suspend fun launchApp(target: String, payload: JsonObject?): Boolean =
        TODO("IosIntents.launchApp — wrap UIApplication.shared.open(_:) with the target's URL scheme")

    override suspend fun openUrl(url: String, inApp: Boolean): Boolean =
        TODO("IosIntents.openUrl — wrap UIApplication.shared.open(_:) or present SFSafariViewController when inApp")

    override suspend fun openMapsDirections(to: String, from: String?, mode: String): Boolean =
        TODO("IosIntents.openMapsDirections — wrap UIApplication.shared.open(_:) on a maps.apple.com URL with daddr/saddr/dirflg")

    override suspend fun openAlarmSet(hour: Int, minute: Int, label: String?): Boolean =
        TODO("IosIntents.openAlarmSet — no public Clock URL scheme on iOS; return false or route via Shortcuts intent")
}
