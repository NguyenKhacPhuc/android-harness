package dev.weft.harness.agents

import dev.weft.harness.behavior.Turn
import dev.weft.harness.cost.QuotaState
import dev.weft.harness.reliability.CircuitBreaker

/**
 * Reactive projection of everything a [WeftAgent] is doing right now.
 *
 * Hosts subscribe to [WeftAgent.state] instead of poking imperative
 * getters. Every meaningful state transition (turn starts, delta
 * arrives, tool fires, error occurs, breaker trips, quota changes,
 * conversation switches) shows up as one new emission of this type.
 *
 * Pair with [WeftAgent.effects] for one-shot transient signals
 * (notifications, breaker-opened) that don't belong on a state object.
 */
public data class AgentState(
    /**
     * Current conversation id. Switches when [AgentIntent.NewChat] or
     * [AgentIntent.Resume] are dispatched. Equivalent to the legacy
     * [WeftAgent.currentConversationId] StateFlow's value.
     */
    public val conversationId: String,
    /**
     * Name of the [AgentDeclaration] this agent was built from.
     * Static for the lifetime of the [WeftAgent] instance.
     */
    public val agentName: String,
    /**
     * Live in-memory turn history. Replaces the formerly-private
     * `MutableList<HistoryEntry>` — chat UIs tail this instead of
     * round-tripping through `ConversationStore.messagesFor()`.
     */
    public val history: List<Turn>,
    /**
     * What the agent is doing in the current turn — `Idle` when there's
     * no in-flight work, otherwise one of the active phases.
     */
    public val turnStatus: TurnStatus,
    /**
     * Streaming-only: the assistant reply built up so far. Empty when
     * [turnStatus] is [TurnStatus.Idle]. Resets at turn start.
     */
    public val pendingAssistantDelta: String,
    /**
     * Tool calls currently in-flight inside the active turn. Cleared
     * back to empty when the turn ends (success or failure).
     */
    public val activeToolCalls: List<ActiveToolCall>,
    /**
     * Last error from the most recent turn. Cleared automatically on
     * the next successful [AgentIntent.Send] / [AgentIntent.Regenerate]
     * — or explicitly via [AgentIntent.ClearError].
     */
    public val lastError: Throwable?,
    /**
     * Current quota state (Ok / Warning / Blocked). Refreshed at the
     * start of every turn; static between turns.
     */
    public val quota: QuotaState,
    /**
     * Live reliability state of the underlying executor. Tracks the
     * embedded [CircuitBreaker.state] StateFlow.
     */
    public val breaker: CircuitBreaker.State,
) {
    public companion object {
        /**
         * Initial state for a freshly-constructed [WeftAgent] before any
         * turn has run.
         */
        public fun initial(
            conversationId: String,
            agentName: String,
            quota: QuotaState = QuotaState.Ok(usdToday = 0.0),
            breaker: CircuitBreaker.State = CircuitBreaker.State.Closed(failureCount = 0),
        ): AgentState = AgentState(
            conversationId = conversationId,
            agentName = agentName,
            history = emptyList(),
            turnStatus = TurnStatus.Idle,
            pendingAssistantDelta = "",
            activeToolCalls = emptyList(),
            lastError = null,
            quota = quota,
            breaker = breaker,
        )
    }
}

/**
 * Where the agent is inside the turn lifecycle.
 *
 *  - [Idle] — no in-flight work; ready to accept the next intent.
 *  - [Sending] — turn dispatched, quota/hooks have fired, awaiting the
 *    first byte from the executor.
 *  - [Streaming] — text deltas are arriving and being appended to
 *    [AgentState.pendingAssistantDelta]. Streaming sends spend most of
 *    their wall-clock time here.
 *  - [ToolRunning] — the executor paused text emission to dispatch one
 *    or more tools. May re-enter [Streaming] when tools complete.
 *  - [Failed] — terminal error state for the turn. The error itself
 *    lives in [AgentState.lastError]. Stays here until cleared.
 */
public enum class TurnStatus {
    Idle,
    Sending,
    Streaming,
    ToolRunning,
    Failed,
}

/**
 * Snapshot of an in-flight tool call. Listed in
 * [AgentState.activeToolCalls] from the moment Koog fires
 * `onToolCallStarting` until the matching `onToolCallCompleted` /
 * `onToolCallFailed` removes it.
 */
public data class ActiveToolCall(
    /**
     * Koog-issued correlation id (`toolCallId` when present, else
     * `eventId`). Stable for the lifetime of the call so hosts can
     * key UI bubbles by it.
     */
    public val id: String,
    public val toolName: String,
    /**
     * Redacted, truncated args preview — same value that goes into
     * [dev.weft.harness.observability.TraceStore] and the legacy
     * [ToolEvent.Starting.argsPreview].
     */
    public val argsPreview: String,
)
