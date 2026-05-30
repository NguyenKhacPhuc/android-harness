package dev.weft.harness.agents

import ai.koog.prompt.message.MessagePart
import dev.weft.harness.agents.routing.ModelTier

/**
 * Inputs the host can dispatch into a [WeftAgent]. Every imperative
 * action the agent supports — sending a message, regenerating, opening
 * a new chat, cancelling — is one of these.
 *
 * Hosts call [WeftAgent.dispatch] with one of these and observe
 * [WeftAgent.state] / [WeftAgent.effects] for the result. Dispatch is
 * fire-and-forget; the host doesn't await a return value, it tails the
 * state flow.
 *
 * Legacy direct methods ([WeftAgent.send] etc.) are deprecated but
 * still functional — they internally dispatch the equivalent intent so
 * the state surface stays consistent regardless of which API the host
 * uses during the migration window.
 */
public sealed interface AgentIntent {

    /**
     * Run a normal user turn. Equivalent to the legacy
     * [WeftAgent.send] (text + optional attachments + optional tier).
     *
     * `streaming = true` emits text deltas into
     * [AgentState.pendingAssistantDelta] as they arrive (chat UIs);
     * `false` produces no intermediate state — only the final
     * assistant turn appended to [AgentState.history] (server agents,
     * one-shot tasks).
     */
    public data class Send(
        public val text: String,
        public val attachments: List<MessagePart.Attachment> = emptyList(),
        public val tier: ModelTier? = null,
        public val streaming: Boolean = true,
    ) : AgentIntent

    /**
     * Round-trip a UI event from an LLM-rendered surface back into the
     * agent (per ADR-007 §6). Composes a synthetic user message and
     * runs a normal turn. Equivalent to the legacy [WeftAgent.sendEvent].
     */
    public data class SendEvent(
        public val action: String,
        public val sourceLabel: String? = null,
        public val fieldValues: Map<String, String> = emptyMap(),
    ) : AgentIntent

    /**
     * Re-run the most recent user turn. Rolls back the previous
     * user+assistant pair from in-memory history and the conversation
     * store, then re-sends. No-op when there's no USER turn to roll
     * back from.
     *
     * Same streaming semantics as [Send].
     */
    public data class Regenerate(
        public val streaming: Boolean = true,
    ) : AgentIntent

    /**
     * Start a fresh thread. Clears in-memory history; if a
     * [dev.weft.harness.conversation.ConversationStore] is wired,
     * creates a new conversation row and switches
     * [AgentState.conversationId] to it.
     */
    public data object NewChat : AgentIntent

    /**
     * Hydrate in-memory history from the persistent store. Passing
     * `conversationId = null` resumes the most-recent thread; an
     * explicit id resumes that specific thread; if neither matches,
     * creates a fresh thread.
     */
    public data class Resume(public val conversationId: String? = null) : AgentIntent

    /**
     * Drop in-memory conversation history. Does NOT touch the
     * persistent store.
     */
    public data object ResetHistory : AgentIntent

    /**
     * Cancel the in-flight turn (if any). The cancelled turn:
     *   - leaves no entry in [AgentState.history]
     *   - leaves no row in the persistent conversation store
     *   - marks the trace as failed via [dev.weft.harness.observability.TraceStore.failTrace]
     *   - emits any captured deltas as a [TurnStatus.Failed] terminal state
     *     with [AgentState.lastError] set to [kotlinx.coroutines.CancellationException]
     *
     * No-op when [AgentState.turnStatus] is already [TurnStatus.Idle]
     * or [TurnStatus.Failed].
     */
    public data object CancelCurrentTurn : AgentIntent

    /**
     * Clear [AgentState.lastError] back to null and, if the agent is
     * stuck in [TurnStatus.Failed], transition it back to
     * [TurnStatus.Idle] so the next [Send] can proceed. Useful for
     * "dismiss error" UI affordances.
     */
    public data object ClearError : AgentIntent
}
