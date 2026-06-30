package dev.weft.osbridge.usercontext

import dev.weft.contracts.UserContext
import dev.weft.contracts.UserContextField
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.localTimeZone
import platform.Foundation.localeIdentifier
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsConnectionRequired
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

/**
 * iOS [UserContext]. Assembles the requested [UserContextField] subset
 * into a [JsonObject] keyed by lowercased field name. LOCATION is
 * skipped — this capability carries no location permission. UIKit reads
 * (DEVICE_CLASS, BATTERY) run on the main thread; NETWORK uses a
 * one-shot SCNetworkReachability probe like `IosSystemInfo.network()`.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosUserContext : UserContext {

    override suspend fun snapshot(fields: Set<UserContextField>): JsonObject {
        val device = if (UserContextField.DEVICE_CLASS in fields) deviceClass() else null
        val battery = if (UserContextField.BATTERY in fields) batteryPercent() else null
        return buildJsonObject {
            if (UserContextField.TIME in fields) {
                put("time", JsonPrimitive(NSISO8601DateFormatter().stringFromDate(NSDate())))
            }
            if (UserContextField.TIMEZONE in fields) {
                put("timezone", JsonPrimitive(NSTimeZone.localTimeZone.name))
            }
            if (UserContextField.LOCALE in fields) {
                put("locale", JsonPrimitive(NSLocale.currentLocale.localeIdentifier.replace('_', '-')))
            }
            if (UserContextField.DEVICE_CLASS in fields) {
                put("device_class", JsonPrimitive(device))
            }
            if (UserContextField.BATTERY in fields && battery != null) {
                put("battery", JsonPrimitive(battery))
            }
            if (UserContextField.NETWORK in fields) {
                put("network", JsonPrimitive(network()))
            }
            // LOCATION intentionally omitted — no location permission in this capability.
        }
    }

    private suspend fun deviceClass(): String = withContext(Dispatchers.Main) {
        if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
            "tablet"
        } else {
            "phone"
        }
    }

    private suspend fun batteryPercent(): Int? = withContext(Dispatchers.Main) {
        val d = UIDevice.currentDevice
        d.batteryMonitoringEnabled = true
        val level = d.batteryLevel
        if (level < 0f) null else (level * PERCENT).toInt()
    }

    private fun network(): String = memScoped {
        val ref = SCNetworkReachabilityCreateWithName(null, "apple.com") ?: return@memScoped "none"
        val flags = alloc<SCNetworkReachabilityFlagsVar>()
        if (!SCNetworkReachabilityGetFlags(ref, flags.ptr)) return@memScoped "none"
        val f = flags.value
        val reachable = (f and kSCNetworkReachabilityFlagsReachable) != 0u &&
            (f and kSCNetworkReachabilityFlagsConnectionRequired) == 0u
        if (!reachable) return@memScoped "none"
        if ((f and kSCNetworkReachabilityFlagsIsWWAN) != 0u) "cellular" else "wifi"
    }

    private companion object {
        const val PERCENT = 100f
    }
}
