package dev.weft.android.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.weft.harness.observability.AgentTrace
import dev.weft.harness.observability.LlmCallTrace
import dev.weft.harness.observability.ToolCallTrace
import dev.weft.harness.observability.ToolStatus
import dev.weft.harness.observability.TraceFeedback
import dev.weft.harness.observability.TraceStatus
import dev.weft.harness.observability.TraceStore
import dev.weft.android.db.WeftDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * SQLDelight-backed [TraceStore]. Each user turn writes one row into
 * `traces` and zero-or-more rows into `llm_calls` and `tool_calls`.
 *
 * The `traces` StateFlow combines the three table flows, so any insert
 * or update in any of them ripples through to the viewer immediately.
 * That's a re-aggregation on every event, which is fine at the cap
 * we enforce ([maxTraces], default 100): the join is small and SQLite
 * is plenty fast.
 *
 * Cap policy: when a new trace pushes us past [maxTraces], the oldest
 * traces (by `start_epoch_ms`) are deleted. Children of deleted traces
 * are deleted in the same transaction (we don't rely on SQLite's FK
 * cascade since the driver doesn't enable `foreign_keys = ON`).
 */
public class SqlDelightTraceStore(
    private val db: WeftDatabase,
    coroutineScope: CoroutineScope,
    private val maxTraces: Int = DEFAULT_MAX_TRACES,
) : TraceStore {

    override val traces: StateFlow<List<AgentTrace>> = combine(
        db.tracesQueries.selectAllTraces().asFlow().mapToList(Dispatchers.IO),
        db.tracesQueries.selectAllLlmCalls().asFlow().mapToList(Dispatchers.IO),
        db.tracesQueries.selectAllToolCalls().asFlow().mapToList(Dispatchers.IO),
    ) { traceRows, llmRows, toolRows ->
        val llmByTrace = llmRows.groupBy { it.trace_id }
        val toolByTrace = toolRows.groupBy { it.trace_id }
        traceRows.map { row ->
            AgentTrace(
                id = row.id,
                conversationId = row.conversation_id,
                startEpochMs = row.start_epoch_ms,
                endEpochMs = row.end_epoch_ms,
                userMessage = row.user_message,
                finalAssistantMessage = row.final_assistant_message,
                llmCalls = llmByTrace[row.id].orEmpty().map { l ->
                    LlmCallTrace(
                        id = l.id,
                        startEpochMs = l.start_epoch_ms,
                        endEpochMs = l.end_epoch_ms,
                        model = l.model,
                        inputTokens = l.input_tokens?.toInt(),
                        outputTokens = l.output_tokens?.toInt(),
                        totalTokens = l.total_tokens?.toInt(),
                        cacheReadTokens = l.cache_read_tokens?.toInt(),
                        cacheWriteTokens = l.cache_write_tokens?.toInt(),
                    )
                },
                toolCalls = toolByTrace[row.id].orEmpty().map { t ->
                    ToolCallTrace(
                        id = t.id,
                        startEpochMs = t.start_epoch_ms,
                        endEpochMs = t.end_epoch_ms,
                        toolName = t.tool_name,
                        argsPreview = t.args_preview,
                        resultPreview = t.result_preview,
                        status = ToolStatus.valueOf(t.status),
                        errorMessage = t.error_message,
                    )
                },
                status = TraceStatus.valueOf(row.status),
                errorMessage = row.error_message,
                feedback = TraceFeedback.valueOf(row.feedback),
                parentTraceId = row.parent_trace_id,
            )
        }
    }.stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    override suspend fun startTrace(
        conversationId: String,
        userMessage: String,
        parentTraceId: String?,
    ): String {
        val id = newId("trace")
        db.tracesQueries.transaction {
            db.tracesQueries.insertTrace(
                id = id,
                conversation_id = conversationId,
                start_epoch_ms = System.currentTimeMillis(),
                user_message = userMessage,
                parent_trace_id = parentTraceId,
            )
            // Cap policy: drop oldest beyond maxTraces. Children cascade
            // via explicit delete (FK pragma isn't enabled — see class KDoc).
            val toEvict = db.tracesQueries.selectOldestTraceIdsBeyond(maxTraces.toLong())
                .executeAsList()
            for (oldId in toEvict) {
                deleteTraceAndChildren(oldId)
            }
        }
        return id
    }

    override suspend fun completeTrace(traceId: String, finalAssistantMessage: String?) {
        db.tracesQueries.completeTrace(
            endEpochMs = System.currentTimeMillis(),
            finalMessage = finalAssistantMessage,
            id = traceId,
        )
    }

    override suspend fun failTrace(traceId: String, errorMessage: String) {
        db.tracesQueries.failTrace(
            endEpochMs = System.currentTimeMillis(),
            errorMessage = errorMessage,
            id = traceId,
        )
    }

    override suspend fun recordLlmStart(traceId: String, model: String): String {
        val id = newId("llm")
        db.tracesQueries.transaction {
            val seq = db.tracesQueries.nextLlmSeq(traceId).executeAsOne()
            db.tracesQueries.insertLlmStart(
                id = id,
                trace_id = traceId,
                seq = seq,
                start_epoch_ms = System.currentTimeMillis(),
                model = model,
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
        db.tracesQueries.completeLlmCall(
            endEpochMs = System.currentTimeMillis(),
            inputTokens = inputTokens?.toLong(),
            outputTokens = outputTokens?.toLong(),
            totalTokens = totalTokens?.toLong(),
            cacheReadTokens = cacheReadTokens?.toLong(),
            cacheWriteTokens = cacheWriteTokens?.toLong(),
            id = llmCallId,
        )
    }

    override suspend fun recordToolStart(
        traceId: String,
        toolName: String,
        argsPreview: String,
    ): String {
        val id = newId("tool")
        db.tracesQueries.transaction {
            val seq = db.tracesQueries.nextToolSeq(traceId).executeAsOne()
            db.tracesQueries.insertToolStart(
                id = id,
                trace_id = traceId,
                seq = seq,
                start_epoch_ms = System.currentTimeMillis(),
                tool_name = toolName,
                args_preview = argsPreview,
            )
        }
        return id
    }

    override suspend fun recordToolComplete(
        traceId: String,
        toolCallId: String,
        resultPreview: String?,
    ) {
        db.tracesQueries.completeToolCall(
            endEpochMs = System.currentTimeMillis(),
            resultPreview = resultPreview,
            id = toolCallId,
        )
    }

    override suspend fun recordToolFailed(
        traceId: String,
        toolCallId: String,
        errorMessage: String,
    ) {
        db.tracesQueries.failToolCall(
            endEpochMs = System.currentTimeMillis(),
            errorMessage = errorMessage,
            id = toolCallId,
        )
    }

    override suspend fun setFeedback(traceId: String, feedback: TraceFeedback) {
        db.tracesQueries.setFeedback(feedback = feedback.name, id = traceId)
    }

    override suspend fun clear() {
        // Delete children first, then parents. We don't rely on SQLite's
        // FK cascade (the driver doesn't enable `foreign_keys = ON`), so
        // we cascade in Kotlin. Wrapped in one transaction so the viewer
        // sees a single atomic flip to empty.
        db.tracesQueries.transaction {
            db.tracesQueries.deleteAllLlmCalls()
            db.tracesQueries.deleteAllToolCalls()
            db.tracesQueries.deleteAllTraces()
        }
    }

    /** Delete a single trace and its children. Caller is inside a transaction. */
    private fun deleteTraceAndChildren(traceId: String) {
        db.tracesQueries.deleteLlmCallsByTrace(traceId)
        db.tracesQueries.deleteToolCallsByTrace(traceId)
        db.tracesQueries.deleteTraceById(traceId)
    }

    private fun newId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(ID_LEN)}"

    public companion object {
        public const val DEFAULT_MAX_TRACES: Int = 100
        private const val ID_LEN: Int = 8
    }
}
