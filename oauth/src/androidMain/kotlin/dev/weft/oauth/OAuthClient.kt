package dev.weft.oauth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Default Android implementation of [OAuthClient] backed by Custom
 * Tabs (browser-driven authorize) + Ktor (token exchange). The
 * interface itself lives in commonMain so iOS can ship a parallel
 * `ASWebAuthenticationSession`-based impl when needed.
 *
 * The [callbacks] channel is shared across all concurrent flows;
 * per-flow [Pkce.generateState] disambiguates which redirect belongs
 * to which `authorize()` invocation.
 *
 * **Threading.** [authorize] suspends across user-driven UI (Custom
 * Tabs). Call it from a coroutine attached to the foreground
 * lifecycle, not from the agent loop directly — if the user dismisses
 * the tab without consenting, you want to know quickly. The default
 * timeout is 5 minutes; beyond that we return
 * [OAuthResult.UserCancelled].
 */
public class AndroidOAuthClient(
    context: Context,
    private val callbacks: OAuthCallbackChannel,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val authorizeTimeoutMs: Long = DEFAULT_AUTHORIZE_TIMEOUT_MS,
) : OAuthClient {

    private val appContext: Context = context.applicationContext

    override suspend fun authorize(config: OAuthConfig): OAuthResult {
        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.deriveChallenge(verifier)
        val state = Pkce.generateState()

        // Open the browser. From here on, control belongs to the user
        // until they consent, dismiss, or wait us out.
        val authorizeUri = buildAuthorizeUri(config, state, challenge)
        launchCustomTabs(authorizeUri)

        // Wait for a redirect that matches our state. Other redirects
        // (older flows, cross-talk from concurrent connectors) get
        // filtered here.
        val redirect = withTimeoutOrNull(authorizeTimeoutMs) {
            callbacks.callbacks.first { uri -> matchesFlow(uri, config.redirectUri, state) }
        } ?: return OAuthResult.UserCancelled

        // Parse the redirect.
        val error = redirect.getQueryParameter("error")
        if (error != null) {
            return OAuthResult.ProviderError(
                code = error,
                description = redirect.getQueryParameter("error_description"),
            )
        }
        val returnedState = redirect.getQueryParameter("state")
        if (returnedState != state) return OAuthResult.StateMismatch
        val code = redirect.getQueryParameter("code") ?: return OAuthResult.ProviderError(
            code = "missing_code",
            description = "Redirect URI had neither `code` nor `error` query param.",
        )

        return exchangeCodeForToken(config, code, verifier)
    }

    override suspend fun refresh(config: OAuthConfig, refreshToken: String): OAuthResult {
        return runCatching {
            val response = httpClient.submitForm(
                url = config.tokenEndpoint,
                formParameters = parameters {
                    append("grant_type", GRANT_TYPE_REFRESH_TOKEN)
                    append("refresh_token", refreshToken)
                    append("client_id", config.clientId)
                },
            )
            parseTokenResponse(response, fallbackRefreshToken = refreshToken)
        }.getOrElse { t ->
            OAuthResult.TransportError(
                message = "Refresh failed: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
    }

    // ----- Internals -------------------------------------------------------

    private fun buildAuthorizeUri(config: OAuthConfig, state: String, challenge: String): Uri =
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
        }.build().toString().let(Uri::parse)

    private fun launchCustomTabs(uri: Uri) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(false)
            .build()
        // Custom Tabs launches in a Task we can't easily attach to the
        // host activity from a background coroutine, so we use the
        // FLAG_ACTIVITY_NEW_TASK fallback. The user comes back via the
        // OS deep-link routing, not via finishActivity().
        intent.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private suspend fun exchangeCodeForToken(
        config: OAuthConfig,
        code: String,
        verifier: String,
    ): OAuthResult = runCatching {
        val response = httpClient.submitForm(
            url = config.tokenEndpoint,
            formParameters = parameters {
                append("grant_type", GRANT_TYPE_AUTHORIZATION_CODE)
                append("code", code)
                append("redirect_uri", config.redirectUri)
                append("client_id", config.clientId)
                append("code_verifier", verifier)
            },
        )
        parseTokenResponse(response, fallbackRefreshToken = null)
    }.getOrElse { t ->
        OAuthResult.TransportError(
            message = "Token exchange failed: ${t.message ?: t::class.simpleName}",
            cause = t,
        )
    }

    private suspend fun parseTokenResponse(
        response: HttpResponse,
        fallbackRefreshToken: String?,
    ): OAuthResult {
        if (response.status != HttpStatusCode.OK) {
            // Try to extract a structured error payload before bailing.
            val body = runCatching { response.body<TokenErrorEnvelope>() }.getOrNull()
            if (body?.error != null) {
                return OAuthResult.ProviderError(code = body.error, description = body.errorDescription)
            }
            return OAuthResult.TransportError(
                message = "Token endpoint returned HTTP ${response.status.value}",
            )
        }
        val envelope = response.body<TokenEnvelope>()
        val now = System.currentTimeMillis()
        val expiresAt = envelope.expiresIn?.let { now + it * 1_000L } ?: 0L
        // Provider rotation: keep whatever the server sent; if it omitted
        // a refresh token entirely, fall back to the one we already have
        // (some providers only emit the refresh token on initial grant).
        val refresh = envelope.refreshToken ?: fallbackRefreshToken
        return OAuthResult.Success(
            tokens = TokenSet(
                accessToken = envelope.accessToken,
                refreshToken = refresh,
                expiresAtEpochMs = expiresAt,
                tokenType = envelope.tokenType ?: "Bearer",
                scope = envelope.scope,
            ),
        )
    }

    @Serializable
    private data class TokenEnvelope(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("scope") val scope: String? = null,
    )

    @Serializable
    private data class TokenErrorEnvelope(
        @SerialName("error") val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    public companion object {
        public const val DEFAULT_AUTHORIZE_TIMEOUT_MS: Long = 5 * 60 * 1_000L

        /**
         * Build a Ktor client wired with JSON content negotiation suitable
         * for OAuth providers. Apps with their own HTTP stack can pass
         * any [HttpClient] in.
         */
        public fun defaultHttpClient(): HttpClient = HttpClient(OkHttp.create()) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
}
