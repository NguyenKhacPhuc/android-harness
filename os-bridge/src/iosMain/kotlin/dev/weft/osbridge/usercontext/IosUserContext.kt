package dev.weft.osbridge.usercontext

import dev.weft.contracts.UserContext
import dev.weft.contracts.UserContextField
import kotlinx.serialization.json.JsonObject

/**
 * iOS stub for [UserContext]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: composite — `CLLocationManager.location` for LOCATION,
 * `Date()` + `TimeZone.current` for TIME / TIMEZONE,
 * `Locale.current.identifier` for LOCALE, `UIDevice.current.batteryLevel`
 * for BATTERY, `Network.NWPathMonitor` for NETWORK, and
 * `UIDevice.current.userInterfaceIdiom` for DEVICE_CLASS. Assembles into
 * the requested [UserContextField] subset.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosUserContext : UserContext {
    override suspend fun snapshot(fields: Set<UserContextField>): JsonObject =
        TODO("IosUserContext.snapshot — assemble JsonObject from CLLocationManager / Date / Locale / UIDevice / NWPathMonitor per requested fields")
}
