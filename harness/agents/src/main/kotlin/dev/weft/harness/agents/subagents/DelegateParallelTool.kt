package dev.weft.harness.agents.subagents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `delegate_parallel` — fan out to N sub-agents concurrently and wait
 * for all of them.
 *
 * Use when the sub-tasks are independent (no data flow between them) —
 * "research these three topics" or "summarise each of these four
 * documents." Latency is bounded by the slowest sub-agent rather than
 * their sum, which is the main reason to reach for this over a sequence
 * of [DelegateTool] calls.
 *
 * Result: a JSON array of `{role, result}` objects, one per spec, in
 * the same order the orchestrator submitted them. The orchestrator
 * reads the array as a normal tool result string and routes the pieces
 * however it wants.
 *
 * **Same isolation invariants** as [DelegateTool] — every sub-agent is
 * a fresh [dev.weft.harness.agents.WeftAgent] with a constrained tool subset,
 * empty history, in-memory discarded trace store. Costs aggregate to
 * the shared [dev.weft.harness.cost.UsageStore].
 *
 * **Cost discipline**: parallel fan-out can spike daily spend quickly
 * — N sub-agents fire at once, each running to completion. The
 * orchestrator's
 * [dev.weft.harness.cost.QuotaPolicy] still gates entry (any sub-agent
 * exceeding the daily cap throws), but parallel calls all pre-check
 * the quota before launching and could collectively blow past the cap
 * if started simultaneously near the limit. Worth knowing.
 */
public class DelegateParallelTool(
    ctx: WeftContext,
    private val runner: SubAgentRunner,
) : WeftTool<DelegateParallelTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = TOOL_NAME,
        description = (
            "Spawn N sub-agents concurrently and wait for all of them. Use when sub-tasks are " +
                "INDEPENDENT (no data flow between them). Latency = slowest sub-agent, not the sum.\n\n" +
                "If sub-tasks depend on each other, use `delegate` sequentially instead.\n\n" +
                "Result: JSON array of {role, result} objects in submission order.\n\n" +
                "Args:\n" +
                "- specs: JSON array of sub-agent specs, e.g. " +
                "[{\"role\":\"...\",\"task\":\"...\",\"tools\":\"network_fetch\",\"modelTier\":\"STANDARD\"},...]"
        ),
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "specs",
                "JSON array of sub-agent specs.",
                ToolParameterType.String,
            ),
        ),
    ),
) {

    @Serializable
    public data class Args(val specs: String)

    @Serializable
    public data class SpecJson(
        val role: String,
        val task: String,
        val tools: String = "",
        val modelTier: String = "STANDARD",
    )

    @Serializable
    public data class ResultJson(val role: String, val result: String)

    override suspend fun executeWeft(args: Args): String {
        val parsed = runCatching { json.decodeFromString<List<SpecJson>>(args.specs) }
            .getOrElse { e ->
                return "delegate_parallel: failed to parse `specs` as JSON array: ${e.message}"
            }
        if (parsed.isEmpty()) return "[]"

        val specs = parsed.map { spec ->
            SubAgentSpec(
                role = spec.role,
                task = spec.task,
                tools = spec.tools.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
                modelTier = DelegateTool.parseTier(spec.modelTier),
            )
        }

        // coroutineScope structured-concurrency: if one sub-agent throws,
        // the others get cancelled, the scope re-throws and the tool
        // call surfaces a failure to the orchestrator. Matches what
        // happens for any other tool exception.
        val results = coroutineScope {
            specs.map { spec ->
                async { ResultJson(role = spec.role, result = runner.run(spec)) }
            }.awaitAll()
        }
        return json.encodeToString(results)
    }

    public companion object {
        public const val TOOL_NAME: String = "delegate_parallel"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
