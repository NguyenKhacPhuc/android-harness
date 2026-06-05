package dev.weft.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android [BrowserAuthorizer]: opens the consent page in Custom Tabs and
 * waits for the matching redirect off the shared [OAuthCallbackChannel].
 *
 * This is the *only* Android-specific part of the OAuth flow — PKCE, URL
 * building, redirect validation and token exchange all live in commonMain
 * ([DefaultOAuthClient]). The [callbacks] channel is shared across
 * concurrent flows; [expectedState] selects the redirect that belongs to
 * this authorization so callbacks don't cross-talk.
 */
internal class CustomTabsBrowserAuthorizer(
    context: Context,
    private val callbacks: OAuthCallbackChannel,
    private val authorizeTimeoutMs: Long,
) : BrowserAuthorizer {

    private val appContext: Context = context.applicationContext

    override suspend fun authorize(
        authorizeUrl: String,
        redirectUri: String,
        expectedState: String,
    ): BrowserResult {
        // Open the browser. From here on, control belongs to the user
        // until they consent, dismiss, or wait us out.
        launchCustomTabs(Uri.parse(authorizeUrl))

        // Wait for a redirect that matches our state. Other redirects
        // (older flows, cross-talk from concurrent connectors) get
        // filtered here.
        val redirect = withTimeoutOrNull(authorizeTimeoutMs) {
            callbacks.callbacks.first { uri -> matchesFlow(uri, redirectUri, expectedState) }
        } ?: return BrowserResult.Cancelled

        return BrowserResult.Redirect(redirect.queryParams())
    }

    private fun launchCustomTabs(uri: Uri) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(false)
            .build()
        // Custom Tabs launches in a Task we can't easily attach to the host
        // activity from a background coroutine, so we use the
        // FLAG_ACTIVITY_NEW_TASK fallback. The user comes back via the OS
        // deep-link routing, not via finishActivity().
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(appContext, uri)
    }

    private fun matchesFlow(uri: Uri, expectedRedirectPrefix: String, expectedState: String): Boolean {
        // Compare scheme + host + path against the configured redirect to
        // avoid mistaking another deep link for an OAuth callback. The
        // query carries `code` + `state` (or `error` + `state`).
        val expected = Uri.parse(expectedRedirectPrefix)
        if (uri.scheme != expected.scheme) return false
        if (uri.host != expected.host) return false
        if (uri.path.orEmpty().trimEnd('/') != expected.path.orEmpty().trimEnd('/')) return false
        val gotState = uri.getQueryParameter("state") ?: return false
        return gotState == expectedState
    }

    private fun Uri.queryParams(): Map<String, String> =
        buildMap {
            for (name in queryParameterNames) {
                getQueryParameter(name)?.let { put(name, it) }
            }
        }
}
