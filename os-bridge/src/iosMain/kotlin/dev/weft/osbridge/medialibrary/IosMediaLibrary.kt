package dev.weft.osbridge.medialibrary

import dev.weft.contracts.MediaFilter
import dev.weft.contracts.MediaItem
import dev.weft.contracts.MediaKind
import dev.weft.contracts.MediaLibrary

/**
 * iOS stub for [MediaLibrary]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `Photos.PHAsset.fetchAssets(with:options:)`
 * for IMAGE / VIDEO (`PHFetchOptions` with sort + predicate for the
 * filter), and `MediaPlayer.MPMediaQuery.songs()` for AUDIO (the iOS
 * music library is a separate framework from Photos). Convert each
 * asset to a stable identifier URI (`ph://<localIdentifier>`) the
 * substrate can hand back to other tools.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosMediaLibrary : MediaLibrary {
    override suspend fun listRecent(kinds: Set<MediaKind>, limit: Int): List<MediaItem> =
        TODO("IosMediaLibrary.listRecent — wrap PHAsset.fetchAssets(with:options:) sorted by creationDate desc")

    override suspend fun query(filter: MediaFilter): List<MediaItem> =
        TODO("IosMediaLibrary.query — wrap PHAsset.fetchAssets(with:options:) with PHFetchOptions.predicate for date + name filters")
}
