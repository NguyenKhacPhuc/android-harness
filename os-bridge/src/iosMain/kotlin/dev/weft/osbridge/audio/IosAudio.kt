package dev.weft.osbridge.audio

import dev.weft.contracts.Audio
import dev.weft.contracts.FileRef

/**
 * iOS stub for [Audio]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `AVFoundation.AVAudioRecorder` —
 * configure with AAC settings (`AVFormatIDKey: kAudioFormatMPEG4AAC`)
 * pointing at a Caches-directory URL, `record(forDuration:)` for
 * fixed-length capture or `record()` + manual `stop()`. Requires
 * `AVAudioSession.sharedInstance().setCategory(.playAndRecord)` and the
 * `NSMicrophoneUsageDescription` Info.plist key.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosAudio : Audio {
    override suspend fun record(maxDurationMs: Long, namePrefix: String): FileRef? =
        TODO("IosAudio.record — wrap AVAudioRecorder.record(forDuration:) writing AAC into Caches/")

    override suspend fun stop(): Unit =
        TODO("IosAudio.stop — wrap AVAudioRecorder.stop()")
}
