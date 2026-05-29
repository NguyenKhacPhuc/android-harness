package dev.weft.osbridge.intents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import androidx.browser.customtabs.CustomTabsIntent
import dev.weft.contracts.Intents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Android implementation of [Intents]. Backs `external_open_url` and
 * `external_launch_app`.
 *
 * Notes:
 *   - Intent.FLAG_ACTIVITY_NEW_TASK is required because we launch from an
 *     application context (not necessarily an Activity).
 *   - For in-app browsing we use Chrome Custom Tabs when available; falls
 *     back to the system browser if not.
 */
class AndroidIntents(private val context: Context) : Intents {

    override suspend fun launchApp(target: String, payload: JsonObject?): Boolean = withContext(Dispatchers.Default) {
        val intent = when {
            // Looks like a URI scheme (`tel:`, `mailto:`, `sms:`, custom-scheme://…)
            target.contains(':') -> Intent(Intent.ACTION_VIEW, Uri.parse(target))
            // Otherwise treat as a package id and launch the app's launcher activity.
            else -> context.packageManager.getLaunchIntentForPackage(target)
                ?: return@withContext false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        applyPayload(intent, payload)
        runCatching { context.startActivity(intent) }.isSuccess
    }

    override suspend fun openUrl(url: String, inApp: Boolean): Boolean = withContext(Dispatchers.Default) {
        val uri = runCatching { Uri.parse(url) }.getOrElse { return@withContext false }
        if (inApp) {
            val tabs = CustomTabsIntent.Builder().build()
            tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { tabs.launchUrl(context, uri) }.isSuccess
        } else {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(intent) }.isSuccess
        }
    }

    override suspend fun openMapsDirections(
        to: String,
        from: String?,
        mode: String,
    ): Boolean = withContext(Dispatchers.Default) {
        // Use Google Maps' universal cross-platform deep link. Most maps
        // apps (Google Maps, Waze with limits, Organic Maps) handle this
        // via Android's app-link routing.
        val travelMode = when (mode.lowercase()) {
            "walking" -> "walking"
            "transit" -> "transit"
            "bicycling", "biking", "cycling" -> "bicycling"
            else -> "driving"
        }
        val url = buildString {
            append("https://www.google.com/maps/dir/?api=1")
            append("&destination=").append(Uri.encode(to))
            if (!from.isNullOrBlank()) append("&origin=").append(Uri.encode(from))
            append("&travelmode=").append(travelMode)
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Resolve before launching — emulators / minimal ROMs may not
        // ship with a maps app. https URL also needs a browser as
        // fallback handler; we check the same way.
        val resolver = intent.resolveActivity(context.packageManager)
        if (resolver == null) return@withContext false
        runCatching { context.startActivity(intent) }.isSuccess
    }

    override suspend fun openAlarmSet(
        hour: Int,
        minute: Int,
        label: String?,
    ): Boolean = withContext(Dispatchers.Default) {
        // AlarmClock.ACTION_SET_ALARM hands off to the user's clock app
        // for confirmation — we don't actually set the alarm ourselves,
        // and we can't tell whether the user confirmed. Returning true
        // here means "the intent launched", not "the alarm exists".
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            // EXTRA_SKIP_UI = false → user always sees their clock app's
            // confirmation screen. That's intentional; silently setting
            // an alarm without confirmation is a hostile surprise.
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Resolve before launching. Pixel emulators + some minimal ROMs
        // (including stock Android 16 emulator images) ship without any
        // app registered for ACTION_SET_ALARM. Checking first lets the
        // caller hand back a useful error instead of an opaque
        // ActivityNotFoundException.
        val resolver = intent.resolveActivity(context.packageManager)
        if (resolver == null) return@withContext false
        runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun applyPayload(intent: Intent, payload: JsonObject?) {
        payload ?: return
        // Best-effort: stringify each top-level key as an extra. Real apps registering custom-scheme
        // handlers will parse this however they choose.
        for ((key, value) in payload) {
            intent.putExtra(key, value.toString())
        }
    }
}
