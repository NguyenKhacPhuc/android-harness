package dev.weft.harness.observability

import kotlinx.serialization.Serializable

/**
 * A trace of a single user-initiated turn through the agent loop. Captures
 * every LLM round-trip and every tool call so the user (and developer) can
 * inspect "what just happened" in detail.
 *
 * The trace is mutable through the turn — Koog event handlers fill in
 * llmCalls and toolCalls as they happen — and frozen on [TraceStore.completeTrace]
 * or [TraceStore.failTrace].
 */
@Serializable
public data class AgentTrace(
    val id: String,
    val conversationId: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val userMessage: String,
    val finalAssistantMessage: String? = null,
    val llmCalls: List<LlmCallTrace> = emptyList(),
    val toolCalls: List<ToolCallTrace> = emptyList(),
    val status: TraceStatus = TraceStatus.RUNNING,
    val errorMessage: String? = null,
    val feedback: TraceFeedback = TraceFeedback.NONE,
    /**
     * Id of the trace that triggered this one — set on sub-agent traces
     * to link them to the orchestrator's delegation tool call. Null for
     * top-level (user-initiated) traces.
     *
     * Propagated via [TraceContext] through the coroutine context, so
     * any [TraceStore.startTrace] call inside a parent's agent loop
     * automatically inherits the parent's id without explicit
     * parameter threading.
     */
    val parentTraceId: String? = null,
) {
    val durationMs: Long? get() = endEpochMs?.let { it - startEpochMs }

    /** Sum of input/output tokens across all LLM calls in this trace. */
    val totalInputTokens: Int get() = llmCalls.sumOf { it.inputTokens ?: 0 }
    val totalOutputTokens: Int get() = llmCalls.sumOf { it.outputTokens ?: 0 }
    val totalTokens: Int get() = llmCalls.sumOf { it.totalTokens ?: 0 }
}

@Serializable
public data class LlmCallTrace(
    val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val model: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Tokens served from the provider's prompt cache. Null when the
     *  provider didn't report a count (OpenAI when auto-cache misses,
     *  Ollama, …). Anthropic reports this whenever cache_control is in play. */
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
) {
    val durationMs: Long? get() = endEpochMs?.let { it - startEpochMs }
}

@Serializable
public data class ToolCallTrace(
    val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val toolName: String,
    val argsPreview: String,
    val resultPreview: String? = null,
    val status: ToolStatus = ToolStatus.RUNNING,
    val errorMessage: String? = null,
) {
    val durationMs: Long? get() = endEpochMs?.let { it - startEpochMs }
}

@Serializable
public enum class TraceStatus { RUNNING, COMPLETED, FAILED }

@Serializable
public enum class ToolStatus { RUNNING, COMPLETED, FAILED }

/**
 * User-supplied "did this turn do what I wanted" signal. Visible in the
 * TraceViewer and useful for triaging regressions when the substrate ships
 * a feedback-export pipeline.
 */
@Serializable
public enum class TraceFeedback { NONE, THUMBS_UP, THUMBS_DOWN }
