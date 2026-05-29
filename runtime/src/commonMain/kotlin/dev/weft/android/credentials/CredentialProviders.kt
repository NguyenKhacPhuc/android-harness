package dev.weft.android.credentials

import dev.weft.contracts.KeyVault
import dev.weft.contracts.ProviderKind
import dev.weft.contracts.WeftCredentialProvider

/**
 * Returns a static, in-process Anthropic API key. The current BYOK flow —
 * the user pastes their own key, the app stores it in `KeyVault`, and at
 * `buildAgent` time we wrap it in this provider.
 *
 * Lifetime: the key is captured at construction and reused for every
 * subsequent LLM call. To rotate, build a fresh provider + a fresh agent.
 */
public class StaticKeyProvider(
    private val apiKey: String,
) : WeftCredentialProvider {
    override suspend fun bearer(): String = apiKey
    // Defaults: api.anthropic.com + x-api-key header — correct for BYOK.
}

/**
 * OpenAI-flavored sibling of [StaticKeyProvider]. The user pastes their
 * OpenAI API key (typically `sk-…`), the app stores it in `KeyVault`, and
 * at `buildAgent` time we wrap it here.
 *
 * Differs from [StaticKeyProvider] only in [kind] + the configurable
 * [baseUrl]. The `Authorization: Bearer …` wire header is added by Koog's
 * `AbstractOpenAILLMClient` internally — [bearer] returns the raw key
 * (NOT pre-prefixed) to avoid the double-Bearer bug.
 */
public class StaticOpenAIKeyProvider(
    private val apiKey: String,
    override val baseUrl: String = WeftCredentialProvider.OPENAI_BASE_URL,
) : WeftCredentialProvider {
    override val kind: ProviderKind = ProviderKind.OpenAI
    // authHeaderName left at the contract default (`x-api-key`). Koog
    // ignores it and wires its own Authorization header — see
    // AbstractOpenAILLMClient. The override only mattered for a planned
    // direct-HTTP path we don't use today.
    override suspend fun bearer(): String = apiKey
}

/**
 * OpenRouter credential provider. OpenRouter is OpenAI-compatible at the
 * wire layer — Koog's OpenRouterLLMClient adds `Authorization: Bearer …`
 * internally, so [bearer] returns the raw key.
 */
public class StaticOpenRouterKeyProvider(
    private val apiKey: String,
) : WeftCredentialProvider {
    override val kind: ProviderKind = ProviderKind.OpenRouter
    override suspend fun bearer(): String = apiKey
}

/**
 * DeepSeek credential provider. DeepSeek's API is OpenAI-compatible;
 * Koog's DeepSeekLLMClient handles the auth header internally.
 */
public class StaticDeepSeekKeyProvider(
    private val apiKey: String,
) : WeftCredentialProvider {
    override val kind: ProviderKind = ProviderKind.DeepSeek
    override suspend fun bearer(): String = apiKey
}

/**
 * Pulls the API key out of [KeyVault] on every call. Useful when the key
 * may change while the app runs (user rotates it in settings, an automated
 * refresh rewrites it, etc.). For a static key cached at launch, prefer
 * [StaticKeyProvider] — fewer KeyVault round-trips.
 *
 * Throws if the alias isn't present — apps should gate `buildAgent` on
 * `keyVault.exists(alias)` first.
 */
public class KeyVaultKeyProvider(
    private val keyVault: KeyVault,
    private val alias: String,
) : WeftCredentialProvider {
    override suspend fun bearer(): String =
        keyVault.get(alias) ?: error("No credential stored at alias '$alias'")
}

/**
 * Targets a proxy server you control instead of Anthropic directly. The
 * app sends a session token; your server validates it and forwards the
 * request to Anthropic with the real key.
 *
 * Typical wiring:
 *
 * ```kotlin
 * ProxyServerProvider(
 *     baseUrl = "https://my-app.com/llm",
 *     sessionTokenSource = { authStore.currentSessionJwt() },
 * )
 * ```
 *
 * **Server contract.** Your server must:
 *   - Accept the Anthropic Messages-API request shape verbatim on
 *     `<baseUrl>/v1/messages` (and `/v1/models` for capability discovery).
 *   - Validate the session token sent in the `x-api-key` header.
 *     (We use `x-api-key` because Koog 0.8's Anthropic client hardcodes
 *     that header; once Koog exposes header customization the SDK will
 *     switch to `Authorization: Bearer`.)
 *   - Inject the real Anthropic key + your own rate limit / abuse
 *     checks, then proxy the upstream call.
 *   - Stream the response back unchanged (Koog expects Anthropic's
 *     event-stream shape).
 *
 * The substrate's streaming / tool-call / cost-tracking pipelines treat
 * the proxy as a transparent hop — no special code path needed on the
 * client.
 *
 * @property baseUrl Your server's root. The substrate appends Anthropic's
 *   `/v1/messages` and `/v1/models` paths. Omit the trailing slash.
 * @property sessionTokenSource Returns the **current** user-session token
 *   each turn. Called before every LLM request so OAuth refresh / session
 *   renewal lands here naturally.
 */
public class ProxyServerProvider(
    baseUrl: String,
    private val sessionTokenSource: suspend () -> String,
) : WeftCredentialProvider {
    // Override the contract's default base URL with the app's proxy URL.
    override val baseUrl: String = baseUrl
    // Sticks with x-api-key because Koog's Anthropic transport hardcodes
    // that header today. The proxy validates `x-api-key: <session-token>`.
    override val authHeaderName: String = WeftCredentialProvider.DEFAULT_AUTH_HEADER

    override suspend fun bearer(): String = sessionTokenSource()
}

/**
 * Calls a suspending lambda each turn to fetch a fresh (often short-lived)
 * credential. Use when your server mints per-request or per-session keys
 * for the user — common pattern when you can't proxy the Anthropic call
 * but still don't want to hand the user a long-lived key.
 *
 * Set [baseUrl] / [authHeaderName] if you also need to redirect the
 * transport; defaults target Anthropic + `x-api-key`.
 */
public class RotatingKeyProvider(
    private val fetch: suspend () -> String,
    override val baseUrl: String = WeftCredentialProvider.DEFAULT_BASE_URL,
    override val authHeaderName: String = WeftCredentialProvider.DEFAULT_AUTH_HEADER,
) : WeftCredentialProvider {
    override suspend fun bearer(): String = fetch()
}
