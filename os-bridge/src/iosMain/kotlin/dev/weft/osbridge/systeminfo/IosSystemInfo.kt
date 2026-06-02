package dev.weft.osbridge.systeminfo

import dev.weft.contracts.BatteryInfo
import dev.weft.contracts.DeviceInfo
import dev.weft.contracts.DisplayInfo
import dev.weft.contracts.NetworkInfo
import dev.weft.contracts.NetworkTransport
import dev.weft.contracts.PowerSource
import dev.weft.contracts.SystemInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.localTimeZone
import platform.Foundation.localeIdentifier
import platform.Foundation.lowPowerModeEnabled
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityFlagsVar
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsConnectionRequired
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle

/**
 * iOS [SystemInfo]. `device` is pure reads (UIDevice + Locale + TimeZone
 * + filesystem); `battery` and `display` touch main-thread UIKit state;
 * `network` uses a one-shot SCNetworkReachability probe. `sdkInt` is -1
 * (Android-only concept).
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosSystemInfo : SystemInfo {

    override suspend fun device(): DeviceInfo {
        val d = UIDevice.currentDevice
        val (free, total) = storageBytes()
        return DeviceInfo(
            manufacturer = "Apple",
            model = d.model,
            osName = d.systemName,
            osVersion = d.systemVersion,
            sdkInt = -1,
            locale = NSLocale.currentLocale.localeIdentifier.replace('_', '-'),
            timezone = NSTimeZone.localTimeZone.name,
            storageFreeBytes = free,
            storageTotalBytes = total,
        )
    }

    override suspend fun battery(): BatteryInfo = withContext(Dispatchers.Main) {
        val d = UIDevice.currentDevice
        d.batteryMonitoringEnabled = true
        val level = d.batteryLevel
        val state = d.batteryState
        BatteryInfo(
            percent = if (level < 0f) null else (level * PERCENT).toInt(),
            charging = state == UIDeviceBatteryState.UIDeviceBatteryStateUnplugged,
            powerSource = when (state) {
                UIDeviceBatteryState.UIDeviceBatteryStateCharging,
                UIDeviceBatteryState.UIDeviceBatteryStateFull,
                -> PowerSource.AC
                UIDeviceBatteryState.UIDeviceBatteryStateUnplugged -> PowerSource.NONE
                else -> PowerSource.UNKNOWN
            },
            saverMode = NSProcessInfo.processInfo.lowPowerModeEnabled,
        )
    }

    override suspend fun network(): NetworkInfo = memScoped {
        val ref = SCNetworkReachabilityCreateWithName(null, "apple.com")
            ?: return@memScoped OFFLINE
        val flags = alloc<SCNetworkReachabilityFlagsVar>()
        if (!SCNetworkReachabilityGetFlags(ref, flags.ptr)) return@memScoped OFFLINE
        val f = flags.value
        val reachable = (f and kSCNetworkReachabilityFlagsReachable) != 0u &&
            (f and kSCNetworkReachabilityFlagsConnectionRequired) == 0u
        if (!reachable) return@memScoped OFFLINE
        val wwan = (f and kSCNetworkReachabilityFlagsIsWWAN) != 0u
        NetworkInfo(
            online = true,
            transport = if (wwan) NetworkTransport.CELLULAR else NetworkTransport.WIFI,
            metered = wwan,
        )
    }

    override suspend fun display(): DisplayInfo = withContext(Dispatchers.Main) {
        val screen = UIScreen.mainScreen
        val scale = screen.scale
        val width = screen.bounds.useContents { size.width } * scale
        val height = screen.bounds.useContents { size.height } * scale
        DisplayInfo(
            darkMode = screen.traitCollection.userInterfaceStyle ==
                UIUserInterfaceStyle.UIUserInterfaceStyleDark,
            widthPx = width.toInt(),
            heightPx = height.toInt(),
            density = scale.toFloat(),
            refreshRateHz = screen.maximumFramesPerSecond.toFloat(),
            brightness = screen.brightness.toFloat(),
            screenOn = true,
        )
    }

    private fun storageBytes(): Pair<Long, Long> {
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(NSHomeDirectory(), null)
            ?: return 0L to 0L
        val free = (attrs[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0L
        val total = (attrs[NSFileSystemSize] as? NSNumber)?.longLongValue ?: 0L
        return free to total
    }

    private companion object {
        const val PERCENT = 100f
        val OFFLINE = NetworkInfo(online = false, transport = NetworkTransport.NONE, metered = false)
    }
}
