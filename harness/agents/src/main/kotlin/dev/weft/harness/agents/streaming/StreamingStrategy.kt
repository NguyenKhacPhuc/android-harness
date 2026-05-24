package dev.weft.harness.agents.streaming

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.prompt.multimodal.buildUserParts
import kotlinx.coroutines.flow.Flow

/**
 * Build a streaming-capable Koog graph strategy that mirrors
 * [singleRunStrategy][ai.koog.agents.core.agent.singleRunStrategy]'s
 * structure but replaces the LLM nodes with streaming variants. Each
 * text delta is forwarded via [onTextDelta] as it arrives; the graph
 * routes through tool calls just like the non-streaming default.
 *
 * Output shape:
 *   nodeStart
 *      → nodeStreamingCallLLM (Message.Assistant)
 *           ↘ onTextMessage  → nodeFinish (yields String)
 *           ↘ onToolCalls    → nodeExecuteTools (ReceivedToolResults)
 *                                → nodeStreamingSendToolResults (Message.Assistant)
 *                                     ↘ onTextMessage  → nodeFinish
 *                                     ↘ onToolCalls    → nodeExecuteTools (loop)
 *
 * Tool tracing piggybacks on Koog's existing `onToolCallStarting/Completed`
 * event hooks — they fire on the standard nodeExecuteTools regardless of
 * what node produced the tool-call MessageParts. LLM tracing in the
 * streaming path is the caller's responsibility (the [onLlmStreamingComplete]
 * callback fires once per LLM round with the final metaInfo).
 *
 * Koog-1.0.0 notes: in 1.0.0 the old `Message.Response` union (Assistant |
 * Tool.Call) is gone — tool calls are now `MessagePart.Tool.Call` carried
 * inside a `Message.Assistant`. Edges route via `onTextMessage` /
 * `onToolCalls` which inspect those parts. Custom streaming nodes therefore
 * need to assemble a `Message.Assistant` whose parts list contains either
 * `MessagePart.Text` or `MessagePart.Tool.Call` entries (or both, in
 * principle — current call sites only see one or the other from Anthropic).
 */
internal fun streamingSingleRunStrategy(
    onTextDelta: suspend (String) -> Unit,
    onLlmStreamingStart: suspend () -> Unit = {},
    onLlmStreamingComplete: suspend (metaInfo: ResponseMetaInfo) -> Unit = {},
): AIAgentGraphStrategy<WeftUserInput, String> = strategy("streaming_single_run") {
    val nodeStreamingCallLLM by node<WeftUserInput, Message.Assistant>("streaming_call_llm") { input ->
        llm.writeSession {
            // Build the user message with text + attachments as a parts
            // list. Shared with the non-streaming `weftSingleRunStrategy`
            // via [buildUserParts] so both paths emit the same shape.
            appendPrompt { user(buildUserParts(input)) }
            collectStreamingResponse(onTextDelta, onLlmStreamingStart, onLlmStreamingComplete)
        }
    }
    val nodeExecuteToolsX by nodeExecuteTools()
    val nodeStreamingSendToolResults by node<ReceivedToolResults, Message.Assistant>("streaming_send_tool_results") { toolResults ->
        llm.writeSession {
            appendPrompt {
                user {
                    toolResults.toolResults.forEach { r ->
                        toolResult(r.toMessagePart())
                    }
                }
            }
            collectStreamingResponse(onTextDelta, onLlmStreamingStart, onLlmStreamingComplete)
        }
    }

    // Wire: same shape as singleRunStrategy but with the streaming LLM nodes.
    edge(nodeStart forwardTo nodeStreamingCallLLM)
    edge(nodeStreamingCallLLM forwardTo nodeExecuteToolsX onToolCalls { true })
    edge(nodeStreamingCallLLM forwardTo nodeFinish onTextMessage { true })
    edge(nodeExecuteToolsX forwardTo nodeStreamingSendToolResults)
    edge(nodeStreamingSendToolResults forwardTo nodeFinish onTextMessage { true })
    edge(nodeStreamingSendToolResults forwardTo nodeExecuteToolsX onToolCalls { true })
}

/**
 * Collect a streaming LLM response, forward text deltas through the supplied
 * callback, and assemble the resulting [Message.Assistant] for the graph to
 * route on.
 *
 * Receiver is `ai.koog.agents.core.agent.session.AIAgentLLMWriteSession`
 * — we use the public API surface (`requestLLMStreaming`, `appendPrompt`)
 * and don't reach into internals.
 *
 * Important: `requestLLMStreaming` does NOT auto-append the assistant
 * response to the prompt the way `requestLLM` does. We append manually
 * after the stream completes so any follow-up call sees a coherent
 * conversation history.
 */
private suspend fun ai.koog.agents.core.agent.session.AIAgentLLMWriteSession.collectStreamingResponse(
    onTextDelta: suspend (String) -> Unit,
    onLlmStreamingStart: suspend () -> Unit,
    onLlmStreamingComplete: suspend (metaInfo: ResponseMetaInfo) -> Unit,
): Message.Assistant {
    onLlmStreamingStart()
    val stream: Flow<StreamFrame> = requestLLMStreaming()
    val textBuilder = StringBuilder()
    val toolCalls = mutableListOf<MessagePart.Tool.Call>()
    var metaInfo: ResponseMetaInfo = ResponseMetaInfo.Empty
    var finishReason: String? = null

    stream.collect { frame ->
        when (frame) {
            is StreamFrame.TextDelta -> {
                textBuilder.append(frame.text)
                onTextDelta(frame.text)
            }
            is StreamFrame.ToolCallComplete -> {
                toolCalls += MessagePart.Tool.Call(
                    id = frame.id,
                    tool = frame.name,
                    args = frame.content,
                )
            }
            is StreamFrame.End -> {
                metaInfo = frame.metaInfo
                finishReason = frame.finishReason
            }
            // TextComplete / ReasoningDelta / ReasoningComplete / ToolCallDelta
            // are informational for this strategy — we ignore them. Real callers
            // could surface reasoning if they want.
            else -> Unit
        }
    }
    onLlmStreamingComplete(metaInfo)

    // Truncation guard: if the LLM hit max_tokens mid-tool-call, the JSON
    // in `call.args` will be a partial string that fails to parse
    // downstream with a confusing "unexpected end of input" error. Detect
    // here and throw something the user can understand. The runtime's
    // retry policy won't help — same prompt + same max_tokens = same
    // truncation — so we surface the cap as the actionable signal.
    if (finishReason == "max_tokens" && toolCalls.isNotEmpty()) {
        throw StreamingTruncatedException(
            "LLM hit max_tokens (${metaInfo.outputTokensCount ?: '?'} out) while " +
                "emitting a tool call (${toolCalls.first().tool}). The tool's " +
                "JSON arguments were truncated. Raise WeftRuntime(maxOutputTokens=…) " +
                "or simplify the request — for ui_render trees this usually means asking " +
                "for fewer components per turn.",
        )
    }

    // Build an Assistant message whose parts capture whatever streamed.
    // Order: text first (if any), then tool calls — matches Anthropic's
    // typical response shape and lets edges' `onTextMessage` / `onToolCalls`
    // transformers pick the parts they care about.
    val parts = mutableListOf<MessagePart.ResponsePart>()
    val text = textBuilder.toString()
    if (text.isNotEmpty()) parts += MessagePart.Text(text)
    parts += toolCalls

    val response = Message.Assistant(
        parts = parts,
        metaInfo = metaInfo,
        finishReason = finishReason,
    )
    // Append assistant/tool-call to prompt history (auto-append doesn't
    // happen for streaming — see kdoc above).
    appendPrompt { message(response) }
    return response
}

/**
 * Thrown when streaming hits `max_tokens` while the model was emitting a
 * tool call. The tool-call JSON is truncated, so we can't execute it; the
 * caller needs to either raise the budget or get the model to produce a
 * smaller call. [dev.weft.harness.agents.WeftAgent.sendStreaming] propagates this
 * through its Flow as [StreamChunk.Failed].
 */
public class StreamingTruncatedException(message: String) : RuntimeException(message)

/**
 * Sealed event surface returned by `WeftAgent.sendStreaming`. Mirrors
 * the existing `ToolEvent` flow for tool activity and adds per-token text
 * deltas plus a terminal [Done] carrying the full final reply.
 */
public sealed class StreamChunk {
    /** Incremental token of the assistant's reply. Append to the visible bubble. */
    public data class TextDelta(val text: String) : StreamChunk()

    /** A tool is about to fire. Same semantic as `ToolEvent.Starting`. */
    public data class ToolStarting(val toolName: String, val argsPreview: String) : StreamChunk()

    /** Tool returned. Same semantic as `ToolEvent.Completed`. */
    public data class ToolCompleted(val toolName: String) : StreamChunk()

    /** Tool failed. Same semantic as `ToolEvent.Failed`. */
    public data class ToolFailed(val toolName: String, val message: String) : StreamChunk()

    /**
     * Terminal event — the agent finished and produced [finalReply]. The
     * caller can use this to flip "in flight" state off; persistence /
     * trace completion already happened by the time this fires.
     */
    public data class Done(val finalReply: String) : StreamChunk()

    /** Terminal event for failure — `sendStreaming` will also throw, but this is the in-flow signal. */
    public data class Failed(val message: String) : StreamChunk()
}
