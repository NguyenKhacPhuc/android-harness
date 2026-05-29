package dev.weft.osbridge.power

import dev.weft.contracts.Power

/**
 * iOS stub for [Power]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIApplication.shared.isIdleTimerDisabled = true`
 * for keepScreenOn (must be toggled on the main thread; setting back
 * to false releases the pin). `UIScreen.main.brightness = value` for
 * setBrightness — this is a SYSTEM-WIDE setter on iOS (no per-window
 * override like Android), so it persists even after the app
 * backgrounds. Use -1f to no-op since iOS has no "release" semantic.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosPower : Power {
    override suspend fun keepScreenOn(enabled: Boolean): Boolean =
        TODO("IosPower.keepScreenOn — wrap UIApplication.shared.isIdleTimerDisabled = enabled on the main thread")

    override suspend fun setBrightness(normalized: Float): Boolean =
        TODO("IosPower.setBrightness — wrap UIScreen.main.brightness = normalized (system-wide; no per-window override on iOS)")
}
