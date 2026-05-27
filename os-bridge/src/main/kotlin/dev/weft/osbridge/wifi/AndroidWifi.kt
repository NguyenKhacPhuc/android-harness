package dev.weft.osbridge.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.weft.contracts.Wifi
import dev.weft.contracts.WifiInfo as WeftWifiInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Wifi]. Reads the active connection's
 * SSID, link speed, RSSI, and frequency via [WifiManager.getConnectionInfo].
 *
 * SSID censorship: on Android 9+ the OS returns `<unknown ssid>`
 * unless the app has FINE_LOCATION granted. We surface null in that
 * case rather than the literal placeholder so the agent can react
 * sensibly ("ask the user to enable location" vs "show the SSID").
 */
@Suppress("DEPRECATION")
public class AndroidWifi(context: Context) : Wifi {
    private val appContext: Context = context.applicationContext

    override suspend fun info(): WeftWifiInfo = withContext(Dispatchers.IO) {
        val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@withContext WeftWifiInfo(enabled = false, connected = false)

        val enabled = runCatching { wm.isWifiEnabled }.getOrDefault(false)
        val raw = runCatching { wm.connectionInfo }.getOrNull()
            ?: return@withContext WeftWifiInfo(enabled = enabled, connected = false)

        // networkId = -1 means "not connected".
        val connected = raw.networkId != -1
        if (!connected) {
            return@withContext WeftWifiInfo(enabled = enabled, connected = false)
        }

        val ssid = sanitizeSsid(raw.ssid)
        val frequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            raw.frequency.takeIf { it > 0 }
        } else null

        WeftWifiInfo(
            enabled = enabled,
            connected = true,
            ssid = ssid,
            linkSpeedMbps = raw.linkSpeed.takeIf { it > 0 },
            rssi = raw.rssi.takeIf { it != Int.MIN_VALUE },
            frequencyMhz = frequency,
        )
    }

    private fun sanitizeSsid(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        // Android wraps SSIDs in quotes for ASCII-text networks; hex
        // for non-text. Strip the quotes; leave hex alone.
        val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1)
        } else raw
        if (unquoted == UNKNOWN_SSID_PLACEHOLDER) return null
        if (!hasFineLocation()) return null
        return unquoted.ifBlank { null }
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val UNKNOWN_SSID_PLACEHOLDER = "<unknown ssid>"
    }
}
