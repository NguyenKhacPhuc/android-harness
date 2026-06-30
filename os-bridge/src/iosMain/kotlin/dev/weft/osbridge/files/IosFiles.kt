package dev.weft.osbridge.files

import dev.weft.contracts.FileContent
import dev.weft.contracts.FileRef
import dev.weft.contracts.FileSaveSpec
import dev.weft.contracts.Files
import dev.weft.contracts.ShareTarget
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import kotlin.coroutines.resume

/**
 * iOS [Files] backed by `NSFileManager` + `NSData`. Writes land under the
 * Caches directory (or a caller-supplied subdirectory), reads accept both
 * `file://` URLs and bare paths (mirroring [dev.weft.osbridge.imageops.IosImageOps]),
 * and [share] hands the file to a `UIActivityViewController` from the top
 * view controller.
 *
 * mimeType on [read] is guessed from the file extension — iOS has no
 * content-resolver to ask, so it's a best-effort lookup, not authoritative.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public open class IosFiles : Files {

    override suspend fun save(spec: FileSaveSpec): FileRef = withContext(Dispatchers.Default) {
        val dir = spec.directory?.takeIf { it.isNotBlank() } ?: "${cachesDir()}/files"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val path = "$dir/${spec.name?.takeIf { it.isNotBlank() } ?: NSUUID().UUIDString}"

        when {
            spec.text != null -> {
                (spec.text as NSString).writeToFile(
                    path,
                    atomically = true,
                    encoding = NSUTF8StringEncoding,
                    error = null,
                )
            }
            spec.contentBase64 != null -> {
                NSData.create(base64EncodedString = spec.contentBase64!!, options = 0uL)
                    ?.writeToFile(path, atomically = true)
            }
            else -> {
                NSData().writeToFile(path, atomically = true)
            }
        }

        val uri = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
        FileRef(uri = uri, sizeBytes = fileSize(path))
    }

    override suspend fun read(uri: String, asBase64: Boolean): FileContent = withContext(Dispatchers.Default) {
        val data = loadData(uri) ?: NSData()
        val mimeType = guessMimeType(uri)
        if (asBase64) {
            FileContent(
                base64 = data.base64EncodedStringWithOptions(0uL),
                mimeType = mimeType,
                sizeBytes = data.length.toLong(),
            )
        } else {
            val text = NSString.create(data, NSUTF8StringEncoding) as String?
            FileContent(
                text = text,
                mimeType = mimeType,
                sizeBytes = data.length.toLong(),
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun share(uri: String, target: ShareTarget): Boolean = withContext(Dispatchers.Main) {
        val url = if (uri.startsWith("file://")) NSURL.URLWithString(uri) else NSURL.fileURLWithPath(uri)
        url ?: return@withContext false
        val presenter = topViewController() ?: return@withContext false
        suspendCancellableCoroutine { cont ->
            val controller = UIActivityViewController(
                activityItems = listOf(url),
                applicationActivities = null,
            )
            controller.completionWithItemsHandler = { _, completed, _, _ ->
                if (cont.isActive) cont.resume(completed)
            }
            // iPad requires a popover anchor or the present call crashes.
            controller.popoverPresentationController?.sourceView = presenter.view
            presenter.presentViewController(controller, animated = true, completion = null)
        }
    }

    private fun loadData(uri: String): NSData? =
        if (uri.startsWith("file://")) {
            NSURL.URLWithString(uri)?.let { NSData.dataWithContentsOfURL(it) }
        } else {
            NSData.dataWithContentsOfFile(uri)
        }

    private fun cachesDir(): String =
        NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: "."

    private fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return 0L
        val size = attrs[NSFileSize] as? Number ?: return 0L
        return size.toLong()
    }

    private fun guessMimeType(uri: String): String {
        val ext = uri.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) {
            top = top.presentedViewController ?: break
        }
        return top
    }
}
