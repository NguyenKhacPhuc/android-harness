package dev.weft.android

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import dev.weft.android.routing.defaultPoolFor
import dev.weft.contracts.ProviderKind
import dev.weft.contracts.WeftCredentialProvider
import dev.weft.harness.agents.routing.ModelPool
import dev.weft.harness.prompt.cache.AnthropicCacheBinder
import dev.weft.harness.prompt.cache.CacheBinder
import dev.weft.harness.prompt.cache.NoOpCacheBinder

/**
 * Builds the Koog [MultiLLMPromptExecutor] + matching [CacheBinder] for a
 * credential [provider], paired with the provider's default [ModelPool].
 * Extracted from [WeftRuntime] so the composition root doesn't carry a
 * 140-line provider switch.
 *
 * **Single source of truth for pools.** The [ModelPool] comes from
 * [defaultPoolFor] — the same function the app's model-picker UI reads —
 * so the live runtime and the advertised defaults can't drift. (They used
 * to be two hand-synced copies: one here, one in `defaultPoolFor`.) Only
 * the executor + cache binder are provider-specific here; the per-tier
 * model choices live entirely in [defaultPoolFor].
 *
 * Adding a provider means: add a [ProviderKind] arm here (executor +
 * binder) AND extend [defaultPoolFor] / `catalogFor` with its pool.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
internal suspend fun buildProviderExecutor(
    provider: WeftCredentialProvider,
): Triple<MultiLLMPromptExecutor, ModelPool, CacheBinder> {
    val pool = defaultPoolFor(provider.kind)
    val (executor, cacheBinder) = when (provider.kind) {
        ProviderKind.Anthropic -> {
            val client = AnthropicLLMClient(
                apiKey = provider.bearer(),
                settings = AnthropicClientSettings(
                    // modelVersionsMap maps Koog's LLModel.id values to the
                    // wire-API names. Sonnet 4.6 is the only custom (non-Koog-
                    // catalog) entry; the rest come from AnthropicModels.
                    modelVersionsMap = mapOf(WeftRuntime.SONNET_4_6_MODEL to "claude-sonnet-4-6"),
                    baseUrl = provider.baseUrl,
                ),
                // Koog 1.0.0 made the HTTP client a pluggable runtime dep
                // discovered via ServiceLoader. Android's packaging is fragile
                // around META-INF/services entries (R8 + AGP resource-merging
                // both can strip them), so we pass the factory explicitly.
                httpClientFactory = KtorKoogHttpClient.Factory(),
            )
            MultiLLMPromptExecutor(client) to AnthropicCacheBinder
        }
        ProviderKind.OpenAI -> {
            val client = OpenAILLMClient(
                apiKey = provider.bearer(),
                settings = OpenAIClientSettings(baseUrl = provider.baseUrl),
                httpClientFactory = KtorKoogHttpClient.Factory(),
            )
            // OpenAI caches stable prefixes server-side, no explicit markers
            // needed — the binder is a no-op for prompt building + tool marking.
            MultiLLMPromptExecutor(client) to NoOpCacheBinder
        }
        ProviderKind.OpenRouter -> {
            val client = OpenRouterLLMClient(
                apiKey = provider.bearer(),
                settings = OpenRouterClientSettings(),
                httpClientFactory = KtorKoogHttpClient.Factory(),
            )
            // OpenRouter is a passthrough; upstream caching shows through but
            // we don't drive markers from our side.
            MultiLLMPromptExecutor(client) to NoOpCacheBinder
        }
        ProviderKind.DeepSeek -> {
            // DeepSeek's API is OpenAI-compatible. Koog dropped its dedicated
            // DeepSeek client at 1.0.0, so we use OpenAILLMClient pointed at
            // api.deepseek.com. Models are tagged with LLMProvider.DeepSeek;
            // the executor map keys this client under that provider explicitly
            // to satisfy MultiLLMPromptExecutor's lookup (OpenAILLMClient
            // doesn't care that its key isn't OpenAI).
            val client = OpenAILLMClient(
                apiKey = provider.bearer(),
                settings = OpenAIClientSettings(baseUrl = WeftRuntime.DEEPSEEK_BASE_URL),
                httpClientFactory = KtorKoogHttpClient.Factory(),
            )
            MultiLLMPromptExecutor(mapOf(LLMProvider.DeepSeek to client)) to NoOpCacheBinder
        }
    }
    return Triple(executor, pool, cacheBinder)
}
