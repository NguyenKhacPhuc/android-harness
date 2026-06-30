package dev.weft.osbridge.mediapicker

import dev.weft.contracts.MediaPicker
import dev.weft.contracts.MediaPickerKind
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlin.coroutines.resume

/**
 * iOS [MediaPicker] backed by `PHPickerViewController` (PhotosUI),
 * presented from the top view controller. Permissionless — the picker
 * mediates access, so no Photos authorization is requested.
 *
 * Each selected `PHPickerResult` loads its data representation
 * asynchronously and in parallel; we count completions under a mutex and
 * resume once every load has finished. Cancellation yields an empty list.
 * The delegate is held for the operation's lifetime.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public open class IosMediaPicker : MediaPicker {

    override suspend fun pick(kind: MediaPickerKind, maxItems: Int): List<String> = withContext(Dispatchers.Main) {
        val presenter = topViewController() ?: return@withContext emptyList()

        val config = PHPickerConfiguration()
        config.selectionLimit = maxItems.coerceAtLeast(0).toLong()
        filterFor(kind)?.let { config.filter = it }

        suspendCancellableCoroutine { cont ->
            val picker = PHPickerViewController(configuration = config)

            val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
                override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                    picker.dismissViewControllerAnimated(true, completion = null)

                    @Suppress("UNCHECKED_CAST")
                    val results = didFinishPicking as List<PHPickerResult>
                    if (results.isEmpty()) {
                        if (cont.isActive) cont.resume(emptyList())
                        return
                    }

                    val uris = mutableListOf<String>()
                    val lock = nativeHeap.alloc<pthread_mutex_t>()
                    pthread_mutex_init(lock.ptr, null)
                    var completed = 0
                    val total = results.size

                    fun finishOne(uri: String?) {
                        pthread_mutex_lock(lock.ptr)
                        uri?.let { uris += it }
                        completed += 1
                        val done = completed >= total
                        val snapshot = if (done) uris.toList() else emptyList()
                        pthread_mutex_unlock(lock.ptr)
                        if (done) {
                            pthread_mutex_destroy(lock.ptr)
                            nativeHeap.free(lock.ptr)
                            if (cont.isActive) cont.resume(snapshot)
                        }
                    }

                    results.forEach { result ->
                        val provider = result.itemProvider
                        // Pick the type the provider actually carries so a movie
                        // in a mixed selection isn't requested as an image.
                        val typeId = if (provider.hasItemConformingToTypeIdentifier(TYPE_MOVIE)) {
                            TYPE_MOVIE
                        } else {
                            TYPE_IMAGE
                        }
                        provider.loadDataRepresentationForTypeIdentifier(typeId) { data: NSData?, _: NSError? ->
                            finishOne(data?.let { writeData(it, typeId) })
                        }
                    }
                }
            }
            picker.delegate = delegate
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    private fun writeData(data: NSData, typeId: String): String? {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return null
        val dir = "$caches/picked"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val ext = if (typeId == TYPE_MOVIE) "mov" else "jpg"
        val path = "$dir/${NSUUID().UUIDString}.$ext"
        if (!data.writeToFile(path, atomically = true)) return null
        return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
    }

    private fun filterFor(kind: MediaPickerKind): PHPickerFilter? = when (kind) {
        MediaPickerKind.IMAGE -> PHPickerFilter.imagesFilter()
        MediaPickerKind.VIDEO -> PHPickerFilter.videosFilter()
        // null filter shows all supported media (images + videos).
        MediaPickerKind.IMAGE_OR_VIDEO -> null
    }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) {
            top = top.presentedViewController ?: break
        }
        return top
    }

    private companion object {
        const val TYPE_IMAGE = "public.image"
        const val TYPE_MOVIE = "public.movie"
    }
}
