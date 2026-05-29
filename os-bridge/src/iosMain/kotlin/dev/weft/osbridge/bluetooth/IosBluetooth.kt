package dev.weft.osbridge.bluetooth

import dev.weft.contracts.Bluetooth
import dev.weft.contracts.BluetoothDeviceInfo

/**
 * iOS stub for [Bluetooth]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: iOS deliberately exposes no public API for
 * listing system-paired Bluetooth devices (Classic pairing is private;
 * BLE pairing is per-app via `CoreBluetooth.CBCentralManager.retrieveConnectedPeripherals(withServices:)`).
 * Practical implementations: return an empty list for [listPaired] and
 * use `UIApplication.shared.open(URL(string: "App-Prefs:Bluetooth"))`
 * (private; will likely be rejected) or fall back to the
 * `App-Prefs:root=Bluetooth` deep link. [deviceBattery] is only
 * accessible to the system audio routing — third-party apps cannot
 * read peripheral battery without GATT discovery, which requires the
 * peripheral to advertise the standard Battery Service (0x180F).
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosBluetooth : Bluetooth {
    override suspend fun listPaired(): List<BluetoothDeviceInfo> =
        TODO("IosBluetooth.listPaired — iOS has no public paired-list API; return emptyList() or wrap CBCentralManager.retrieveConnectedPeripherals(withServices:)")

    override suspend fun openSettings(): Boolean =
        TODO("IosBluetooth.openSettings — wrap UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString))")

    override suspend fun deviceBattery(address: String): Int? =
        TODO("IosBluetooth.deviceBattery — wrap CoreBluetooth GATT read of Battery Service 0x180F characteristic 0x2A19")
}
