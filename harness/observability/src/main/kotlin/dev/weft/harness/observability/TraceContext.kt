package dev.weft.harness.observability

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context element that carries the **currently active trace
 * id and conversation id**. Used to propagate parent → child linkage
 * through suspend-function call chains without threading these
 * parameters everywhere.
 *
 * Set by [dev.weft.harness.agents.WeftAgent.send] / `sendStreaming` after
 * starting a trace; read by [dev.weft.harness.agents.subagents.SubAgentRunner]
 * (and by the sub-agent's `send`) so:
 *
 *   - the sub-agent's `traceStore.startTrace` call gets the parent's
 *     [traceId] as its `parentTraceId` (nested traces),
 *   - the sub-agent's `WeftAgent` is constructed with the parent's
 *     [conversationId] so all traces from one user turn — orchestrator
 *     plus every sub-agent — share the same conversation id, letting
 *     conversation-scoped queries return the whole tree.
 *
 * Implementation note: kotlinx.coroutines's structured concurrency
 * preserves [CoroutineContext] through `withContext`, `async`,
 * `launch`, and plain suspend calls — so a parent `WeftAgent` that
 * wraps its body in `withContext(TraceContext(...))` will have those
 * ids visible to every tool execution inside the agent loop,
 * including `DelegateTool.executeWeft` which invokes the sub-agent.
 *
 * Why a [CoroutineContext] element rather than a constructor param or
 * a [WeftContext] field: tools are constructed once at runtime build
 * time but executed many times across different agent turns. Each
 * turn has a different trace id; constructor injection can't carry
 * that. CoroutineContext is the idiomatic way to thread "ambient
 * per-call data" without parameter pollution.
 */
class TraceContext(
    val traceId: String,
    val conversationId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceContext>
}
