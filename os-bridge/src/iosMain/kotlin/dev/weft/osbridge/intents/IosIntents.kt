package dev.weft.osbridge.intents

import dev.weft.contracts.Intents
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSCharacterSet
import platform.Foundation.NSURL
import platform.Foundation.URLQueryAllowedCharacterSet
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.UIKit.UIApplication
import kotlin.coroutines.resume

/**
 * iOS [Intents] via `UIApplication.openURL`. `openUrl` and `launchApp`
 * open the system handler for a URL / app scheme; `openMapsDirections`
 * builds an Apple Maps `maps.apple.com` URL. `openAlarmSet` returns
 * false — iOS exposes no public Clock URL scheme.
 *
 * `inApp` is best-effort: this impl always opens externally (an in-app
 * `SFSafariViewController` needs a host view controller to present
 * from, which the substrate doesn't own).
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosIntents : Intents {

    override suspend fun launchApp(target: String, payload: JsonObject?): Boolean =
        open(target)

    override suspend fun openUrl(url: String, inApp: Boolean): Boolean =
        open(url)

    override suspend fun openMapsDirections(to: String, from: String?, mode: String): Boolean {
        val dirflg = when (mode.lowercase()) {
            "walking" -> "w"
            "transit" -> "r"
            else -> "d"
        }
        val query = buildString {
            append("https://maps.apple.com/?daddr=").append(encode(to))
            if (from != null) append("&saddr=").append(encode(from))
            append("&dirflg=").append(dirflg)
        }
        return open(query)
    }

    override suspend fun openAlarmSet(hour: Int, minute: Int, label: String?): Boolean = false

    private suspend fun open(urlString: String): Boolean {
        val url = NSURL.URLWithString(urlString) ?: return false
        return withContext(Dispatchers.Main) {
            val app = UIApplication.sharedApplication
            if (!app.canOpenURL(url)) return@withContext false
            suspendCancellableCoroutine { cont ->
                app.openURL(url, options = emptyMap<Any?, Any?>()) { success ->
                    cont.resume(success)
                }
            }
        }
    }

    private fun encode(value: String): String =
        (value as platform.Foundation.NSString)
            .stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.URLQueryAllowedCharacterSet)
            ?: value
}
