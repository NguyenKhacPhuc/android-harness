package dev.weft.harness.prompt.cache

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl

/**
 * Stability classification for a region of a prompt.
 *
 * Caching is prefix-based across every provider Koog supports: a marker
 * tags the *end* of a region, and the provider caches everything before
 * (and including) that point. The tier choice is purely about how long
 * the cached prefix is expected to live, not about which provider —
 * [CacheBinder] implementations translate the tier into the right
 * provider-native directive.
 */
enum class CacheTier {
    /**
     * Bytes-identical across calls for the life of the app process: app
     * preamble, tool catalog, component metadata. Maps to the provider's
     * longest available TTL (1 hour on Anthropic).
     */
    STATIC,

    /**
     * Stable within a session/conversation but varies across them:
     * compacted history older than the live tail, long documents the
     * user is querying, MCP-discovered tool catalogs. Maps to the
     * provider's medium TTL (5 minutes on Anthropic).
     */
    SESSION,

    /**
     * Changes nearly every turn: device snapshot, last user message,
     * in-flight tool results. Never cached — exists so the builder can
     * label volatile regions explicitly and refuse to attach markers
     * to them.
     */
    VOLATILE,
}

/**
 * Provider-agnostic cache directive emitter. Implementations translate a
 * [CacheTier] into whatever cache-control surface their backend supports
 * — explicit markers for Anthropic and Bedrock, no-op for OpenAI and
 * DeepSeek (those providers cache prefixes automatically and don't
 * accept explicit hints).
 *
 * The interface intentionally exposes a single helper [cachedSystem]
 * because the v1 surface only marks the system message. As we add
 * history-tier and tool-catalog caching the interface will grow —
 * keep additions opt-in via default implementations so existing
 * binders don't have to change.
 */
interface CacheBinder {
    /**
     * Emit a `system(...)` message into [builder], optionally annotated
     * with the appropriate cache directive for this binder's provider.
     */
    fun cachedSystem(builder: PromptBuilder, text: String, tier: CacheTier)

    /**
     * Emit a `user(...)` message into [builder] with an optional cache
     * directive. Used to mark the boundary between SESSION-stable history
     * and the VOLATILE tail when assembling the prompt.
     *
     * Anthropic supports cache_control on user messages directly (Koog
     * 1.0.0's `user(content, cache)` overload). Other providers either
     * cache automatically or don't support it — `NoOpCacheBinder` just
     * emits a plain user message.
     */
    fun cachedUser(builder: PromptBuilder, text: String, tier: CacheTier)

    /**
     * Return [tools] with the last entry's [ToolDescriptor] marked for
     * caching at [tier], or [tools] unchanged when the binder doesn't
     * support tool-catalog caching.
     *
     * Anthropic caches the *prefix* of the tool list ending at the last
     * marked tool — so marking the trailing tool extends the cached
     * prefix to cover every prior tool definition. For a substrate with
     * 40+ tools this is bigger savings than the system message alone.
     */
    fun markedTools(tools: List<Tool<*, *>>, tier: CacheTier): List<Tool<*, *>>
}

/**
 * Anthropic / Anthropic-via-proxy binder. Uses Koog 1.0.0's native
 * [AnthropicCacheControl] markers.
 *
 * Mapping:
 *   - STATIC  → `AnthropicCacheControl.OneHour` (extended 1h cache)
 *   - SESSION → `AnthropicCacheControl.Default` (5 minute cache)
 *   - VOLATILE → no marker (caller wanted to opt out)
 *
 * The 1-hour TTL costs ~25% more per cache write than the 5-minute TTL
 * but earns proportionally larger savings on long-lived sessions, so
 * it's the right default for the substrate's app preamble + tool
 * catalog (which never change within a process).
 */
object AnthropicCacheBinder : CacheBinder {
    override fun cachedSystem(builder: PromptBuilder, text: String, tier: CacheTier) {
        val ctl = tierToControl(tier)
        if (ctl != null) builder.system(text, ctl) else builder.system(text)
    }

    override fun cachedUser(builder: PromptBuilder, text: String, tier: CacheTier) {
        val ctl = tierToControl(tier)
        if (ctl != null) builder.user(text, ctl) else builder.user(text)
    }

    override fun markedTools(tools: List<Tool<*, *>>, tier: CacheTier): List<Tool<*, *>> {
        if (tools.isEmpty()) return tools
        val ctl = tierToControl(tier) ?: return tools
        val last = tools.last()
        // Anthropic caches "up to and including" the marked descriptor —
        // we mark the LAST tool so the entire prior catalog gets cached.
        val markedDescriptor = last.descriptor.copy(cacheControl = ctl)
        return tools.dropLast(1) + CachedToolAdapter(last, markedDescriptor)
    }

    private fun tierToControl(tier: CacheTier): AnthropicCacheControl? = when (tier) {
        CacheTier.STATIC -> AnthropicCacheControl.OneHour
        CacheTier.SESSION -> AnthropicCacheControl.Default
        CacheTier.VOLATILE -> null
    }
}

/**
 * Inert binder for providers without explicit cache markers (OpenAI,
 * DeepSeek, Ollama). OpenAI and DeepSeek cache long stable prefixes
 * automatically server-side, so callers still benefit from putting
 * volatile content last — the binder just doesn't add directives.
 * Ollama doesn't have prompt caching at all.
 */
object NoOpCacheBinder : CacheBinder {
    override fun cachedSystem(builder: PromptBuilder, text: String, tier: CacheTier) {
        builder.system(text)
    }

    override fun cachedUser(builder: PromptBuilder, text: String, tier: CacheTier) {
        builder.user(text)
    }

    override fun markedTools(tools: List<Tool<*, *>>, tier: CacheTier): List<Tool<*, *>> = tools
}

/**
 * Forwards `execute` to an inner [Tool] while presenting a different
 * [ToolDescriptor] to Koog. Used by [AnthropicCacheBinder.markedTools]
 * to attach `cache_control` to the trailing tool's descriptor without
 * disturbing the underlying tool's behaviour (permission gates,
 * destructive confirmations, app-specific tools — all preserved
 * because the parent class's virtual dispatch routes through to the
 * original tool's overridden `execute`).
 *
 * The unchecked casts are safe at runtime: Koog dispatches via the
 * tool's [Tool.argsType] TypeToken, not via the Kotlin generic
 * parameters, so the star-projection lossage here is invisible
 * to the runtime.
 */
class CachedToolAdapter(
    private val inner: Tool<*, *>,
    descriptor: ToolDescriptor,
) : Tool<Any?, Any?>(
    argsType = inner.argsType,
    resultType = inner.resultType,
    descriptor = descriptor,
) {
    @Suppress("UNCHECKED_CAST")
    private val innerAny: Tool<Any?, Any?> = inner as Tool<Any?, Any?>

    override suspend fun execute(args: Any?): Any? = innerAny.execute(args)
}
