package dev.weft.osbridge.sharing

import dev.weft.contracts.ShareContent
import dev.weft.contracts.ShareTarget
import dev.weft.contracts.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import kotlin.coroutines.resume

/**
 * iOS [Sharing] via `UIActivityViewController`, presented from the
 * top-most view controller of the key window. Returns true when the
 * user completes a share action, false when they dismiss the sheet.
 *
 * `ShareTarget.SpecificApp` has no public iOS analogue (the OS owns
 * routing), so it falls back to the system share sheet.
 *
 * Open so hosts can subclass and override individual methods.
 */
public open class IosSharing : Sharing {

    @Suppress("UNUSED_PARAMETER")
    override suspend fun share(content: ShareContent, target: ShareTarget): Boolean {
        val items = activityItems(content)
        if (items.isEmpty()) return false
        return withContext(Dispatchers.Main) {
            val presenter = topViewController() ?: return@withContext false
            suspendCancellableCoroutine { cont ->
                val controller = UIActivityViewController(
                    activityItems = items,
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
    }

    private fun activityItems(content: ShareContent): List<Any> = buildList {
        content.text?.let { add(it) }
        content.url?.let { NSURL.URLWithString(it)?.let(::add) }
        content.fileUri?.let { uri ->
            val url = if (uri.startsWith("file://")) NSURL.URLWithString(uri) else NSURL.fileURLWithPath(uri)
            url?.let(::add)
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
