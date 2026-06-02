package dev.weft.oauth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * iOS implementation of [OAuthClient] backed by
 * `ASWebAuthenticationSession` (browser-driven authorize + callback) +
 * Ktor/Darwin (token exchange). Mirrors [dev.weft.oauth.AndroidOAuthClient];
 * only the browser step differs — the system intercepts the redirect's
 * URL scheme, so no host-side callback channel is needed.
 *
 * The redirect scheme is derived from [OAuthConfig.redirectUri] (the part
 * before `://`); the host must register it as a URL type in its app.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
public class IosOAuthClient(
    private val httpClient: HttpClient = defaultHttpClient(),
) : OAuthClient {

    override suspend fun authorize(config: OAuthConfig): OAuthResult {
        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.deriveChallenge(verifier)
        val state = Pkce.generateState()

        val authorizeUrl = buildAuthorizeUrl(config, state, challenge)
        val callbackScheme = config.redirectUri.substringBefore("://")

        val callback = launchWebAuth(authorizeUrl, callbackScheme) ?: return OAuthResult.UserCancelled
        val params = queryParams(callback)

        params["error"]?.let {
            return OAuthResult.ProviderError(code = it, description = params["error_description"])
        }
        if (params["state"] != state) return OAuthResult.StateMismatch
        val code = params["code"] ?: return OAuthResult.ProviderError(
            code = "missing_code",
            description = "Redirect URI had neither `code` nor `error` query param.",
        )
        return exchangeCodeForToken(config, code, verifier)
    }

    override suspend fun refresh(config: OAuthConfig, refreshToken: String): OAuthResult = runCatching {
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
        OAuthResult.TransportError("Refresh failed: ${t.message ?: t::class.simpleName}", t)
    }

    private fun buildAuthorizeUrl(config: OAuthConfig, state: String, challenge: String): String =
        URLBuilder(config.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", config.redirectUri)
            parameters.append("state", state)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", CODE_CHALLENGE_METHOD_S256)
            if (config.scopes.isNotEmpty()) parameters.append("scope", config.scopes.joinToString(" "))
            for ((k, v) in config.extraAuthParams) parameters.append(k, v)
        }.buildString()

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

    private suspend fun exchangeCodeForToken(config: OAuthConfig, code: String, verifier: String): OAuthResult =
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
            parseTokenResponse(response, fallbackRefreshToken = null)
        }.getOrElse { t ->
            OAuthResult.TransportError("Token exchange failed: ${t.message ?: t::class.simpleName}", t)
        }

    private suspend fun parseTokenResponse(response: HttpResponse, fallbackRefreshToken: String?): OAuthResult {
        if (response.status != HttpStatusCode.OK) {
            val body = runCatching { response.body<TokenErrorEnvelope>() }.getOrNull()
            if (body?.error != null) return OAuthResult.ProviderError(body.error, body.errorDescription)
            return OAuthResult.TransportError("Token endpoint returned HTTP ${response.status.value}")
        }
        val envelope = response.body<TokenEnvelope>()
        val now = Clock.System.now().toEpochMilliseconds()
        val expiresAt = envelope.expiresIn?.let { now + it * 1_000L } ?: 0L
        return OAuthResult.Success(
            TokenSet(
                accessToken = envelope.accessToken,
                refreshToken = envelope.refreshToken ?: fallbackRefreshToken,
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

    private class AnchorProvider :
        NSObject(),
        ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(
            session: ASWebAuthenticationSession,
        ): ASPresentationAnchor = UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }

    public companion object {
        public fun defaultHttpClient(): HttpClient = HttpClient(Darwin.create()) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }
}
