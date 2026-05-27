package dev.weft.osbridge.medialibrary

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.weft.contracts.MediaFilter
import dev.weft.contracts.MediaItem
import dev.weft.contracts.MediaKind
import dev.weft.contracts.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [MediaLibrary] backed by [MediaStore]. Each
 * [MediaKind] maps to its dedicated content URI: images → `Images.Media`,
 * videos → `Video.Media`, audio → `Audio.Media`. Results from multiple
 * kinds are merged and re-sorted by `DATE_ADDED` desc.
 *
 * Permission semantics handled at the tool gate. This class never throws
 * on permission denial — `query()` simply returns an empty list, which
 * lets the tool layer surface a clean "no media accessible" message
 * instead of a stack trace.
 */
public class AndroidMediaLibrary(context: Context) : MediaLibrary {
    private val appContext: Context = context.applicationContext

    override suspend fun listRecent(kinds: Set<MediaKind>, limit: Int): List<MediaItem> =
        query(MediaFilter(kinds = kinds, limit = limit))

    override suspend fun query(filter: MediaFilter): List<MediaItem> = withContext(Dispatchers.IO) {
        if (filter.kinds.isEmpty()) return@withContext emptyList()
        val cap = filter.limit.coerceIn(1, MediaLibrary.LIST_LIMIT_MAX)
        val all = mutableListOf<MediaItem>()
        for (kind in filter.kinds) {
            runCatching { all += queryKind(kind, filter, cap) }
        }
        all.sortedByDescending { it.dateAddedEpochMs ?: 0L }.take(cap)
    }

    private fun queryKind(kind: MediaKind, filter: MediaFilter, cap: Int): List<MediaItem> {
        val (collection, columns) = collectionFor(kind)

        val where = buildString {
            val clauses = mutableListOf<String>()
            filter.sinceEpochMs?.let { clauses += "${MediaStore.MediaColumns.DATE_ADDED} >= ?" }
            filter.untilEpochMs?.let { clauses += "${MediaStore.MediaColumns.DATE_ADDED} <= ?" }
            filter.nameContains?.takeIf { it.isNotBlank() }?.let {
                clauses += "LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE ?"
            }
            if (clauses.isNotEmpty()) append(clauses.joinToString(" AND "))
        }.ifBlank { null }

        val args = buildList {
            filter.sinceEpochMs?.let { add((it / SECONDS_PER_MS).toString()) }
            filter.untilEpochMs?.let { add((it / SECONDS_PER_MS).toString()) }
            filter.nameContains?.takeIf { it.isNotBlank() }
                ?.let { add("%${it.lowercase()}%") }
        }.toTypedArray().ifEmpty { null }

        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $cap"

        val cursor = appContext.contentResolver.query(
            collection,
            columns,
            where,
            args,
            sortOrder,
        ) ?: return emptyList()

        val items = mutableListOf<MediaItem>()
        cursor.use { c ->
            val idx = ColumnIndex(c, kind)
            while (c.moveToNext()) {
                val id = c.getLong(idx.id)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                items += MediaItem(
                    uri = uri,
                    kind = kind,
                    displayName = idx.displayName?.let { c.getStringOrNull(it) },
                    mimeType = idx.mime?.let { c.getStringOrNull(it) },
                    sizeBytes = idx.size?.let { c.getLongOrZero(it) },
                    dateAddedEpochMs = idx.dateAdded?.let { c.getLongOrZero(it) * SECONDS_PER_MS },
                    widthPx = idx.width?.let { c.getIntOrNull(it) },
                    heightPx = idx.height?.let { c.getIntOrNull(it) },
                    durationMs = idx.duration?.let { c.getLongOrZero(it) },
                )
            }
        }
        return items
    }

    private fun collectionFor(kind: MediaKind): Pair<Uri, Array<String>> = when (kind) {
        MediaKind.IMAGE -> imagesCollection() to IMAGE_COLUMNS
        MediaKind.VIDEO -> videosCollection() to VIDEO_COLUMNS
        MediaKind.AUDIO -> audioCollection() to AUDIO_COLUMNS
    }

    private fun imagesCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun videosCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private class ColumnIndex(c: android.database.Cursor, kind: MediaKind) {
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val displayName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME).positive()
        val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE).positive()
        val size = c.getColumnIndex(MediaStore.MediaColumns.SIZE).positive()
        val dateAdded = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED).positive()
        val width = if (kind != MediaKind.AUDIO) {
            c.getColumnIndex(MediaStore.MediaColumns.WIDTH).positive()
        } else null
        val height = if (kind != MediaKind.AUDIO) {
            c.getColumnIndex(MediaStore.MediaColumns.HEIGHT).positive()
        } else null
        val duration = if (kind != MediaKind.IMAGE) {
            c.getColumnIndex(MediaStore.MediaColumns.DURATION).positive()
        } else null

        private fun Int.positive(): Int? = if (this >= 0) this else null
    }

    private fun android.database.Cursor.getStringOrNull(idx: Int): String? =
        if (isNull(idx)) null else getString(idx)

    private fun android.database.Cursor.getIntOrNull(idx: Int): Int? =
        if (isNull(idx)) null else getInt(idx).takeIf { it > 0 }

    private fun android.database.Cursor.getLongOrZero(idx: Int): Long =
        if (isNull(idx)) 0L else getLong(idx)

    private companion object {
        // MediaStore stores DATE_ADDED in seconds; we expose epoch ms.
        const val SECONDS_PER_MS = 1_000L

        val IMAGE_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        val VIDEO_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )
        val AUDIO_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
        )
    }
}
