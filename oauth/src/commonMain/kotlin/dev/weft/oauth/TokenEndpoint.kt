package dev.weft.oauth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The provider's token endpoint: code-for-token exchange and refresh.
 *
 * Pure Ktor + kotlinx.serialization — no browser, no platform API — so
 * the HTTP/JSON half of OAuth lives once in commonMain instead of being
 * copy-pasted into each platform's [OAuthClient]. The engine is supplied
 * by the caller via [httpClient] (OkHttp on Android, Darwin on iOS);
 * everything else here is shared.
 *
 * Both methods are total: transport/parse failures map to
 * [OAuthResult.TransportError], structured provider errors to
 * [OAuthResult.ProviderError] — they never throw.
 */
@OptIn(ExperimentalTime::class)
internal class TokenEndpoint(private val httpClient: HttpClient) {

    suspend fun exchangeCode(config: OAuthConfig, code: String, verifier: String): OAuthResult =
        runCatching {
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
            parse(response, fallbackRefreshToken = null)
        }.getOrElse { t ->
            OAuthResult.TransportError("Token exchange failed: ${t.message ?: t::class.simpleName}", t)
        }

    suspend fun refresh(config: OAuthConfig, refreshToken: String): OAuthResult =
        runCatching {
            val response = httpClient.submitForm(
                url = config.tokenEndpoint,
                formParameters = parameters {
                    append("grant_type", GRANT_TYPE_REFRESH_TOKEN)
                    append("refresh_token", refreshToken)
                    append("client_id", config.clientId)
                },
            )
            parse(response, fallbackRefreshToken = refreshToken)
        }.getOrElse { t ->
            OAuthResult.TransportError("Refresh failed: ${t.message ?: t::class.simpleName}", t)
        }

    private suspend fun parse(response: HttpResponse, fallbackRefreshToken: String?): OAuthResult {
        if (response.status != HttpStatusCode.OK) {
            // Try to extract a structured error payload before bailing.
            val body = runCatching { response.body<TokenErrorEnvelope>() }.getOrNull()
            if (body?.error != null) {
                return OAuthResult.ProviderError(code = body.error, description = body.errorDescription)
            }
            return OAuthResult.TransportError("Token endpoint returned HTTP ${response.status.value}")
        }
        val envelope = response.body<TokenEnvelope>()
        val now = Clock.System.now().toEpochMilliseconds()
        val expiresAt = envelope.expiresIn?.let { now + it * 1_000L } ?: 0L
        // Provider rotation: keep whatever the server sent; if it omitted a
        // refresh token entirely, fall back to the one we already have (some
        // providers only emit the refresh token on the initial grant).
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
}
