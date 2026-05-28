package dev.weft.harness.observability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Persistence + lookup for [AgentTrace]s. The substrate creates a single
 * instance at startup; WeftAgent calls its lifecycle methods around
 * each turn.
 *
 * The current in-memory implementation keeps the last [maxTraces] traces.
 * SQLite-backed persistence is the natural next step (see plan
 * `docs/07-harness.md:96`).
 */
interface TraceStore {

    /** Snapshot of all currently-known traces, newest first. */
    val traces: StateFlow<List<AgentTrace>>

    /**
     * Start a new trace for [conversationId] / [userMessage]. Returns
     * the trace id.
     *
     * Pass [parentTraceId] to link a sub-agent's trace to the
     * orchestrator's delegation tool call. Top-level (user-initiated)
     * traces leave it null. The DevTools UI uses the link to render
     * nested traces under their parent.
     */
    suspend fun startTrace(
        conversationId: String,
        userMessage: String,
        parentTraceId: String? = null,
    ): String

    /** Mark a trace as completed and record the final assistant reply. */
    suspend fun completeTrace(traceId: String, finalAssistantMessage: String?)

    /** Mark a trace as failed. */
    suspend fun failTrace(traceId: String, errorMessage: String)

    /** Record an LLM-call sub-event. Pass either Start or Complete shape. */
    suspend fun recordLlmStart(traceId: String, model: String): String

    suspend fun recordLlmComplete(
        traceId: String,
        llmCallId: String,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        totalTokens: Int? = null,
        cacheReadTokens: Int? = null,
        cacheWriteTokens: Int? = null,
    )

    suspend fun recordToolStart(traceId: String, toolName: String, argsPreview: String): String

    suspend fun recordToolComplete(traceId: String, toolCallId: String, resultPreview: String?)

    suspend fun recordToolFailed(traceId: String, toolCallId: String, errorMessage: String)

    /** Set or clear feedback on a trace. The viewer shows it as 👍 / 👎. */
    suspend fun setFeedback(traceId: String, feedback: TraceFeedback)

    /** Wipe all traces. User-facing "clear traces" lives behind this. */
    suspend fun clear()
}

/**
 * In-memory implementation. Newest [maxTraces] are kept; older ones are
 * dropped silently. The plan calls for SQLite + size-cap by bytes; this
 * approximation is enough to ship a viewer.
 */
class InMemoryTraceStore(private val maxTraces: Int = DEFAULT_MAX_TRACES) : TraceStore {

    private val _traces: MutableStateFlow<List<AgentTrace>> = MutableStateFlow(emptyList())
    override val traces: StateFlow<List<AgentTrace>> = _traces.asStateFlow()

    override suspend fun startTrace(
        conversationId: String,
        userMessage: String,
        parentTraceId: String?,
    ): String {
        val id = newId("trace")
        val trace = AgentTrace(
            id = id,
            conversationId = conversationId,
            startEpochMs = System.currentTimeMillis(),
            userMessage = userMessage,
            parentTraceId = parentTraceId,
        )
        _traces.update { (listOf(trace) + it).take(maxTraces) }
        return id
    }

    override suspend fun completeTrace(traceId: String, finalAssistantMessage: String?) {
        mutate(traceId) {
            it.copy(
                endEpochMs = System.currentTimeMillis(),
                finalAssistantMessage = finalAssistantMessage,
                status = TraceStatus.COMPLETED,
            )
        }
    }

    override suspend fun failTrace(traceId: String, errorMessage: String) {
        mutate(traceId) {
            it.copy(
                endEpochMs = System.currentTimeMillis(),
                status = TraceStatus.FAILED,
                errorMessage = errorMessage,
            )
        }
    }

    override suspend fun recordLlmStart(traceId: String, model: String): String {
        val id = newId("llm")
        mutate(traceId) {
            it.copy(
                llmCalls = it.llmCalls + LlmCallTrace(
                    id = id,
                    startEpochMs = System.currentTimeMillis(),
                    model = model,
                ),
            )
        }
        return id
    }

    override suspend fun recordLlmComplete(
        traceId: String,
        llmCallId: String,
        inputTokens: Int?,
        outputTokens: Int?,
        totalTokens: Int?,
        cacheReadTokens: Int?,
        cacheWriteTokens: Int?,
    ) {
        mutate(traceId) { t ->
            t.copy(
                llmCalls = t.llmCalls.map { call ->
                    if (call.id == llmCallId) {
                        call.copy(
                            endEpochMs = System.currentTimeMillis(),
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            totalTokens = totalTokens,
                            cacheReadTokens = cacheReadTokens,
                            cacheWriteTokens = cacheWriteTokens,
                        )
                    } else {
                        call
                    }
                },
            )
        }
    }

    override suspend fun recordToolStart(traceId: String, toolName: String, argsPreview: String): String {
        val id = newId("tool")
        mutate(traceId) {
            it.copy(
                toolCalls = it.toolCalls + ToolCallTrace(
                    id = id,
                    startEpochMs = System.currentTimeMillis(),
                    toolName = toolName,
                    argsPreview = argsPreview,
                ),
            )
        }
        return id
    }

    override suspend fun recordToolComplete(traceId: String, toolCallId: String, resultPreview: String?) {
        mutate(traceId) { t ->
            t.copy(
                toolCalls = t.toolCalls.map { call ->
                    if (call.id == toolCallId) {
                        call.copy(
                            endEpochMs = System.currentTimeMillis(),
                            resultPreview = resultPreview,
                            status = ToolStatus.COMPLETED,
                        )
                    } else {
                        call
                    }
                },
            )
        }
    }

    override suspend fun recordToolFailed(traceId: String, toolCallId: String, errorMessage: String) {
        mutate(traceId) { t ->
            t.copy(
                toolCalls = t.toolCalls.map { call ->
                    if (call.id == toolCallId) {
                        call.copy(
                            endEpochMs = System.currentTimeMillis(),
                            status = ToolStatus.FAILED,
                            errorMessage = errorMessage,
                        )
                    } else {
                        call
                    }
                },
            )
        }
    }

    override suspend fun setFeedback(traceId: String, feedback: TraceFeedback) {
        mutate(traceId) { it.copy(feedback = feedback) }
    }

    override suspend fun clear() {
        _traces.value = emptyList()
    }

    private fun mutate(traceId: String, transform: (AgentTrace) -> AgentTrace) {
        _traces.update { list ->
            list.map { if (it.id == traceId) transform(it) else it }
        }
    }

    private fun newId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"

    companion object {
        const val DEFAULT_MAX_TRACES: Int = 100
    }
}
