package dev.weft.osbridge.bluetooth

import dev.weft.contracts.Bluetooth
import dev.weft.contracts.BluetoothDeviceInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import kotlin.coroutines.resume

/**
 * iOS [Bluetooth]. iOS hides the system paired-device list from
 * non-system apps, so [listPaired] returns empty and [deviceBattery]
 * returns null — honest, not faked. [openSettings] opens the app's own
 * Settings page (iOS has no public deep-link to the system Bluetooth
 * panel; the app Settings screen is the closest public target).
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosBluetooth : Bluetooth {

    /** iOS exposes no public API for the system-paired device list. */
    override suspend fun listPaired(): List<BluetoothDeviceInfo> = emptyList()

    override suspend fun openSettings(): Boolean {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return false
        return withContext(Dispatchers.Main) {
            val app = UIApplication.sharedApplication
            if (!app.canOpenURL(url)) return@withContext false
            suspendCancellableCoroutine { cont ->
                app.openURL(url, options = emptyMap<Any?, Any?>()) { success ->
                    cont.resume(success)
                }
            }
        }
    }

    /** Third-party apps can't read a paired device's battery on iOS. */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun deviceBattery(address: String): Int? = null
}
