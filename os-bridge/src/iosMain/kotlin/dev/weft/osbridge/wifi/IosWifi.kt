package dev.weft.osbridge.wifi

import dev.weft.contracts.Wifi
import dev.weft.contracts.WifiInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsConnectionRequired
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable

/**
 * iOS [Wifi]. Connectivity is approximated with the same one-shot
 * SCNetworkReachability probe as IosSystemInfo.network(): reachable +
 * not-WWAN means a wifi-class transport. iOS limitations make several
 * fields unknowable from a sandboxed substrate: [WifiInfo.ssid] needs
 * the Access-WiFi-Information entitlement + NEHotspotNetwork; and
 * link speed / RSSI / frequency have no public API. [WifiInfo.enabled]
 * can't read the radio toggle, so it mirrors [WifiInfo.connected].
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosWifi : Wifi {

    override suspend fun info(): WifiInfo = memScoped {
        val ref = SCNetworkReachabilityCreateWithName(null, "apple.com")
            ?: return@memScoped DISCONNECTED
        val flags = alloc<SCNetworkReachabilityFlagsVar>()
        if (!SCNetworkReachabilityGetFlags(ref, flags.ptr)) return@memScoped DISCONNECTED
        val f = flags.value
        val reachable = (f and kSCNetworkReachabilityFlagsReachable) != 0u &&
            (f and kSCNetworkReachabilityFlagsConnectionRequired) == 0u
        val wwan = (f and kSCNetworkReachabilityFlagsIsWWAN) != 0u
        val connected = reachable && !wwan
        WifiInfo(
            // iOS can't read the radio toggle from a sandboxed app — approximate.
            enabled = connected,
            connected = connected,
            // Requires Access-WiFi-Information entitlement + NEHotspotNetwork.
            ssid = null,
            // No public API for these on iOS.
            linkSpeedMbps = null,
            rssi = null,
            frequencyMhz = null,
        )
    }

    private companion object {
        val DISCONNECTED = WifiInfo(enabled = false, connected = false)
    }
}
