package dev.weft.oauth

/**
 * OAuth 2.0 Authorization Code + PKCE client.
 *
 * One [OAuthClient] handles multiple configs / connectors.
 *
 * **Threading.** [authorize] suspends across user-driven UI (Custom
 * Tabs on Android, ASWebAuthenticationSession on iOS once that lands).
 * Call it from a coroutine attached to the foreground lifecycle, not
 * from the agent loop directly — if the user dismisses the prompt
 * without consenting, you want to know quickly. Implementations should
 * apply a sensible timeout (the Android default is 5 minutes) and
 * return [OAuthResult.UserCancelled] when the user backs out.
 *
 * KMP — commonMain. Implementations:
 *   - androidMain: [AndroidOAuthClient] (Custom Tabs + Ktor/OkHttp).
 *   - iosMain: TBD (ASWebAuthenticationSession + Ktor/Darwin) — host
 *     can ship its own until the substrate ships one.
 */
public interface OAuthClient {
    /**
     * Run the full Authorization Code + PKCE flow: open the system
     * browser, suspend for the redirect, exchange the code for tokens.
     */
    public suspend fun authorize(config: OAuthConfig): OAuthResult

    /**
     * Exchange [refreshToken] for a fresh access token. Most providers
     * also rotate the refresh token; the caller should persist
     * whatever comes back, not retain the original.
     */
    public suspend fun refresh(config: OAuthConfig, refreshToken: String): OAuthResult
}
