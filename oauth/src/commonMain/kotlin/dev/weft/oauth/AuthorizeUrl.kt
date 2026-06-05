package dev.weft.oauth

import io.ktor.http.URLBuilder

/**
 * Builds the provider's authorization-endpoint URL for the Authorization
 * Code + PKCE flow. Pure string construction — no platform or network
 * dependency — so the same builder serves every [OAuthClient] impl
 * (Custom Tabs, ASWebAuthenticationSession) instead of each re-deriving
 * the query layout.
 */
internal object AuthorizeUrl {

    /**
     * @param state per-flow CSRF token (also used to disambiguate
     *   concurrent redirects on the shared Android callback channel).
     * @param challenge the PKCE S256 code challenge derived from the
     *   verifier the caller keeps for the later token exchange.
     */
    fun build(config: OAuthConfig, state: String, challenge: String): String =
        URLBuilder(config.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("state", state)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", CODE_CHALLENGE_METHOD_S256)
            if (config.scopes.isNotEmpty()) {
                parameters.append("scope", config.scopes.joinToString(" "))
            }
            for ((k, v) in config.extraAuthParams) parameters.append(k, v)
        }.buildString()
}
