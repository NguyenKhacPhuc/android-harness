package dev.weft.android.routing

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.llm.LLModel
import dev.weft.android.WeftRuntime
import dev.weft.contracts.ProviderKind
import dev.weft.harness.agents.routing.ModelPool

/**
 * Per-provider model catalog. Returns the curated set of [LLModel]s that
 * we expose in the per-tier model picker. Not exhaustive — Koog's full
 * catalogs ship many more variants (e.g., 20+ OpenAI Chat models). The
 * curation here favors familiar names, recent models, and a mix across
 * the price/capability spectrum so users can fill all four
 * [ModelPool] slots sensibly.
 *
 * To expand the menu for a given provider, append entries below. To wire
 * a brand-new provider, add a [ProviderKind] arm here AND extend
 * [WeftRuntime.buildExecutorFor] to construct its executor.
 */
public fun catalogFor(provider: ProviderKind): List<LLModel> = when (provider) {
    ProviderKind.Anthropic -> listOf(
        AnthropicModels.Haiku_4_5,
        AnthropicModels.Sonnet_4_5,
        AnthropicModels.Sonnet_4_6,
        AnthropicModels.Opus_4_5,
        AnthropicModels.Opus_4_6,
        AnthropicModels.Opus_4_7,
    )

    ProviderKind.OpenAI -> listOf(
        OpenAIModels.Chat.GPT4oMini,
        OpenAIModels.Chat.GPT4o,
        OpenAIModels.Chat.GPT4_1Mini,
        OpenAIModels.Chat.GPT4_1,
        OpenAIModels.Chat.GPT5Mini,
        OpenAIModels.Chat.GPT5,
        OpenAIModels.Chat.GPT5Codex,
    )

    // OpenRouter ships ~60 models in Koog's catalog. Curated down to the
    // recognizable subset — mix providers + price tiers so users see the
    // gateway's breadth without drowning in options. Add a model here
    // when users ask for it; full Koog list at
    // ai.koog.prompt.executor.clients.openrouter.OpenRouterModels.
    ProviderKind.OpenRouter -> listOf(
        OpenRouterModels.GPT4oMini,
        OpenRouterModels.GPT5Nano,
        OpenRouterModels.GPT5Mini,
        OpenRouterModels.GPT5,
        OpenRouterModels.Claude4_5Haiku,
        OpenRouterModels.Claude4_5Sonnet,
        OpenRouterModels.Claude4_6Sonnet,
        OpenRouterModels.Claude4_5Opus,
        OpenRouterModels.Claude4_6Opus,
        OpenRouterModels.Phi4Reasoning,
    )

    ProviderKind.DeepSeek -> listOf(
        WeftRuntime.DEEPSEEK_CHAT_MODEL,
        WeftRuntime.DEEPSEEK_REASONER_MODEL,
    )
}

/**
 * Resolve a model by its `id` within [provider]'s catalog. Used by the
 * app to convert persisted model-id strings back into typed [LLModel]
 * instances at agent-build time. Returns null when the id doesn't match
 * anything in the curated catalog — caller should fall back to the
 * tier's default.
 */
public fun findModelInCatalog(provider: ProviderKind, modelId: String): LLModel? =
    catalogFor(provider).firstOrNull { it.id == modelId }

/**
 * Default [ModelPool] for [provider] — the same one
 * [WeftRuntime.buildExecutorFor] uses when no override is supplied. Pure
 * function over [ProviderKind]: no credentials, no Koog client built. The
 * app uses this to populate "current default" hints in the model-picker
 * UI without paying the cost of constructing an executor.
 *
 * Adding a provider: extend this with its default pool AND extend
 * [WeftRuntime.buildExecutorFor]'s arm so the live runtime matches what
 * this advertises.
 */
public fun defaultPoolFor(provider: ProviderKind): ModelPool = when (provider) {
    ProviderKind.Anthropic -> ModelPool(
        cheap = AnthropicModels.Haiku_4_5,
        standard = WeftRuntime.SONNET_4_6_MODEL,
        vision = WeftRuntime.SONNET_4_6_MODEL,
        heavy = AnthropicModels.Opus_4_7,
    )
    ProviderKind.OpenAI -> ModelPool(
        cheap = OpenAIModels.Chat.GPT4oMini,
        standard = OpenAIModels.Chat.GPT4o,
        vision = OpenAIModels.Chat.GPT4o,
        heavy = OpenAIModels.Chat.GPT4o,
    )
    ProviderKind.OpenRouter -> ModelPool(
        cheap = OpenRouterModels.GPT4oMini,
        standard = OpenRouterModels.Claude4_5Sonnet,
        vision = OpenRouterModels.Claude4_5Sonnet,
        heavy = OpenRouterModels.Claude4_5Opus,
    )
    ProviderKind.DeepSeek -> ModelPool(
        cheap = WeftRuntime.DEEPSEEK_CHAT_MODEL,
        standard = WeftRuntime.DEEPSEEK_CHAT_MODEL,
        vision = WeftRuntime.DEEPSEEK_CHAT_MODEL,
        heavy = WeftRuntime.DEEPSEEK_REASONER_MODEL,
    )
}
