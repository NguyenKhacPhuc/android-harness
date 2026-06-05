package dev.weft.oauth

/**
 * Shared [OAuthClient] orchestration. Composes the three single-purpose
 * collaborators so the Authorization Code + PKCE flow is written once,
 * not re-derived per platform:
 *
 *   1. [Pkce] — generate verifier / challenge / state.
 *   2. [AuthorizeUrl] — build the consent URL.
 *   3. [BrowserAuthorizer] — the only platform-specific step: present the
 *      browser, return the redirect params.
 *   4. [TokenEndpoint] — exchange the code (and later refresh) for tokens.
 *
 * Platform impls ([AndroidOAuthClient], [IosOAuthClient]) are thin
 * wrappers that supply a platform [BrowserAuthorizer] and a [TokenEndpoint]
 * over the platform's HTTP engine.
 */
internal class DefaultOAuthClient(
    private val browser: BrowserAuthorizer,
    private val tokenEndpoint: TokenEndpoint,
) : OAuthClient {

    override suspend fun authorize(config: OAuthConfig): OAuthResult {
        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.deriveChallenge(verifier)
        val state = Pkce.generateState()

        val authorizeUrl = AuthorizeUrl.build(config, state, challenge)
        val params = when (val outcome = browser.authorize(authorizeUrl, config.redirectUri, state)) {
            BrowserResult.Cancelled -> return OAuthResult.UserCancelled
            is BrowserResult.Redirect -> outcome.params
        }

        params["error"]?.let { error ->
            return OAuthResult.ProviderError(code = error, description = params["error_description"])
        }
        if (params["state"] != state) return OAuthResult.StateMismatch
        val code = params["code"] ?: return OAuthResult.ProviderError(
            code = "missing_code",
            description = "Redirect URI had neither `code` nor `error` query param.",
        )

        return tokenEndpoint.exchangeCode(config, code, verifier)
    }

    override suspend fun refresh(config: OAuthConfig, refreshToken: String): OAuthResult =
        tokenEndpoint.refresh(config, refreshToken)
}
