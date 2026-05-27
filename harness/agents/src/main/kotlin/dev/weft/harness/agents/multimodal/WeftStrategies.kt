package dev.weft.harness.agents.multimodal

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import dev.weft.contracts.ToolActivationSink
import dev.weft.contracts.ToolProvider
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.prompt.multimodal.buildUserParts
import dev.weft.tools.ResolvedWeftTool
import kotlin.coroutines.coroutineContext

/**
 * Non-streaming chat strategy that accepts [WeftUserInput] instead of a
 * raw String. Mirrors Koog's built-in
 * [ai.koog.agents.core.agent.singleRunStrategy] one-for-one, with two
 * differences:
 *
 *  1. The first node emits a user message whose `parts` list contains
 *     the text **and** the user's attachments (image / audio / video /
 *     file).
 *  2. A `nodeApplyActivations` sits between `nodeExecuteTools` and
 *     `nodeSendToolResult` to drain the [ToolActivationSink] and apply
 *     the two mutations Stage 2 of `docs/architecture/tool-provider.md`
 *     needs: extend the LLM-facing tool list AND extend the wire-level
 *     ToolRegistry. When [toolProvider] is null (back-compat, eager
 *     mode) this node is a no-op.
 *
 * Flow:
 *   nodeStart
 *     → nodeCallLLM (WeftUserInput → Message.Assistant)
 *         ↘ onTextMessage → nodeFinish
 *         ↘ onToolCalls   → nodeExecuteTools (ReceivedToolResults)
 *                             → nodeApplyActivations (pass-through + side effect)
 *                             → nodeSendToolResult (Message.Assistant)
 *                                 ↘ onTextMessage → nodeFinish
 *                                 ↘ onToolCalls   → nodeExecuteTools (loop)
 *
 * The follow-up `nodeSendToolResult` is identical to the built-in — once
 * we're past the first LLM call, every subsequent round-trip is just
 * "send tool results, read next assistant message," which doesn't need
 * the attachments handling.
 *
 * `buildUserParts` lives in `:harness:prompt` (pure prompt-shaping
 * function); imported here so the strategy node can emit the right
 * message-parts list.
 *
 * @param toolProvider the runtime's [ToolProvider]. When non-null, the
 *   activation node resolves names drained from the sink and mutates
 *   `llm.tools` + `llm.toolRegistry`. Null = eager mode, no activation.
 */
internal fun weftSingleRunStrategy(
    toolProvider: ToolProvider? = null,
): AIAgentGraphStrategy<WeftUserInput, String> =
    strategy<WeftUserInput, String>("weft_single_run") {
        val nodeCallLLM by node<WeftUserInput, Message.Assistant>("weft_call_llm") { input ->
            llm.writeSession {
                appendPrompt {
                    user(buildUserParts(input))
                }
                requestLLM()
            }
        }
        val nodeExecuteToolsX by nodeExecuteTools()
        val nodeApplyActivations by node<ReceivedToolResults, ReceivedToolResults>(
            "weft_apply_activations",
        ) { results ->
            if (toolProvider == null) return@node results
            val sink = coroutineContext[ToolActivationSink.Key] ?: return@node results
            val names = sink.drain()
            if (names.isEmpty()) return@node results

            val resolved = mutableListOf<ResolvedWeftTool>()
            for (name in names) {
                val r = toolProvider.resolve(name)
                if (r is ResolvedWeftTool) resolved += r
                // r==null or non-Weft -> silently skip; the LLM saw the
                // search result but the tool isn't registered. Next
                // iteration just won't find it; agent surfaces a
                // tool-not-found error from Koog's executor.
            }
            if (resolved.isEmpty()) return@node results

            // (1) Advertise to the next LLM call. `tools` is a mutable
            // property on the persistent AIAgentLLMContext; updating it
            // here propagates to whatever `nodeCallLLM` opens next.
            // Filter out anything already present (defensive: the model
            // may have called find_tool twice).
            llm.writeSession {
                val current = tools
                val currentNames = current.mapTo(HashSet()) { it.name }
                val additions = resolved
                    .map { it.tool.descriptor }
                    .filter { it.name !in currentNames }
                if (additions.isNotEmpty()) tools = current + additions
            }
            // (2) Make dispatchable when the LLM calls them. ToolRegistry's
            // `add()` is public and the GenericAgentEnvironment looks tools
            // up by name on each executeTool call. `llm.toolRegistry` is
            // a Kotlin property on AIAgentLLMContextCommon (Koog 1.0.0).
            val registry = llm.toolRegistry
            val registryNames = registry.tools.mapTo(HashSet()) { it.descriptor.name }
            for (r in resolved) {
                if (r.tool.descriptor.name !in registryNames) registry.add(r.tool)
            }
            results
        }
        val nodeSendToolResult by nodeLLMSendToolResults()

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteToolsX onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteToolsX forwardTo nodeApplyActivations)
        edge(nodeApplyActivations forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        edge(nodeSendToolResult forwardTo nodeExecuteToolsX onToolCalls { true })
    }
