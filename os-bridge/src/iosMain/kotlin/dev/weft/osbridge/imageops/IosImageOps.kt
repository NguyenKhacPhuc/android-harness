package dev.weft.osbridge.imageops

import dev.weft.contracts.FileRef
import dev.weft.contracts.ImageOps
import dev.weft.contracts.RectPx
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.drawInRect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * iOS [ImageOps] backed by `UIImage` + CoreGraphics. Loads the source
 * (file path or `file://` URL), applies the transform at pixel scale,
 * writes a PNG into the Caches directory, and returns a [FileRef] whose
 * `uri` is a `file://` URL.
 *
 * Pixel-exact: renders with a format scale of 1 so a point equals a
 * pixel, and reads source dimensions from the backing `CGImage`.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosImageOps : ImageOps {

    override suspend fun resize(uri: String, maxEdgePx: Int, namePrefix: String): FileRef? {
        val src = loadImage(uri) ?: return null
        val cg = src.CGImage ?: return null
        val srcW = CGImageGetWidth(cg).toInt()
        val srcH = CGImageGetHeight(cg).toInt()
        if (srcW <= 0 || srcH <= 0) return null
        val longest = max(srcW, srcH)
        val (w, h) = if (longest <= maxEdgePx) {
            srcW to srcH
        } else {
            val scale = maxEdgePx.toDouble() / longest.toDouble()
            (srcW * scale).roundToInt().coerceAtLeast(1) to (srcH * scale).roundToInt().coerceAtLeast(1)
        }
        val out = render(w.toDouble(), h.toDouble()) {
            src.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
        }
        return writeResult(out, namePrefix)
    }

    override suspend fun crop(uri: String, rect: RectPx, namePrefix: String): FileRef? {
        val src = loadImage(uri) ?: return null
        val cg = src.CGImage ?: return null
        val srcW = CGImageGetWidth(cg).toInt()
        val srcH = CGImageGetHeight(cg).toInt()
        if (rect.left < 0 || rect.top < 0 || rect.right > srcW || rect.bottom > srcH) return null
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        if (w <= 0 || h <= 0) return null
        val croppedCg = CGImageCreateWithImageInRect(
            cg,
            CGRectMake(rect.left.toDouble(), rect.top.toDouble(), w.toDouble(), h.toDouble()),
        ) ?: return null
        return writeResult(UIImage.imageWithCGImage(croppedCg), namePrefix)
    }

    override suspend fun rotate(uri: String, degrees: Int, namePrefix: String): FileRef? {
        val src = loadImage(uri) ?: return null
        val normalized = (((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN).let {
            ((it / QUARTER.toDouble()).roundToInt() * QUARTER) % FULL_TURN
        }
        val srcW = src.pixelWidth()
        val srcH = src.pixelHeight()
        if (normalized == 0) {
            val out = render(srcW, srcH) { src.drawInRect(CGRectMake(0.0, 0.0, srcW, srcH)) }
            return writeResult(out, namePrefix)
        }
        val cg = src.CGImage ?: return null
        val orientation = when (normalized) {
            QUARTER -> UIImageOrientation.UIImageOrientationRight
            QUARTER * 2 -> UIImageOrientation.UIImageOrientationDown
            else -> UIImageOrientation.UIImageOrientationLeft
        }
        val oriented = UIImage.imageWithCGImage(cg, scale = 1.0, orientation = orientation)
        val w = oriented.size.useContents { width }
        val h = oriented.size.useContents { height }
        val out = render(w, h) { oriented.drawInRect(CGRectMake(0.0, 0.0, w, h)) }
        return writeResult(out, namePrefix)
    }

    private fun UIImage.pixelWidth(): Double = CGImage?.let { CGImageGetWidth(it).toDouble() } ?: 0.0
    private fun UIImage.pixelHeight(): Double = CGImage?.let { CGImageGetHeight(it).toDouble() } ?: 0.0

    private fun render(widthPx: Double, heightPx: Double, draw: () -> Unit): UIImage {
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(widthPx, heightPx), format = format)
        return renderer.imageWithActions { draw() }
    }

    private fun loadImage(uri: String): UIImage? {
        val data: NSData? = if (uri.startsWith("file://")) {
            NSURL.URLWithString(uri)?.let { NSData.dataWithContentsOfURL(it) }
        } else {
            NSData.dataWithContentsOfFile(uri)
        }
        return data?.let { UIImage.imageWithData(it) }
    }

    private fun writeResult(image: UIImage, namePrefix: String): FileRef? {
        val data = UIImagePNGRepresentation(image) ?: return null
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return null
        val dir = "$caches/image-ops"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val path = "$dir/${sanitize(namePrefix)}-${NSUUID().UUIDString}.png"
        if (!data.writeToFile(path, atomically = true)) return null
        val outUri = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
        return FileRef(uri = outUri, sizeBytes = data.length.toLong())
    }

    private fun sanitize(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "img" }

    private companion object {
        const val FULL_TURN = 360
        const val QUARTER = 90
    }
}
