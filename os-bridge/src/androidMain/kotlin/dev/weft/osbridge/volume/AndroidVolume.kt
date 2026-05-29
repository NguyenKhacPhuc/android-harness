package dev.weft.osbridge.volume

import android.content.Context
import android.media.AudioManager
import dev.weft.contracts.Volume
import dev.weft.contracts.VolumeStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Volume] backed by [AudioManager].
 *
 * Stream mapping is straight 1:1 to STREAM_* constants. Set operations
 * use the per-stream max as the 1.0 reference so callers don't have
 * to know that ALARM maxes at 7 while MUSIC maxes at 15.
 */
class AndroidVolume(context: Context) : Volume {
    private val appContext: Context = context.applicationContext
    private val am: AudioManager? = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override suspend fun get(stream: VolumeStream): Float = withContext(Dispatchers.IO) {
        val mgr = am ?: return@withContext 0f
        val s = stream.toAndroid()
        val current = runCatching { mgr.getStreamVolume(s) }.getOrDefault(0)
        val max = runCatching { mgr.getStreamMaxVolume(s) }.getOrDefault(1).coerceAtLeast(1)
        (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    override suspend fun set(stream: VolumeStream, normalized: Float): Boolean = withContext(Dispatchers.IO) {
        val mgr = am ?: return@withContext false
        val s = stream.toAndroid()
        val max = runCatching { mgr.getStreamMaxVolume(s) }.getOrDefault(1).coerceAtLeast(1)
        val clamped = normalized.coerceIn(0f, 1f)
        val target = (clamped * max).toInt().coerceIn(0, max)
        runCatching { mgr.setStreamVolume(s, target, 0) }.isSuccess
    }

    private fun VolumeStream.toAndroid(): Int = when (this) {
        VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
        VolumeStream.RING -> AudioManager.STREAM_RING
        VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        VolumeStream.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        VolumeStream.SYSTEM -> AudioManager.STREAM_SYSTEM
    }
}
