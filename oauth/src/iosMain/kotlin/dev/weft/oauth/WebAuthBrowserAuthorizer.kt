package dev.weft.oauth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS [BrowserAuthorizer] backed by `ASWebAuthenticationSession`. The
 * system intercepts the redirect's URL scheme and hands it straight back
 * through the session's completion handler, so — unlike Android — there's
 * no host-side callback channel and [expectedState] isn't needed to
 * select a redirect (the session is 1:1 with the authorization).
 *
 * The redirect scheme is derived from [redirectUri] (the part before
 * `://`); the host must register it as a URL type in its app.
 *
 * The only iOS-specific step of the flow — everything else (PKCE, URL
 * building, redirect validation, token exchange) is shared in commonMain
 * ([DefaultOAuthClient]).
 */
@OptIn(ExperimentalForeignApi::class)
internal class WebAuthBrowserAuthorizer : BrowserAuthorizer {

    override suspend fun authorize(
        authorizeUrl: String,
        redirectUri: String,
        expectedState: String,
    ): BrowserResult {
        val callbackScheme = redirectUri.substringBefore("://")
        val callback = launchWebAuth(authorizeUrl, callbackScheme) ?: return BrowserResult.Cancelled
        return BrowserResult.Redirect(queryParams(callback))
    }

    private suspend fun launchWebAuth(url: String, callbackScheme: String): NSURL? =
        withContext(Dispatchers.Main) {
            val authUrl = NSURL.URLWithString(url) ?: return@withContext null
            suspendCancellableCoroutine { cont ->
                val provider = AnchorProvider()
                val session = ASWebAuthenticationSession(
                    uRL = authUrl,
                    callbackURLScheme = callbackScheme,
                    completionHandler = { callbackUrl: NSURL?, error: NSError? ->
                        if (cont.isActive) cont.resume(if (error != null) null else callbackUrl)
                    },
                )
                session.presentationContextProvider = provider
                cont.invokeOnCancellation { session.cancel() }
                if (!session.start() && cont.isActive) cont.resume(null)
            }
        }

    private fun queryParams(url: NSURL): Map<String, String> {
        val components = NSURLComponents.componentsWithURL(url, resolvingAgainstBaseURL = false)
        val items = components?.queryItems ?: return emptyMap()
        return buildMap {
            for (item in items) {
                val queryItem = item as NSURLQueryItem
                queryItem.value?.let { put(queryItem.name, it) }
            }
        }
    }

    private class AnchorProvider :
        NSObject(),
        ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(
            session: ASWebAuthenticationSession,
        ): ASPresentationAnchor = UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }
}
