package dev.weft.osbridge.power

import dev.weft.contracts.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen

/**
 * iOS [Power]. `keepScreenOn` toggles `UIApplication.idleTimerDisabled`
 * (true pins the screen awake, false releases it). `setBrightness` sets
 * `UIScreen.mainScreen.brightness`, which on iOS is system-wide (no
 * per-window override) and persists after backgrounding — a normalized
 * value below 0 is treated as a no-op.
 *
 * Both touch UIKit main-thread-affined state, so they run on the main
 * dispatcher.
 *
 * Open so hosts can subclass and override individual methods.
 */
public open class IosPower : Power {

    override suspend fun keepScreenOn(enabled: Boolean): Boolean = withContext(Dispatchers.Main) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        true
    }

    override suspend fun setBrightness(normalized: Float): Boolean = withContext(Dispatchers.Main) {
        if (normalized < 0f) return@withContext false
        UIScreen.mainScreen.brightness = normalized.coerceIn(0f, 1f).toDouble()
        true
    }
}
