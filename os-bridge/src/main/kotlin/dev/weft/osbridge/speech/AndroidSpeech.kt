package dev.weft.osbridge.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import dev.weft.contracts.Speech
import dev.weft.contracts.SpeechRecognitionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Android implementation of [Speech] using [TextToSpeech].
 *
 * TTS init is async — the constructor returns immediately and the engine
 * fires `onInit(status)` later. We expose readiness via a
 * [CompletableDeferred] callers await before queueing an utterance, so
 * the first [say] is a single suspend point that resolves once the
 * engine is ready.
 *
 * Each utterance is identified by a monotonic id so [UtteranceProgressListener]
 * can route completion back to exactly the suspending caller that queued
 * it (rather than fan-in across all callers).
 */
class AndroidSpeech(context: Context) : Speech {

    private val appContext: Context = context.applicationContext

    private val tts: TextToSpeech

    /** Resolves true when init succeeded, false when the engine reported failure. */
    private val ready: CompletableDeferred<Boolean> = CompletableDeferred()

    private val nextId = AtomicLong(0)

    /** id → completion deferred. Listener notifies each by id. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        tts = TextToSpeech(appContext) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.complete(true)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.complete(false)
            }
            override fun onError(utteranceId: String, errorCode: Int) {
                pending.remove(utteranceId)?.complete(false)
            }
        })
    }

    override suspend fun say(text: String, locale: String?): Boolean {
        if (text.isBlank()) return false
        // Bound init time so a busted TTS service can't hang the agent loop.
        val initOk = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } == true
        if (!initOk) return false

        // Best-effort locale set. If the requested locale isn't supported,
        // fall back to the device default.
        val target = locale?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        if (tts.isLanguageAvailable(target) >= TextToSpeech.LANG_AVAILABLE) {
            tts.language = target
        }

        val id = "utt-${nextId.incrementAndGet()}"
        val done = CompletableDeferred<Boolean>()
        pending[id] = done

        val params = android.os.Bundle()
        val queued = tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { pending.remove(id) }
            done.invokeOnCompletion { t ->
                if (!cont.isActive) return@invokeOnCompletion
                cont.resume(if (t == null) done.getCompleted() else false)
            }
        }
    }

    override suspend fun stop() {
        if (!ready.isCompleted || ready.getCompleted() != true) return
        tts.stop()
        // Resume any pending awaiters as failed — they were interrupted.
        pending.values.toList().forEach { it.complete(false) }
        pending.clear()
    }

    override suspend fun recognize(
        locale: String?,
        maxDurationMs: Long,
    ): SpeechRecognitionResult? {
        if (!hasMicPermission()) return null
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return null

        // SpeechRecognizer must be created on the main thread and the
        // listener callbacks fire there too. We hop onto Main, wire
        // everything up, then await the completion deferred on whatever
        // dispatcher the caller is on.
        return withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            val result = CompletableDeferred<SpeechRecognitionResult?>()

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    result.complete(null)
                }
                override fun onResults(bundle: Bundle?) {
                    val matches = bundle?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                    ).orEmpty()
                    val scores = bundle?.getFloatArray(
                        SpeechRecognizer.CONFIDENCE_SCORES,
                    )
                    val best = matches.firstOrNull()
                    if (best.isNullOrBlank()) {
                        result.complete(null)
                    } else {
                        result.complete(
                            SpeechRecognitionResult(
                                text = best,
                                confidence = scores?.firstOrNull(),
                                alternatives = matches.drop(1),
                            ),
                        )
                    }
                }
                override fun onPartialResults(bundle: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, RECOGNITION_MAX_RESULTS)
                val tag = locale ?: Locale.getDefault().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            try {
                recognizer.startListening(intent)
            } catch (t: Throwable) {
                runCatching { recognizer.destroy() }
                return@withContext null
            }

            val outcome = withTimeoutOrNull(maxDurationMs) { result.await() }
            // Stop + tear down regardless of outcome. destroy() also
            // releases the bound system service.
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.destroy() }
            outcome
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val INIT_TIMEOUT_MS = 3_000L
        const val RECOGNITION_MAX_RESULTS = 3
    }
}
