package dev.weft.osbridge.medialibrary

import dev.weft.contracts.MediaFilter
import dev.weft.contracts.MediaItem
import dev.weft.contracts.MediaKind
import dev.weft.contracts.MediaLibrary
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSSortDescriptor
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaType
import platform.Photos.PHAssetMediaTypeAudio
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

/**
 * iOS [MediaLibrary] backed by the Photos framework (`PHAsset`). Requests
 * read authorization once; returns an empty list (never throws) when the
 * user declines or the status is restricted.
 *
 * Each item's `uri` is `ph://<localIdentifier>` — a stable Photos id, NOT
 * a file path. It is resolvable via `PHImageManager` / `PHAssetResource`,
 * not directly by files_read. Audio is unsupported by Photos (it indexes
 * images + video only), so AUDIO kinds yield nothing here.
 *
 * `nameContains` is not applied: PHAsset has no cheap display-name field
 * (the original filename lives behind a `PHAssetResource` lookup per
 * asset), so name filtering is skipped rather than paid for.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosMediaLibrary : MediaLibrary {

    override suspend fun listRecent(kinds: Set<MediaKind>, limit: Int): List<MediaItem> =
        query(MediaFilter(kinds = kinds, limit = limit))

    override suspend fun query(filter: MediaFilter): List<MediaItem> = withContext(Dispatchers.Default) {
        if (filter.kinds.isEmpty()) return@withContext emptyList()
        if (!requestAuthorization()) return@withContext emptyList()

        val cap = filter.limit.coerceIn(1, MediaLibrary.LIST_LIMIT_MAX)
        val options = PHFetchOptions().apply {
            sortDescriptors = listOf(
                NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false),
            )
            fetchLimit = cap.toULong()
        }

        val all = mutableListOf<MediaItem>()
        for (kind in filter.kinds) {
            val mediaType = mediaTypeFor(kind) ?: continue
            val fetch = PHAsset.fetchAssetsWithMediaType(mediaType, options)
            fetch.enumerateObjectsUsingBlock { obj, _, _ ->
                val asset = obj as? PHAsset ?: return@enumerateObjectsUsingBlock
                all += asset.toMediaItem(kind)
            }
        }
        all.sortedByDescending { it.dateAddedEpochMs ?: 0L }.take(cap)
    }

    private fun PHAsset.toMediaItem(kind: MediaKind): MediaItem {
        val createdMs = creationDate
            ?.let { (it.timeIntervalSince1970 * MILLIS_PER_SECOND).toLong() }
        val durationMs = if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) {
            (duration * MILLIS_PER_SECOND).toLong()
        } else {
            null
        }
        return MediaItem(
            uri = "ph://$localIdentifier",
            kind = kind,
            displayName = null,
            mimeType = null,
            sizeBytes = null,
            dateAddedEpochMs = createdMs,
            widthPx = pixelWidth.toInt(),
            heightPx = pixelHeight.toInt(),
            durationMs = durationMs,
        )
    }

    private fun mediaTypeFor(kind: MediaKind): PHAssetMediaType? = when (kind) {
        MediaKind.IMAGE -> PHAssetMediaTypeImage
        MediaKind.VIDEO -> PHAssetMediaTypeVideo
        MediaKind.AUDIO -> PHAssetMediaTypeAudio
    }

    private suspend fun requestAuthorization(): Boolean = suspendCancellableCoroutine { cont ->
        PHPhotoLibrary.requestAuthorization { status ->
            val ok = status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited
            if (cont.isActive) cont.resume(ok)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
