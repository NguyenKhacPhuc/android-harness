package dev.weft.osbridge.systeminfo

import dev.weft.contracts.BatteryInfo
import dev.weft.contracts.DeviceInfo
import dev.weft.contracts.DisplayInfo
import dev.weft.contracts.NetworkInfo
import dev.weft.contracts.SystemInfo

/**
 * iOS stub for [SystemInfo]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: composite —
 * `UIDevice.current` (`batteryLevel`, `batteryState`, `systemName`,
 * `systemVersion`, `model`) for battery + device,
 * `ProcessInfo.processInfo.isLowPowerModeEnabled` for saver mode,
 * `Network.NWPathMonitor` for network + transport, `UIScreen.main`
 * (`bounds`, `scale`, `brightness`, `maximumFramesPerSecond`) and
 * `UITraitCollection.current.userInterfaceStyle` for display, plus
 * `FileManager.default.attributesOfFileSystem(forPath:)` for storage.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosSystemInfo : SystemInfo {
    override suspend fun battery(): BatteryInfo =
        TODO("IosSystemInfo.battery — wrap UIDevice.current.batteryLevel/batteryState + ProcessInfo.isLowPowerModeEnabled")

    override suspend fun network(): NetworkInfo =
        TODO("IosSystemInfo.network — wrap Network.NWPathMonitor with NWPath.usesInterfaceType(_:) for transport")

    override suspend fun device(): DeviceInfo =
        TODO("IosSystemInfo.device — wrap UIDevice.current + Locale.current + TimeZone.current + FileManager attributesOfFileSystem")

    override suspend fun display(): DisplayInfo =
        TODO("IosSystemInfo.display — wrap UIScreen.main bounds/scale/brightness/maximumFramesPerSecond + UITraitCollection.userInterfaceStyle")
}
