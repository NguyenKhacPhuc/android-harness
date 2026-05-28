package dev.weft.harness.observability.wiredump

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.ExperimentalTime

/**
 * Charles-Proxy-style wire-traffic dumper for [PromptExecutor].
 *
 * Wraps any delegate executor (typically Koog's `MultiLLMPromptExecutor`)
 * and records every request + response + timing to one or more
 * [DumpSink]s. Forwards calls transparently — no behaviour change at
 * the agent loop.
 *
 * Usage:
 *
 * ```kotlin
 * val real = MultiLLMPromptExecutor(...)
 * val dumper = WireDumper(
 *     delegate = real,
 *     sink = CompoundSink(
 *         JsonLinesSink(File("dumps/session.jsonl")),
 *         PerTurnSink(File("dumps/turns/")),
 *     ),
 * )
 * WeftAgent(executor = dumper, ...)
 * ```
 *
 * The dumper does not understand provider-specific cache-control or
 * structured-output extensions — those are captured as opaque JSON
 * blobs in [WireRequest.providerExtras] so the fixture round-trip
 * stays lossless even when we don't yet model the field.
 *
 * Threading: every dump-write happens after the delegate call returns
 * (or, for streaming, after the last frame). Multiple turns can be in
 * flight; each gets its own [WireCapture.requestId] so the JSON-lines
 * file stays sortable.
 */
class WireDumper(
    private val delegate: PromptExecutor,
    private val sink: DumpSink,
    /**
     * Optional turn-counter override. Defaults to a fresh atomic
     * starting at 1; pass a shared counter to align dumps with other
     * surfaces (e.g. the TraceStore).
     */
    private val turnCounter: AtomicInteger = AtomicInteger(0),
    /**
     * Clock override for tests. Production callers can ignore.
     */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : PromptExecutor() {

    @OptIn(ExperimentalTime::class)
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        val turn = turnCounter.incrementAndGet()
        val start = nowMs()
        val request = WireRequest.from(prompt, model, tools)
        val result = runCatching { delegate.execute(prompt, model, tools) }
        val durationMs = nowMs() - start

        val capture = WireCapture(
            turnNumber = turn,
            requestId = "req-${turn.toString().padStart(4, '0')}",
            timestampMs = start,
            durationMs = durationMs,
            streaming = false,
            request = request,
            response = result.getOrNull()?.let(WireResponse::from),
            error = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName.orEmpty() },
        )
        // Sink is responsible for handling its own errors; we don't let
        // a dump failure mask a real LLM error.
        runCatching { sink.write(capture) }
        return result.getOrThrow()
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        val turn = turnCounter.incrementAndGet()
        val start = nowMs()
        val request = WireRequest.from(prompt, model, tools)
        val collected = mutableListOf<StreamFrame>()
        val error = runCatching {
            delegate.executeStreaming(prompt, model, tools).collect { frame ->
                collected += frame
                emit(frame)
            }
        }.exceptionOrNull()
        val durationMs = nowMs() - start

        val capture = WireCapture(
            turnNumber = turn,
            requestId = "req-${turn.toString().padStart(4, '0')}-stream",
            timestampMs = start,
            durationMs = durationMs,
            streaming = true,
            request = request,
            response = if (error == null) WireResponse.fromFrames(collected) else null,
            error = error?.let { it.message ?: it::class.simpleName.orEmpty() },
        )
        runCatching { sink.write(capture) }
        if (error != null) throw error
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = delegate.executeMultipleChoices(prompt, model, tools)

    override suspend fun models(): List<LLModel> = delegate.models()

    override fun close() {
        runCatching { sink.close() }
        delegate.close()
    }
}

// =============================================================================
// DTOs — what we actually serialize to disk
// =============================================================================

/**
 * One captured turn — what went out, what came back, how long it took.
 *
 * The JSON shape is deliberately stable: the fixture-bridge
 * (`FakeWeftLLM.fromCapture`) reads these back, so additions must use
 * `default = …` and renames need migration.
 */
@kotlinx.serialization.Serializable
data class WireCapture(
    val turnNumber: Int,
    val requestId: String,
    val timestampMs: Long,
    val durationMs: Long,
    val streaming: Boolean,
    val request: WireRequest,
    val response: WireResponse? = null,
    val error: String? = null,
)

@kotlinx.serialization.Serializable
data class WireRequest(
    val modelId: String,
    val modelProvider: String,
    val systemMessage: String,
    val messages: List<WireMessage>,
    val toolNames: List<String>,
    /**
     * Compact preview of provider-specific options that don't fit the
     * generic shape — cache-control markers, max_tokens, response
     * format, etc. Best-effort: we don't promise lossless capture.
     */
    val providerExtras: Map<String, String> = emptyMap(),
) {
    companion object {
        fun from(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): WireRequest {
            val systemText = prompt.messages
                .filterIsInstance<Message.System>()
                .joinToString("\n") { it.textContent() }
            val rest = prompt.messages
                .filter { it !is Message.System }
                .map { WireMessage.from(it) }
            val provider = model.provider::class.simpleName?.lowercase().orEmpty()
            val extras = buildMap {
                model.contextLength?.let { put("contextLength", it.toString()) }
                model.maxOutputTokens?.let { put("maxOutputTokens", it.toString()) }
                prompt.params.maxTokens?.let { put("paramMaxTokens", it.toString()) }
                prompt.params.temperature?.let { put("paramTemperature", it.toString()) }
            }
            return WireRequest(
                modelId = model.id,
                modelProvider = provider,
                systemMessage = systemText,
                messages = rest,
                toolNames = tools.map { it.name },
                providerExtras = extras,
            )
        }
    }
}

@kotlinx.serialization.Serializable
data class WireMessage(
    val role: String,
    val text: String,
    val toolCalls: List<WireToolCall> = emptyList(),
    val toolResults: List<WireToolResult> = emptyList(),
) {
    companion object {
        fun from(msg: Message): WireMessage {
            val text = msg.textContent()
            val toolCalls = msg.parts
                .filterIsInstance<MessagePart.Tool.Call>()
                .map { WireToolCall(it.id.orEmpty(), it.tool, it.args) }
            val toolResults = msg.parts
                .filterIsInstance<MessagePart.Tool.Result>()
                .map {
                    WireToolResult(
                        callId = it.id.orEmpty(),
                        name = it.tool,
                        content = it.output,
                        isError = it.isError,
                    )
                }
            return WireMessage(
                role = msg.role.name.lowercase(),
                text = text,
                toolCalls = toolCalls,
                toolResults = toolResults,
            )
        }
    }
}

@kotlinx.serialization.Serializable
data class WireToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

@kotlinx.serialization.Serializable
data class WireToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
)

@kotlinx.serialization.Serializable
data class WireResponse(
    val text: String,
    val toolCalls: List<WireToolCall>,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val finishReason: String? = null,
) {
    companion object {
        fun from(msg: Message.Assistant): WireResponse {
            val text = msg.textContent()
            val toolCalls = msg.parts
                .filterIsInstance<MessagePart.Tool.Call>()
                .map { WireToolCall(it.id.orEmpty(), it.tool, it.args) }
            return WireResponse(
                text = text,
                toolCalls = toolCalls,
                inputTokens = msg.metaInfo.inputTokensCount,
                outputTokens = msg.metaInfo.outputTokensCount,
                totalTokens = msg.metaInfo.totalTokensCount,
            )
        }

        /**
         * Build a synthetic [WireResponse] from accumulated streaming
         * frames. We assemble the same shape the non-streaming path
         * produces so the fixture round-trip works regardless of which
         * path produced the capture.
         */
        fun fromFrames(frames: List<StreamFrame>): WireResponse {
            val textBuf = StringBuilder()
            val toolCalls = mutableListOf<WireToolCall>()
            var inputTokens: Int? = null
            var outputTokens: Int? = null
            var totalTokens: Int? = null
            var finish: String? = null
            for (frame in frames) {
                when (frame) {
                    is StreamFrame.TextDelta -> textBuf.append(frame.text)
                    is StreamFrame.TextComplete -> {
                        // TextComplete is sometimes emitted in addition to deltas;
                        // overwrite with the canonical full text when present.
                        textBuf.clear()
                        textBuf.append(frame.text)
                    }
                    is StreamFrame.ToolCallComplete -> {
                        toolCalls += WireToolCall(
                            id = frame.id.orEmpty(),
                            name = frame.name,
                            argsJson = frame.content,
                        )
                    }
                    is StreamFrame.End -> {
                        finish = frame.finishReason
                        inputTokens = frame.metaInfo.inputTokensCount
                        outputTokens = frame.metaInfo.outputTokensCount
                        totalTokens = frame.metaInfo.totalTokensCount
                    }
                    else -> Unit
                }
            }
            return WireResponse(
                text = textBuf.toString(),
                toolCalls = toolCalls,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = totalTokens,
                finishReason = finish,
            )
        }
    }
}
