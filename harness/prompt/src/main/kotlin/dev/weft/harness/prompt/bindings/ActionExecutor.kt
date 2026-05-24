package dev.weft.harness.prompt.bindings

import dev.weft.contracts.DataSourceRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Substrate-level handler for `${'$'}exec` action sentinels emitted by
 * the agent in a `ComponentNode`'s `action` prop. The Compose-side
 * renderer ([dev.weft.compose.components.AgentRenderedTreeScreen])
 * invokes this BEFORE handing the event off to the host's `onAction`
 * callback. When the action is shaped as a direct-execute payload,
 * we run the named data tool against the registry without involving
 * the LLM — sub-100ms tap-to-result instead of the 3-5s round-trip
 * the legacy LLM path takes.
 *
 * Hosts don't need to know about `${'$'}exec` — by the time their
 * `onAction` callback fires, the action has either been handled here
 * (and the callback is skipped) or it's a legacy LLM-driven action
 * (and the callback receives it as before).
 *
 * Lives in `:harness:prompt/bindings/` next to [BindingEvaluator]
 * because the two concepts are paired: `${'$'}exec` mutates the data
 * source, `${'$'}binding` re-resolves the display against it. Same
 * file structure, same package, same module.
 *
 * ### Supported tools in v1
 *
 *   - `data_upsert` — args: `{source, record}`. Inserts or updates
 *     a record in the named source.
 *   - `data_delete` — args: `{source, id}`. Deletes by id from the
 *     named source. Returns false silently if id wasn't there.
 *
 * Unsupported tools (anything other than these two) fall through —
 * [tryExecuteAction] returns [Result.handled] = false so the caller
 * forwards to the LLM path. The SDK can grow this set as additional
 * direct-execute tools earn their own fast-path.
 */
public object ActionExecutor {

    /**
     * Outcome of an attempted direct-execute dispatch.
     *
     * @property handled true when the action was a recognized
     *   `${'$'}exec` sentinel and we ran it (success OR failure). When
     *   false, the caller should forward the action to the host's
     *   `onAction` (legacy LLM-driven path).
     * @property tool the named tool that ran, if any — useful for
     *   tracing / logging by the caller.
     * @property errorMessage non-null when the dispatch ran but the
     *   underlying tool failed. The caller may surface this to the
     *   user (e.g. as a chat error bubble) without re-running through
     *   the LLM.
     */
    public data class Result(
        public val handled: Boolean,
        public val tool: String? = null,
        public val errorMessage: String? = null,
    )

    /**
     * Try to interpret [action] as a Layer-2 direct-execute payload
     * and run it. Returns `Result(handled = false)` when [action]
     * isn't JSON, isn't an object, doesn't carry a `${'$'}exec` key, or
     * names a tool outside the fast-path set — caller falls back to
     * the LLM path. Returns `Result(handled = true, ...)` when we
     * dispatched, regardless of whether the dispatch itself
     * succeeded; the error (if any) is on [Result.errorMessage].
     */
    public suspend fun tryExecuteAction(
        action: String,
        sources: DataSourceRegistry,
    ): Result {
        val exec = parseExec(action) ?: return Result(handled = false)
        return runCatching {
            executeExec(exec, sources)
        }.fold(
            onSuccess = { Result(handled = true, tool = exec.tool) },
            onFailure = { t ->
                Result(
                    handled = true,
                    tool = exec.tool,
                    errorMessage = t.message ?: t::class.simpleName.orEmpty(),
                )
            },
        )
    }

    /**
     * Try to interpret [action] as JSON containing a `${'$'}exec` sentinel.
     * Public so callers (e.g. UI surfaces other than `AgentRenderedTreeScreen`)
     * can detect the format without actually dispatching it.
     */
    public fun isExecAction(action: String): Boolean = parseExec(action) != null

    private fun parseExec(action: String): ExecAction? {
        val parsed = runCatching { Json.parseToJsonElement(action) }.getOrNull()
            ?: return null
        val obj = parsed as? JsonObject ?: return null
        val exec = obj["\$exec"] as? JsonObject ?: return null
        val tool = (exec["tool"] as? JsonPrimitive)?.content ?: return null
        val args = exec["args"] as? JsonObject ?: JsonObject(emptyMap())
        return ExecAction(tool, args)
    }

    private suspend fun executeExec(exec: ExecAction, sources: DataSourceRegistry) {
        when (exec.tool) {
            "data_upsert" -> {
                val name = (exec.args["source"] as? JsonPrimitive)?.content
                    ?: error("data_upsert missing 'source'")
                val record = exec.args["record"] as? JsonObject
                    ?: error("data_upsert missing 'record'")
                val source = sources.get(name)
                    ?: error("Unknown data source '$name'")
                source.upsert(record)
            }
            "data_delete" -> {
                val name = (exec.args["source"] as? JsonPrimitive)?.content
                    ?: error("data_delete missing 'source'")
                val id = (exec.args["id"] as? JsonPrimitive)?.content
                    ?: error("data_delete missing 'id'")
                val source = sources.get(name)
                    ?: error("Unknown data source '$name'")
                source.delete(id)
            }
            else -> error(
                "\$exec tool '${exec.tool}' is not in the substrate fast-path. " +
                    "Supported: data_upsert, data_delete.",
            )
        }
    }

    private data class ExecAction(val tool: String, val args: JsonObject)
}
