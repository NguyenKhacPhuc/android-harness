package dev.weft.osbridge.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.weft.contracts.Audio
import dev.weft.contracts.FileRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android implementation of [Audio]. Records to a file in the app's
 * cache directory and exposes it via the substrate's FileProvider so the
 * caller gets back a `content://` URI usable with [Sharing] and [Files].
 *
 * Encoding choices: AAC (LC) inside an MP4 container, mono, 44.1kHz, 64kbps.
 * Good enough for voice (the dominant assistant use case) without bloating
 * file size.
 */
class AndroidAudio(context: Context) : Audio {

    private val appContext: Context = context.applicationContext
    private val authority: String = "${appContext.packageName}.fileprovider"

    @Volatile private var current: MediaRecorder? = null

    override suspend fun record(maxDurationMs: Long, namePrefix: String): FileRef? {
        if (!hasMicrophonePermission()) return null
        if (current != null) return null // another recording in flight

        return withContext(Dispatchers.IO) {
            val cacheDir = File(appContext.cacheDir, "audio").apply { mkdirs() }
            val outFile = File(cacheDir, "${sanitize(namePrefix)}-${System.currentTimeMillis()}.m4a")
            val recorder = createRecorder()
            try {
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    setAudioSamplingRate(SAMPLE_RATE_HZ)
                    setAudioEncodingBitRate(BITRATE_BPS)
                    setOutputFile(outFile.absolutePath)
                    if (maxDurationMs > 0) setMaxDuration(maxDurationMs.toInt().coerceAtMost(Int.MAX_VALUE))
                    prepare()
                    start()
                }
                current = recorder

                // Wait either until the duration elapses or stop() is called.
                // The MediaRecorder fires onInfo(MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)
                // when the cap hits, but it also auto-stops — we mirror that
                // by polling here on a short tick. Polling is fine for the
                // ≤max-15min duration agents will use.
                val deadline = System.currentTimeMillis() + maxDurationMs
                while (current === recorder && System.currentTimeMillis() < deadline) {
                    delay(POLL_INTERVAL_MS)
                }
            } catch (t: Throwable) {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                current = null
                outFile.delete()
                return@withContext null
            }

            // Finalize. If stop() was called externally, current is already null.
            if (current === recorder) {
                runCatching { recorder.stop() }
                current = null
            }
            runCatching { recorder.release() }

            if (!outFile.exists() || outFile.length() == 0L) {
                outFile.delete()
                return@withContext null
            }
            val uri = FileProvider.getUriForFile(appContext, authority, outFile)
            FileRef(uri = uri.toString(), sizeBytes = outFile.length())
        }
    }

    override suspend fun stop() {
        val r = current ?: return
        current = null
        withContext(Dispatchers.IO) { runCatching { r.stop() } }
    }

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun sanitize(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "rec" }

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val BITRATE_BPS = 64_000
        const val POLL_INTERVAL_MS = 100L
    }
}
