package dev.weft.osbridge.power

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import dev.weft.contracts.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Android implementation of [Power]. Toggles the FLAG_KEEP_SCREEN_ON
 * window flag and sets per-window screen brightness on the foreground
 * Activity. Falls back to false when no Activity is in foreground
 * (background coroutine, app minimized).
 *
 * Per-window brightness override means the change reverts when the
 * Activity is destroyed — we don't touch system brightness (which
 * would need WRITE_SETTINGS, a permission Play Store flags for
 * generic apps).
 */
public class AndroidPower(context: Context) : Power {
    private val app: Application = context.applicationContext as Application

    @Volatile
    private var foreground: WeakReference<Activity>? = null

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foreground = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (foreground?.get() === activity) foreground = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override suspend fun keepScreenOn(enabled: Boolean): Boolean = withContext(Dispatchers.Main) {
        val activity = foreground?.get() ?: return@withContext false
        runCatching {
            val window = activity.window ?: return@runCatching false
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            true
        }.getOrDefault(false)
    }

    override suspend fun setBrightness(normalized: Float): Boolean = withContext(Dispatchers.Main) {
        val activity = foreground?.get() ?: return@withContext false
        runCatching {
            val window = activity.window ?: return@runCatching false
            val attrs = window.attributes
            attrs.screenBrightness = if (normalized < 0f) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                normalized.coerceIn(0f, 1f)
            }
            window.attributes = attrs
            true
        }.getOrDefault(false)
    }
}
