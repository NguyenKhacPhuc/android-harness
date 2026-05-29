package dev.weft.osbridge.speech

import dev.weft.contracts.Speech
import dev.weft.contracts.SpeechRecognitionResult

/**
 * iOS stub for [Speech]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `AVFoundation.AVSpeechSynthesizer` —
 * `speak(AVSpeechUtterance(string:))` for say, `stopSpeaking(at: .immediate)`
 * for stop. For recognize: `Speech.SFSpeechRecognizer` +
 * `SFSpeechAudioBufferRecognitionRequest` driven by an `AVAudioEngine`
 * input tap. Requires `NSSpeechRecognitionUsageDescription` +
 * `NSMicrophoneUsageDescription` Info.plist keys.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosSpeech : Speech {
    override suspend fun say(text: String, locale: String?): Boolean =
        TODO("IosSpeech.say — wrap AVSpeechSynthesizer.speak(AVSpeechUtterance(string:))")

    override suspend fun stop(): Unit =
        TODO("IosSpeech.stop — wrap AVSpeechSynthesizer.stopSpeaking(at: .immediate)")

    override suspend fun recognize(locale: String?, maxDurationMs: Long): SpeechRecognitionResult? =
        TODO("IosSpeech.recognize — wrap SFSpeechRecognizer + SFSpeechAudioBufferRecognitionRequest driven by AVAudioEngine")
}
