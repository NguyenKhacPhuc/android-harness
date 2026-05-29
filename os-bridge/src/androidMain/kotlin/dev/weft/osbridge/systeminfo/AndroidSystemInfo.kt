package dev.weft.osbridge.systeminfo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import dev.weft.contracts.BatteryInfo
import dev.weft.contracts.DeviceInfo
import dev.weft.contracts.DisplayInfo
import dev.weft.contracts.NetworkInfo
import dev.weft.contracts.NetworkTransport
import dev.weft.contracts.PowerSource
import dev.weft.contracts.SystemInfo
import java.util.Locale
import java.util.TimeZone

/**
 * Android implementation of [SystemInfo]. Cheap, no I/O — every reader
 * just queries a system service or the [Build] constants. Wrapped in
 * `runCatching` defensively because system services can be flaky on
 * customized OEM builds.
 *
 * No coroutine context switch (`withContext(Dispatchers.IO)`) because
 * none of these calls actually block — BatteryManager + ConnectivityManager
 * are synchronous registry reads, and StatFs is a tiny libc call.
 */
class AndroidSystemInfo(private val context: Context) : SystemInfo {

    override suspend fun battery(): BatteryInfo {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val percent = runCatching {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 }

        // ACTION_BATTERY_CHANGED is a sticky broadcast — registerReceiver(null, ...)
        // returns the latest stuck Intent without registering anything.
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val statusInt = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pluggedInt = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val charging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusInt == BatteryManager.BATTERY_STATUS_FULL ||
            pluggedInt > 0

        val powerSource = when (pluggedInt) {
            0 -> PowerSource.NONE
            BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
            BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
            // BATTERY_PLUGGED_DOCK landed in API 33; refer numerically to stay
            // compatible with API 26+ minSdk.
            4 -> PowerSource.DOCK
            else -> PowerSource.UNKNOWN
        }

        val saverMode = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        }.getOrDefault(false)

        return BatteryInfo(
            percent = percent,
            charging = charging,
            powerSource = powerSource,
            saverMode = saverMode,
        )
    }

    override suspend fun network(): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkInfo(online = false, transport = NetworkTransport.NONE, metered = false)

        val active = runCatching { cm.activeNetwork }.getOrNull()
        val caps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            ?: return NetworkInfo(online = false, transport = NetworkTransport.NONE, metered = false)

        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.BLUETOOTH
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.NONE
        }

        // NOT_METERED = unmetered (wifi typically); absence = metered.
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return NetworkInfo(online = online, transport = transport, metered = metered)
    }

    override suspend fun device(): DeviceInfo {
        val stat = runCatching {
            StatFs(context.filesDir.absolutePath)
        }.getOrNull()
        val totalBytes = stat?.let { it.blockCountLong * it.blockSizeLong } ?: -1L
        val freeBytes = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: -1L

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            osName = "Android",
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            sdkInt = Build.VERSION.SDK_INT,
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            storageFreeBytes = freeBytes,
            storageTotalBytes = totalBytes,
        )
    }

    @Suppress("DEPRECATION")
    override suspend fun display(): DisplayInfo {
        val metrics = context.resources.displayMetrics
        val nightMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        val display: Display? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.defaultDisplay
            }
        }.getOrNull()

        val refresh = runCatching { display?.refreshRate ?: 60f }.getOrDefault(60f)

        // SCREEN_BRIGHTNESS is 0..255 in System settings. We normalize
        // to 0..1. When auto-brightness is on the stored value reflects
        // the user's last manual setting, not the actual screen state —
        // we surface it anyway, the model can correlate with the auto
        // flag if needed.
        val brightness: Float? = runCatching {
            val raw = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
            (raw / BRIGHTNESS_MAX_RAW).coerceIn(0f, 1f)
        }.getOrNull()

        val screenOn = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive == true
        }.getOrDefault(false)

        return DisplayInfo(
            darkMode = nightMode,
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            density = metrics.density,
            refreshRateHz = refresh,
            brightness = brightness,
            screenOn = screenOn,
        )
    }

    private companion object {
        const val BRIGHTNESS_MAX_RAW = 255f
    }
}
