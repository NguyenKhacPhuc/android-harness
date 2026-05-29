package dev.weft.osbridge.volume

import dev.weft.contracts.Volume
import dev.weft.contracts.VolumeStream

/**
 * iOS stub for [Volume]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `AVFAudio.AVAudioSession.sharedInstance().outputVolume`
 * for read (system media volume, 0..1). iOS deliberately does NOT
 * expose programmatic volume setting — the only public path is showing
 * an `MPVolumeView` (deprecated cosmetic slider) and letting the user
 * drag it. [set] should likely return false on iOS for everything
 * except as a no-op. Per-stream split (RING / NOTIFICATION / ALARM)
 * doesn't map cleanly to iOS — there's one media volume and one
 * ringer volume, and the ringer isn't readable from a third-party app.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosVolume : Volume {
    override suspend fun get(stream: VolumeStream): Float =
        TODO("IosVolume.get — wrap AVAudioSession.sharedInstance().outputVolume (MEDIA only; other streams not exposed)")

    override suspend fun set(stream: VolumeStream, normalized: Float): Boolean =
        TODO("IosVolume.set — iOS has no public programmatic volume setter; return false")
}
