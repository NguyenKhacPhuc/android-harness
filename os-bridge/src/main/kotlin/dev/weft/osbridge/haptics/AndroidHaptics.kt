package dev.weft.osbridge.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.weft.contracts.HapticEffect
import dev.weft.contracts.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Haptics]. Tries the modern
 * [VibrationEffect.Composition] API on API 30+, falls back to
 * [VibrationEffect.createWaveform] on API 26+, and to the deprecated
 * [Vibrator.vibrate]`(long)` on older devices.
 *
 * No-ops on devices with no vibrator or with vibration disabled in
 * settings — same posture as `View.performHapticFeedback`.
 */
public class AndroidHaptics(context: Context) : Haptics {

    private val appContext: Context = context.applicationContext

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override suspend fun perform(effect: HapticEffect): Unit = withContext(Dispatchers.Default) {
        val v = vibrator ?: return@withContext
        if (!v.hasVibrator()) return@withContext
        playCompositionPrimitive(v, effect) ?: playWaveform(v, effect)
    }

    /**
     * Modern path: VibrationEffect.Composition primitives (API 30+).
     * Returns null when not supported so the caller falls back to waveforms.
     */
    private fun playCompositionPrimitive(v: Vibrator, effect: HapticEffect): Unit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val composition = VibrationEffect.startComposition()
        when (effect) {
            HapticEffect.TICK -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
            HapticEffect.CLICK -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
            HapticEffect.HEAVY_CLICK -> composition.addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_CLICK, /* scale = */ 1.0f,
            )
            HapticEffect.DOUBLE_CLICK -> {
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 0)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, DOUBLE_CLICK_DELAY_MS)
            }
            HapticEffect.SUCCESS -> {
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 0)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 80)
            }
            HapticEffect.WARNING -> composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
            HapticEffect.ERROR -> {
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 0)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 60)
            }
        }
        return runCatching { v.vibrate(composition.compose()) }.getOrNull()
    }

    /**
     * Fallback path: hand-rolled waveform patterns for API 26..29 (and a
     * coarse approximation on anything older via the deprecated path).
     */
    @Suppress("DEPRECATION")
    private fun playWaveform(v: Vibrator, effect: HapticEffect) {
        val pattern: LongArray = when (effect) {
            HapticEffect.TICK -> longArrayOf(0, 10)
            HapticEffect.CLICK -> longArrayOf(0, 20)
            HapticEffect.HEAVY_CLICK -> longArrayOf(0, 35)
            HapticEffect.DOUBLE_CLICK -> longArrayOf(0, 20, DOUBLE_CLICK_DELAY_MS.toLong(), 20)
            HapticEffect.SUCCESS -> longArrayOf(0, 15, 80, 25)
            HapticEffect.WARNING -> longArrayOf(0, 80)
            HapticEffect.ERROR -> longArrayOf(0, 80, 60, 80)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, /* repeat = */ -1))
        } else {
            v.vibrate(pattern, /* repeat = */ -1)
        }
    }

    private companion object {
        // Apple HIG-aligned: ~70-80ms between the two taps in a double-click
        // feels like one composite gesture, not two separate events.
        const val DOUBLE_CLICK_DELAY_MS = 70
    }
}
