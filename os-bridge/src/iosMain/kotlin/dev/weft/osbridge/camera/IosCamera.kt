package dev.weft.osbridge.camera

import dev.weft.contracts.Camera
import dev.weft.contracts.FileRef
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSFileManager
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS [Camera] backed by `UIImagePickerController` with `sourceType =
 * .camera`, presented from the top view controller. The captured
 * `UIImage` is JPEG-encoded and written into the Caches directory.
 *
 * Returns null (never throws) when the camera source is unavailable —
 * e.g. on the simulator, which has no camera — or when the user cancels.
 * The delegate is held in a local val for the operation's lifetime so it
 * isn't collected while the picker is on screen.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public open class IosCamera : Camera {

    override suspend fun captureImage(namePrefix: String): FileRef? = withContext(Dispatchers.Main) {
        if (!UIImagePickerController.isSourceTypeAvailable(
                UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera,
            )
        ) {
            return@withContext null
        }
        val presenter = topViewController() ?: return@withContext null

        suspendCancellableCoroutine { cont ->
            val picker = UIImagePickerController()
            picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera

            // Retained for the operation's lifetime: captured by this coroutine
            // frame and referenced by the picker as its delegate.
            val delegate = object :
                NSObject(),
                UIImagePickerControllerDelegateProtocol,
                UINavigationControllerDelegateProtocol {

                override fun imagePickerController(
                    picker: UIImagePickerController,
                    didFinishPickingMediaWithInfo: Map<Any?, *>,
                ) {
                    picker.dismissViewControllerAnimated(true, completion = null)
                    val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                    val ref = image?.let { writeJpeg(it, namePrefix) }
                    if (cont.isActive) cont.resume(ref)
                }

                override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                    picker.dismissViewControllerAnimated(true, completion = null)
                    if (cont.isActive) cont.resume(null)
                }
            }
            picker.delegate = delegate
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    private fun writeJpeg(image: UIImage, namePrefix: String): FileRef? {
        val data = UIImageJPEGRepresentation(image, JPEG_QUALITY) ?: return null
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return null
        val dir = "$caches/camera"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val path = "$dir/${sanitize(namePrefix)}-${NSUUID().UUIDString}.jpg"
        if (!data.writeToFile(path, atomically = true)) return null
        val uri = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
        return FileRef(uri = uri, sizeBytes = data.length.toLong())
    }

    private fun sanitize(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "photo" }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) {
            top = top.presentedViewController ?: break
        }
        return top
    }

    private companion object {
        const val JPEG_QUALITY = 0.9
    }
}
