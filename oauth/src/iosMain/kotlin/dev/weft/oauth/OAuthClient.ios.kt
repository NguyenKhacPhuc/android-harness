package dev.weft.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * iOS [OAuthClient]: `ASWebAuthenticationSession` for the consent step,
 * Ktor (Darwin) for token exchange. A thin composition root — it wires
 * the iOS-specific [WebAuthBrowserAuthorizer] to the shared
 * [DefaultOAuthClient] orchestration + [TokenEndpoint] (both commonMain).
 * Mirrors [AndroidOAuthClient]; only the browser step differs.
 *
 * The redirect scheme is derived from [OAuthConfig.redirectUri] (the part
 * before `://`); the host must register it as a URL type in its app.
 */
public class IosOAuthClient private constructor(
    private val delegate: OAuthClient,
) : OAuthClient by delegate {

    public constructor(
        httpClient: HttpClient = defaultHttpClient(),
    ) : this(
        DefaultOAuthClient(
            browser = WebAuthBrowserAuthorizer(),
            tokenEndpoint = TokenEndpoint(httpClient),
        ),
    )

    public companion object {
        public fun defaultHttpClient(): HttpClient = HttpClient(Darwin.create()) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }
}
