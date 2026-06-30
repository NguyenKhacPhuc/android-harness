package dev.weft.osbridge.speech

import dev.weft.contracts.Speech
import dev.weft.contracts.SpeechRecognitionResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * iOS [Speech]. [say] / [stop] drive `AVSpeechSynthesizer` (text-to-speech);
 * the synthesizer is held as a field so it survives across calls, and a
 * retained `AVSpeechSynthesizerDelegate` subclass resumes the suspending
 * [say] on the synthesizer's finish/cancel callback.
 *
 * [recognize] is an honest null: live microphone speech-to-text needs
 * `AVAudioEngine` / `AVAudioSession`, which can't be bound under the current
 * Kotlin/Native + iOS SDK cinterop (the AVAudioSession binding gap). A host
 * that needs STT must wire `SFSpeechRecognizer` from Swift and inject it.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosSpeech : Speech {

    private val synthesizer = AVSpeechSynthesizer()

    // Retained for the synthesizer's lifetime — ObjC holds the delegate weakly,
    // so dropping this reference would silently stop the finish/cancel callbacks.
    private val delegate = SpeechDelegate()

    init {
        synthesizer.delegate = delegate
    }

    override suspend fun say(text: String, locale: String?): Boolean {
        val utterance = AVSpeechUtterance(string = text)
        if (locale != null) {
            utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(locale)
        }
        return suspendCancellableCoroutine { cont ->
            delegate.register(utterance, cont)
            cont.invokeOnCancellation { delegate.unregister(utterance) }
            // speakUtterance must run on the main thread; hop there without
            // suspending the continuation we just registered.
            withContextMain { synthesizer.speakUtterance(utterance) }
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
    }

    /**
     * Honest null — live microphone STT needs AVAudioEngine / AVAudioSession,
     * which is blocked by the Kotlin/Native AVAudioSession cinterop binding gap
     * on this toolchain. Hosts inject an SFSpeechRecognizer-backed impl instead.
     */
    override suspend fun recognize(locale: String?, maxDurationMs: Long): SpeechRecognitionResult? = null

    private fun withContextMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue(), block)
    }
}

/**
 * Retained synthesizer delegate. Resumes each utterance's continuation once,
 * on whichever terminal callback (finish or cancel) the synthesizer reports.
 */
@OptIn(ExperimentalForeignApi::class)
private class SpeechDelegate : NSObject(), AVSpeechSynthesizerDelegateProtocol {

    private val pending = mutableMapOf<AVSpeechUtterance, Continuation<Boolean>>()

    fun register(utterance: AVSpeechUtterance, cont: Continuation<Boolean>) {
        pending[utterance] = cont
    }

    fun unregister(utterance: AVSpeechUtterance) {
        pending.remove(utterance)
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didFinishSpeechUtterance: AVSpeechUtterance,
    ) {
        pending.remove(didFinishSpeechUtterance)?.resume(true)
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        didCancelSpeechUtterance: AVSpeechUtterance,
    ) {
        pending.remove(didCancelSpeechUtterance)?.resume(true)
    }
}
