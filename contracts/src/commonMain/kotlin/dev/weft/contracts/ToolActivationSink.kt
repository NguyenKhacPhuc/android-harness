package dev.weft.contracts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * Side channel between `find_tool` (which lives in `:tools`) and the
 * agent strategy's activation graph node (which lives in
 * `:harness:agents`).
 *
 * Stage 2 of `docs/architecture/tool-provider.md`. When the LLM calls
 * `find_tool`, the tool searches its [ToolProvider]'s catalog and
 * writes matching tool names into this sink via [record]. The agent
 * loop's activation node, sitting between `nodeExecuteTools` and
 * `nodeSendToolResult`, drains the sink via [drain] and resolves
 * each name into a [ResolvedTool] which then becomes:
 *
 *   1. Advertised to the next LLM iteration via
 *      `AIAgentLLMContext.tools = tools + new` (mutable field).
 *   2. Dispatchable when the LLM calls it via
 *      `ToolRegistry.add(new)`.
 *
 * Both Koog APIs are confirmed mutable post-construction; see
 * `docs/architecture/tool-provider-koog-probe.md`.
 *
 * ### Lifecycle
 *
 * A fresh sink is created per `WeftAgent.send` call and attached to
 * the agent's coroutine context. Tools and graph nodes access it via
 * `coroutineContext[ToolActivationSink.Key]`. When the turn ends,
 * the sink is discarded.
 *
 * ### Concurrency
 *
 * The sink is shared between find_tool's tool-dispatch coroutine and
 * the graph node's activation coroutine — both run inside the same
 * `agent.run()` call but on potentially different dispatchers.
 * Mutations are serialized through a [Mutex].
 */
class ToolActivationSink : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key

    private val mutex = Mutex()
    private val pending = mutableListOf<String>()

    /**
     * Append [toolNames] to the pending activation list. Called by
     * `find_tool` after a successful catalog search. Duplicates are
     * de-duplicated against what's already pending so a noisy LLM
     * calling find_tool twice doesn't double-activate.
     */
    suspend fun record(toolNames: Collection<String>) {
        if (toolNames.isEmpty()) return
        mutex.withLock {
            for (name in toolNames) {
                if (name !in pending) pending += name
            }
        }
    }

    /**
     * Atomically read + clear the pending list. Called by the agent
     * strategy's activation node after every tool-execution batch.
     * Returning an empty list means "no activations this batch" —
     * the node short-circuits.
     */
    suspend fun drain(): List<String> = mutex.withLock {
        if (pending.isEmpty()) emptyList()
        else pending.toList().also { pending.clear() }
    }

    companion object Key : CoroutineContext.Key<ToolActivationSink>
}
