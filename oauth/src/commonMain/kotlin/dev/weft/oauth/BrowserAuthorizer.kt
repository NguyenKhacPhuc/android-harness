package dev.weft.oauth

/**
 * The one genuinely platform-specific step of the Authorization Code
 * flow: present the provider's consent page in a system browser and hand
 * back the redirect it produces. Android drives Custom Tabs + the
 * deep-link [OAuthCallbackChannel]; iOS drives
 * `ASWebAuthenticationSession`. Everything around this step (PKCE,
 * URL building, redirect validation, token exchange) is shared in
 * commonMain — see [DefaultOAuthClient].
 */
internal interface BrowserAuthorizer {

    /**
     * Open [authorizeUrl] and suspend until the user finishes.
     *
     * @param redirectUri the configured callback the provider returns to
     *   — used by the Android impl to recognise the matching deep link.
     * @param expectedState the per-flow state — used by the Android impl
     *   to select the right redirect off the shared callback channel when
     *   multiple authorizations run concurrently. iOS ignores it (its
     *   session is 1:1).
     * @return the redirect's query params, or [BrowserResult.Cancelled]
     *   if the user dismissed the browser / timed out.
     */
    suspend fun authorize(
        authorizeUrl: String,
        redirectUri: String,
        expectedState: String,
    ): BrowserResult
}

/** Outcome of the browser step. */
internal sealed interface BrowserResult {
    /** User dismissed the browser without completing, or we timed out. */
    data object Cancelled : BrowserResult

    /** The provider redirected back; [params] are the redirect query params. */
    data class Redirect(val params: Map<String, String>) : BrowserResult
}
