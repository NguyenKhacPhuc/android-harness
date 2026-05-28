package dev.weft.contracts

/**
 * Where the runtime sources its LLM credentials.
 *
 * Apps pick the implementation that matches their auth model:
 *
 *   - **BYOK** (paste your own Anthropic key) — `StaticKeyProvider`.
 *     Good for dev tools, demos, power-user apps.
 *   - **Server proxy** (your backend holds the real key, your app talks to
 *     your server with a session token) — `ProxyServerProvider`. Standard
 *     consumer pattern. Every polished AI app does this.
 *   - **OAuth-connected providers** — the OAuth module's `OAuthTokenStore`
 *     plugs in here once Anthropic exposes user-account API access (or for
 *     any other provider with OAuth).
 *   - **Rotating short-lived keys** — a lambda the SDK calls each turn to
 *     fetch a fresh key from your server.
 *
 * The contract is intentionally minimal — three things the runtime needs
 * to know before making any LLM call:
 *
 *   1. [bearer] — the secret. Suspending because OAuth refresh + proxy
 *      session-token rotation are async by nature.
 *   2. [baseUrl] — where to send the request. Defaults to Anthropic
 *      direct. Override for proxy.
 *   3. [authHeaderName] — which header carries the secret. Defaults to
 *      `x-api-key` (Anthropic's scheme). Bearer-token providers (proxy,
 *      OAuth) override to `Authorization` and prepend `Bearer `.
 *
 * The SDK calls [bearer] **before each LLM request**, so providers are
 * free to cache and refresh as they see fit.
 */
/**
 * Which LLM backend this provider talks to. Drives client selection +
 * cache-binder selection in [dev.weft.android.WeftRuntime.buildAgent].
 *
 * New providers are added here as the substrate gains support. Each
 * `ProviderKind` should map to exactly one Koog `LLMClient` family.
 */
public enum class ProviderKind {
    /** Anthropic native or Anthropic-API-compatible proxy. */
    Anthropic,

    /** OpenAI native (api.openai.com). For OpenAI-compatible third parties
     *  with distinct branding (DeepSeek, OpenRouter), prefer their own
     *  ProviderKind entry so usage tracking + UX stay accurate. */
    OpenAI,

    /** OpenRouter — gateway routing one key to dozens of upstream models
     *  (hosted Anthropic, OpenAI, Llama, Mistral, etc.). OpenAI-compatible
     *  wire protocol; tagged separately for routing + cost accounting. */
    OpenRouter,

    /** DeepSeek (api.deepseek.com). OpenAI-compatible wire protocol; we
     *  use [ai.koog.prompt.executor.clients.openai.OpenAILLMClient] under
     *  the hood since Koog dropped its dedicated DeepSeek client at 1.0.0.
     *  Models tagged [ai.koog.prompt.llm.LLMProvider.DeepSeek] keep the
     *  routing + cost-attribution distinct from generic OpenAI usage. */
    DeepSeek,
}

public interface WeftCredentialProvider {

    /** The credential to authenticate the next LLM request. */
    public suspend fun bearer(): String

    /**
     * Which provider this credential targets. Defaults to [ProviderKind.Anthropic]
     * for back-compat — every credential provider that shipped before this
     * field existed was Anthropic-only. OpenAI / OpenAI-proxy providers
     * override this to [ProviderKind.OpenAI] so [dev.weft.android.WeftRuntime]
     * picks the right [ai.koog.prompt.executor.model.LLMClient] +
     * [dev.weft.harness.prompt.cache.CacheBinder].
     */
    public val kind: ProviderKind get() = ProviderKind.Anthropic

    /**
     * Base URL for the LLM provider. Default targets Anthropic; proxy
     * providers point at the app's backend; on-premise / region-pinned
     * setups can override here. OpenAI-kind providers MUST override to a
     * URL that responds to the OpenAI chat-completions schema.
     */
    public val baseUrl: String get() = DEFAULT_BASE_URL

    /**
     * HTTP header name that carries the credential. Defaults to Anthropic's
     * `x-api-key`. Bearer-style auth (proxy server, OAuth, OpenAI) should
     * override to `Authorization` and return the token already prefixed
     * with `"Bearer "` from [bearer].
     */
    public val authHeaderName: String get() = DEFAULT_AUTH_HEADER

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.anthropic.com"
        public const val DEFAULT_AUTH_HEADER: String = "x-api-key"
        public const val BEARER_AUTH_HEADER: String = "Authorization"
        public const val OPENAI_BASE_URL: String = "https://api.openai.com"
    }
}
