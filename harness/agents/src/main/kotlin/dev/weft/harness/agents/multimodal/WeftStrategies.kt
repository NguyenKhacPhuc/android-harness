package dev.weft.harness.agents.multimodal

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.prompt.multimodal.buildUserParts

/**
 * Non-streaming chat strategy that accepts [WeftUserInput] instead of a
 * raw String. Mirrors Koog's built-in
 * [ai.koog.agents.core.agent.singleRunStrategy] one-for-one, with the
 * single difference: the first node emits a user message whose `parts`
 * list contains the text **and** the user's attachments (image / audio /
 * video / file).
 *
 * Flow:
 *   nodeStart
 *     → nodeCallLLM (WeftUserInput → Message.Assistant)
 *         ↘ onTextMessage → nodeFinish
 *         ↘ onToolCalls   → nodeExecuteTools (ReceivedToolResults)
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
 */
internal fun weftSingleRunStrategy(): AIAgentGraphStrategy<WeftUserInput, String> =
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
        val nodeSendToolResult by nodeLLMSendToolResults()

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteToolsX onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteToolsX forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        edge(nodeSendToolResult forwardTo nodeExecuteToolsX onToolCalls { true })
    }
