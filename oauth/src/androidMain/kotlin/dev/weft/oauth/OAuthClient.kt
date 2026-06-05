package dev.weft.oauth

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Default Android [OAuthClient]: Custom Tabs for the consent step, Ktor
 * (OkHttp) for token exchange. A thin composition root — it wires the
 * Android-specific [CustomTabsBrowserAuthorizer] to the shared
 * [DefaultOAuthClient] orchestration + [TokenEndpoint] (both commonMain).
 *
 * The [callbacks] channel is shared across all concurrent flows; per-flow
 * [Pkce.generateState] disambiguates which redirect belongs to which
 * `authorize()` invocation.
 *
 * **Threading.** [authorize] suspends across user-driven UI (Custom
 * Tabs). Call it from a coroutine attached to the foreground lifecycle,
 * not from the agent loop directly — if the user dismisses the tab
 * without consenting, you want to know quickly. The default timeout is 5
 * minutes; beyond that we return [OAuthResult.UserCancelled].
 */
public class AndroidOAuthClient private constructor(
    private val delegate: OAuthClient,
) : OAuthClient by delegate {

    public constructor(
        context: Context,
        callbacks: OAuthCallbackChannel,
        httpClient: HttpClient = defaultHttpClient(),
        authorizeTimeoutMs: Long = DEFAULT_AUTHORIZE_TIMEOUT_MS,
    ) : this(
        DefaultOAuthClient(
            browser = CustomTabsBrowserAuthorizer(context, callbacks, authorizeTimeoutMs),
            tokenEndpoint = TokenEndpoint(httpClient),
        ),
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
