package dev.weft.osbridge.wifi

import dev.weft.contracts.Wifi
import dev.weft.contracts.WifiInfo

/**
 * iOS stub for [Wifi]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `NetworkExtension.NEHotspotNetwork.fetchCurrent(completionHandler:)`
 * (iOS 14+, requires the Hotspot entitlement + Location permission for
 * SSID) for SSID + signal. Fall back to
 * `SystemConfiguration.CaptiveNetwork.CNCopyCurrentNetworkInfo` (deprecated
 * pre-iOS 14). `Network.NWPathMonitor` reports whether WiFi is the
 * active transport but doesn't expose link speed or RSSI directly —
 * those need the NEHotspotNetwork path.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosWifi : Wifi {
    override suspend fun info(): WifiInfo =
        TODO("IosWifi.info — wrap NEHotspotNetwork.fetchCurrent(completionHandler:) + NWPathMonitor for online flag")
}
