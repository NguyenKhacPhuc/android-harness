package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.ToolActivationSink
import dev.weft.contracts.ToolMetadata
import dev.weft.contracts.ToolProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

/**
 * The discovery meta-tool — Stage 2 of `docs/architecture/tool-provider.md`.
 *
 * Substrate-registered, advertised in every agent's catalog when the
 * runtime's [ToolProvider] has any on-demand tools. When called by
 * the LLM, this tool:
 *
 *   1. Scores every entry in `provider.available` against the query
 *      (substring match on name + description, optionally
 *      category-filtered).
 *   2. Returns the top-N matches to the LLM as a structured list of
 *      `(name, description)` pairs.
 *   3. **Side-effect:** writes those names into the
 *      [ToolActivationSink] on the current coroutine context. The
 *      agent loop's activation graph node drains the sink after this
 *      tool's result is committed and mutates `llm.tools` +
 *      `ToolRegistry` so the surfaced tools become callable in the
 *      next iteration of the *same* user-visible turn.
 *
 * Ranking is naive substring matching — works for v1; can be swapped
 * for embeddings later without a contract change. The relevant
 * scoring quality knob is _what is shown to the LLM_, not _what is
 * activated_; activation is downstream of selection.
 */
class FindToolTool(
    ctx: WeftContext,
    private val provider: ToolProvider,
) : WeftTool<FindToolTool.Args, FindToolTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "find_tool",
        description = "Find tools available beyond the ones listed above. Use when " +
            "you need to do something the listed tools don't cover — camera, file " +
            "I/O, calendar, network, integrations. Returns matching tool names + " +
            "descriptions; after calling this, the matching tools become available " +
            "for you to call in the SAME turn.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "query",
                "Natural-language search query, e.g. 'take photo', 'send email', " +
                    "'read calendar events'. Substring-matched against tool names + " +
                    "descriptions.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "category",
                "Optional category filter (e.g. 'memory', 'maps', 'media'). When " +
                    "set, only tools tagged with this category are searched.",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "limit",
                "Max results (default 8, max 25).",
                ToolParameterType.Integer,
            ),
        ),
    ),
    // Side effect is "activate tools for the remainder of this turn",
    // which the LLM expects to happen. Not destructive.
    sideEffecting = true,
) {

    @Serializable
    data class Args(
        val query: String,
        val category: String? = null,
        val limit: Int = DEFAULT_LIMIT,
    )

    @Serializable
    data class Result(
        /** Found tools, best-first. */
        val tools: List<Match>,
        /**
         * How many of [tools] were activated. Activation happens
         * automatically after this tool returns — the agent loop's
         * graph node reads the sink and mutates LLM context.
         */
        val activated: Int,
    )

    @Serializable
    data class Match(val name: String, val description: String, val category: String? = null)

    override suspend fun executeWeft(args: Args): Result {
        val cap = args.limit.coerceIn(1, MAX_LIMIT)
        val candidates = provider.available
            .let { all ->
                if (args.category.isNullOrBlank()) all
                else all.filter { it.category?.equals(args.category, ignoreCase = true) == true }
            }
            // Exclude always-on tools — they're already visible to the LLM, no
            // point in re-surfacing them (and we don't want to "activate"
            // something that's already in the registry).
            .filter { !it.alwaysOn }

        val ranked = rank(candidates, args.query).take(cap)
        val matches = ranked.map { meta ->
            Match(name = meta.name, description = meta.description, category = meta.category)
        }

        // Side channel — graph node downstream will drain this and apply
        // the mutations. If we're not running inside a WeftAgent.send()
        // (e.g. unit test calling executeWeft directly), the sink is
        // absent and we silently skip activation; the LLM still sees the
        // search result, just nothing becomes callable.
        val sink = currentCoroutineContext()[ToolActivationSink.Key]
        if (sink != null && matches.isNotEmpty()) {
            sink.record(matches.map { it.name })
        }
        return Result(tools = matches, activated = if (sink != null) matches.size else 0)
    }

    /**
     * Naive ranking — substring matches on name + description, scored
     * by which field matched and how exact the match is. Works for
     * v1; swap for an embedding model once the substring approach
     * starts missing in practice.
     */
    private fun rank(candidates: List<ToolMetadata>, query: String): List<ToolMetadata> {
        if (query.isBlank()) return candidates
        val q = query.lowercase().trim()
        val tokens = q.split(WHITESPACE).filter { it.isNotBlank() }

        data class Scored(val meta: ToolMetadata, val score: Int)

        return candidates
            .map { meta ->
                val name = meta.name.lowercase()
                val desc = meta.description.lowercase()
                var s = 0
                if (name == q) s += SCORE_EXACT_NAME
                else if (name.contains(q)) s += SCORE_NAME_SUBSTRING
                for (t in tokens) {
                    if (name.contains(t)) s += SCORE_NAME_TOKEN
                    if (desc.contains(t)) s += SCORE_DESC_TOKEN
                }
                Scored(meta, s)
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .map { it.meta }
    }

    private companion object {
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 25
        const val SCORE_EXACT_NAME = 100
        const val SCORE_NAME_SUBSTRING = 50
        const val SCORE_NAME_TOKEN = 10
        const val SCORE_DESC_TOKEN = 3
        val WHITESPACE = Regex("\\s+")
    }
}
