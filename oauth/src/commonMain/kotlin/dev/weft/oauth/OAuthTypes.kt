package dev.weft.oauth

import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Per-connector OAuth configuration.
 *
 * @property clientId OAuth client identifier issued by the provider. For
 *   public mobile clients we deliberately do NOT take a client secret —
 *   that's the whole point of PKCE.
 * @property authorizationEndpoint URL the user is sent to for the
 *   consent screen. E.g. `https://linear.app/oauth/authorize`.
 * @property tokenEndpoint URL we POST the auth code (and later refresh
 *   tokens) to. E.g. `https://api.linear.app/oauth/token`.
 * @property redirectUri Where the provider sends the user back after they
 *   consent. Must match a redirect URI registered with the provider AND
 *   match an `<intent-filter>` declared in the host app's manifest, e.g.
 *   `undercurrent://oauth/linear`. The substrate doesn't pick the scheme —
 *   the app does.
 * @property scopes OAuth scopes to request. Provider-specific.
 * @property extraAuthParams Extra query params appended to the
 *   authorization URL — useful for `prompt=consent`, `audience=`,
 *   `access_type=offline` (Google), etc.
 */
@Serializable
public data class OAuthConfig(
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    val scopes: List<String> = emptyList(),
    val extraAuthParams: Map<String, String> = emptyMap(),
)

/**
 * The persisted credential bundle for one connector. We store the bundle
 * as a single JSON blob inside KeyVault rather than splitting across keys
 * — the KeyVault interface is per-alias and bundling keeps the storage
 * cleaner.
 *
 * `expiresAtEpochMs == 0L` means "no expiry communicated by the server"
 * — treat as a long-lived token. Providers like GitHub do this for their
 * older OAuth flow; modern OIDC providers always include `expires_in`.
 */
@Serializable
public data class TokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val tokenType: String = "Bearer",
    val scope: String? = null,
) {
    /**
     * Conservatively expired: returns true within [skewMs] of the real
     * expiry so refreshes happen *before* the next request 401s. Default
     * 60s of skew covers clock drift + in-flight requests.
     */
    @OptIn(ExperimentalTime::class)
    public fun isExpired(skewMs: Long = DEFAULT_SKEW_MS, nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean {
        if (expiresAtEpochMs == 0L) return false
        return nowMs >= (expiresAtEpochMs - skewMs)
    }

    public companion object {
        public const val DEFAULT_SKEW_MS: Long = 60_000L
    }
}

/**
 * Outcome of [OAuthClient.authorize]. The success case carries the full
 * token bundle; the failure cases preserve enough context for the app to
 * surface a useful error.
 */
public sealed class OAuthResult {
    public data class Success(val tokens: TokenSet) : OAuthResult()

    /** User dismissed the Custom Tab without consenting. */
    public data object UserCancelled : OAuthResult()

    /**
     * Provider returned an `error` query param on the redirect. Common
     * values: `access_denied`, `invalid_request`, `invalid_scope`.
     */
    public data class ProviderError(val code: String, val description: String?) : OAuthResult()

    /** Network / parsing failure during token exchange. */
    public data class TransportError(val message: String, val cause: Throwable? = null) : OAuthResult()

    /**
     * `state` returned by the provider didn't match what we generated.
     * Indicates either a stale callback or an attempted CSRF. Always
     * treat as failure; never trust the tokens.
     */
    public data object StateMismatch : OAuthResult()
}

internal const val GRANT_TYPE_AUTHORIZATION_CODE: String = "authorization_code"
internal const val GRANT_TYPE_REFRESH_TOKEN: String = "refresh_token"
internal const val CODE_CHALLENGE_METHOD_S256: String = "S256"
