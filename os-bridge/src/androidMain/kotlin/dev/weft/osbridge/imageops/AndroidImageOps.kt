package dev.weft.osbridge.imageops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import dev.weft.contracts.FileRef
import dev.weft.contracts.ImageOps
import dev.weft.contracts.RectPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Android implementation of [ImageOps]. Loads the input via
 * [BitmapFactory] (works for any URI scheme [ContentResolver] can
 * resolve — content://, file://, FileProvider URIs from earlier
 * substrate calls), applies the transform, writes the result to the
 * app's cache, returns a FileProvider URI.
 *
 * Always writes JPEG at quality 90 — small enough for share, lossless
 * enough for OCR/barcode follow-ups. Apps that need PNG or other
 * formats can implement their own ImageOps.
 */
class AndroidImageOps(context: Context) : ImageOps {
    private val appContext: Context = context.applicationContext
    private val authority: String = "${appContext.packageName}.fileprovider"

    override suspend fun resize(uri: String, maxEdgePx: Int, namePrefix: String): FileRef? =
        withContext(Dispatchers.IO) {
            val src = decode(uri) ?: return@withContext null
            try {
                val longest = maxOf(src.width, src.height).coerceAtLeast(1)
                if (longest <= maxEdgePx) {
                    // Already small enough — just re-encode.
                    save(src, namePrefix)
                } else {
                    val scale = maxEdgePx.toFloat() / longest.toFloat()
                    val w = (src.width * scale).toInt().coerceAtLeast(1)
                    val h = (src.height * scale).toInt().coerceAtLeast(1)
                    val out = Bitmap.createScaledBitmap(src, w, h, true)
                    save(out, namePrefix).also { if (out !== src) out.recycle() }
                }
            } finally {
                src.recycle()
            }
        }

    override suspend fun crop(uri: String, rect: RectPx, namePrefix: String): FileRef? =
        withContext(Dispatchers.IO) {
            val src = decode(uri) ?: return@withContext null
            try {
                val left = rect.left.coerceIn(0, src.width)
                val top = rect.top.coerceIn(0, src.height)
                val right = rect.right.coerceIn(left, src.width)
                val bottom = rect.bottom.coerceIn(top, src.height)
                val w = right - left
                val h = bottom - top
                if (w <= 0 || h <= 0) return@withContext null
                val out = Bitmap.createBitmap(src, left, top, w, h)
                save(out, namePrefix).also { if (out !== src) out.recycle() }
            } finally {
                src.recycle()
            }
        }

    override suspend fun rotate(uri: String, degrees: Int, namePrefix: String): FileRef? =
        withContext(Dispatchers.IO) {
            val src = decode(uri) ?: return@withContext null
            try {
                val snapped = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
                // Snap to nearest 90.
                val normalized = ((snapped + QUARTER_HALF) / QUARTER_TURN) * QUARTER_TURN % FULL_TURN
                if (normalized == 0) return@withContext save(src, namePrefix)
                val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
                val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                save(out, namePrefix).also { if (out !== src) out.recycle() }
            } finally {
                src.recycle()
            }
        }

    private fun decode(uri: String): Bitmap? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        return runCatching {
            appContext.contentResolver.openInputStream(parsed)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    private fun save(bitmap: Bitmap, namePrefix: String): FileRef? {
        val dir = File(appContext.cacheDir, "image-ops").apply { mkdirs() }
        val out = File(dir, "${sanitize(namePrefix)}-${System.currentTimeMillis()}.jpg")
        val ok = runCatching {
            FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }
        }.getOrDefault(false)
        if (!ok || !out.exists()) {
            out.delete()
            return null
        }
        val uri = runCatching { FileProvider.getUriForFile(appContext, authority, out) }
            .getOrNull() ?: return null
        return FileRef(uri = uri.toString(), sizeBytes = out.length())
    }

    private fun sanitize(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "img" }

    private companion object {
        const val JPEG_QUALITY = 90
        const val FULL_TURN = 360
        const val QUARTER_TURN = 90
        const val QUARTER_HALF = 45
    }
}
