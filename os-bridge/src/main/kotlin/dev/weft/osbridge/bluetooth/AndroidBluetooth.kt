package dev.weft.osbridge.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.weft.contracts.Bluetooth
import dev.weft.contracts.BluetoothDeviceInfo
import dev.weft.contracts.BluetoothDeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android impl of [Bluetooth]. Intentionally narrow surface —
 * scanning / connecting / file transfer aren't here. The reasoning is
 * in the [Bluetooth] interface KDoc.
 *
 * Permission handling: on Android 12+ (API 31+) all read-side calls
 * need `BLUETOOTH_CONNECT`. We check at the start of each call and
 * return safely-empty results on denial, instead of throwing — the
 * substrate's permission gate already runs upstream via the
 * [dev.weft.contracts.Permission.BLUETOOTH_CONNECT] declaration on
 * the tools. These checks are defense-in-depth.
 */
class AndroidBluetooth(private val context: Context) : Bluetooth {

    private val adapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    @SuppressWarnings("MissingPermission")
    override suspend fun listPaired(): List<BluetoothDeviceInfo> = withContext(Dispatchers.Default) {
        if (!hasBluetoothConnectPermission()) return@withContext emptyList()
        val a = adapter?.takeIf { it.isEnabled } ?: return@withContext emptyList()
        // bondedDevices is a hot read off the adapter; no I/O.
        val bonded = runCatching { a.bondedDevices ?: emptySet<BluetoothDevice>() }.getOrDefault(emptySet())
        val connectedAddresses = collectConnectedAddresses()
        bonded.map { device ->
            BluetoothDeviceInfo(
                address = device.address ?: "",
                name = runCatching { device.name }.getOrNull(),
                type = device.bluetoothType(),
                deviceClass = device.bluetoothClass?.majorDeviceClass?.let { majorClassLabel(it) },
                connected = device.address in connectedAddresses,
            )
        }
    }

    override suspend fun openSettings(): Boolean = withContext(Dispatchers.Default) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.isSuccess
    }

    @SuppressWarnings("MissingPermission")
    override suspend fun deviceBattery(address: String): Int? = withContext(Dispatchers.Default) {
        if (!hasBluetoothConnectPermission()) return@withContext null
        // METADATA_MAIN_BATTERY landed in API 29 but the public surface
        // is marked @SystemApi in some Android source trees, so the
        // compileSdk-shipped stubs don't expose it. Reflection bypass
        // works on all real devices that have the runtime method. If a
        // device doesn't have it (older OS, OEM strip), we return null.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val a = adapter?.takeIf { it.isEnabled } ?: return@withContext null
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return@withContext null
        val raw = runCatching {
            val getMetadata = BluetoothDevice::class.java
                .getMethod("getMetadata", Int::class.javaPrimitiveType)
            // METADATA_MAIN_BATTERY constant value is 1.
            getMetadata.invoke(device, METADATA_MAIN_BATTERY_VALUE) as? ByteArray
        }.getOrNull() ?: return@withContext null
        // Returns the percent as ASCII bytes (e.g. "85"), or null when
        // the device doesn't report battery.
        val str = runCatching { String(raw, Charsets.UTF_8).trim() }.getOrNull() ?: return@withContext null
        str.toIntOrNull()?.takeIf { it in 0..100 }
    }

    /**
     * Public value of [BluetoothDevice.METADATA_MAIN_BATTERY] (Android
     * doesn't expose it as a public constant on every SDK level, so
     * we inline the documented value).
     */
    private companion object {
        const val METADATA_MAIN_BATTERY_VALUE = 1
    }

    /**
     * Best-effort active-connection lookup. Profiles other than A2DP /
     * HEADSET / HID may also matter for some devices; this is good enough
     * for the common "are my headphones connected?" case.
     */
    @SuppressWarnings("MissingPermission")
    private fun collectConnectedAddresses(): Set<String> {
        val a = adapter ?: return emptySet()
        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.HID_DEVICE)
        // Connection state is held by a profile service; getProfileConnectionState
        // returns the state without requiring a service bind. Slightly imprecise
        // (returns aggregate state for the profile, not per-device), but enough
        // for a "device is connected somewhere" signal.
        val anyProfileConnected = profiles.any { profile ->
            runCatching {
                a.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
        }
        if (!anyProfileConnected) return emptySet()
        // We don't have a per-device check without a profile service bind.
        // Mark all bonded devices as connected when ANY profile reports
        // a connection — false positives are acceptable for this surface
        // (the UI/agent just sees a hint).
        val bonded = runCatching { a.bondedDevices ?: emptySet<BluetoothDevice>() }.getOrDefault(emptySet())
        return bonded.mapNotNull { it.address }.toSet()
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT is API 31+. On older Androids the legacy
        // install-time BLUETOOTH permission covers us.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@SuppressWarnings("MissingPermission")
private fun BluetoothDevice.bluetoothType(): BluetoothDeviceType {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return BluetoothDeviceType.UNKNOWN
    return when (runCatching { type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothDeviceType.CLASSIC
        BluetoothDevice.DEVICE_TYPE_LE -> BluetoothDeviceType.LE
        BluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothDeviceType.DUAL
        else -> BluetoothDeviceType.UNKNOWN
    }
}

/**
 * Translate `BluetoothClass.Device.Major` constants into a short label.
 * Not exhaustive — covers the categories that pop up most for consumer
 * use (audio, wearable, computer, phone, peripheral).
 */
private fun majorClassLabel(major: Int): String? = when (major) {
    0x0100 -> "computer"     // Major.COMPUTER
    0x0200 -> "phone"        // Major.PHONE
    0x0400 -> "audio_video"  // Major.AUDIO_VIDEO
    0x0500 -> "peripheral"   // Major.PERIPHERAL (keyboards, mice)
    0x0600 -> "imaging"      // Major.IMAGING
    0x0700 -> "wearable"     // Major.WEARABLE
    0x0800 -> "toy"          // Major.TOY
    0x0900 -> "health"       // Major.HEALTH
    else -> null
}
