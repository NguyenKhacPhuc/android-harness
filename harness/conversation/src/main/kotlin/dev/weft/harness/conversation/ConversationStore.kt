package dev.weft.harness.conversation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-conversation chat history that survives app restart.
 *
 * The substrate's `WeftAgent` writes to this store every turn; the
 * chat UI reads from it to rebuild past bubbles when the app launches.
 * Tool / event bubbles are intentionally out of scope — they're ephemeral
 * chat scaffolding, not conversation content. We persist just the USER
 * and ASSISTANT turns the LLM actually sees.
 *
 * Implementations are free to cap retention; the SqlDelight default keeps
 * the most recent 50 threads (see `SqlDelightConversationStore`). The
 * in-memory ref impl ([InMemoryConversationStore]) is the canonical
 * stub for tests and trivial apps that don't need persistence.
 */
public interface ConversationStore {
    /** All known threads, newest first by last activity. */
    public val conversations: StateFlow<List<ConversationSummary>>

    /**
     * Reactive search across thread titles and message bodies. Empty query
     * returns every thread (same as [conversations]). Case-insensitive
     * substring match on ASCII. Apps that want richer matching (fuzzy,
     * accent-insensitive, FTS) can do so on the client off this flow or
     * write their own SQL.
     */
    public fun search(query: String): Flow<List<ConversationSummary>>

    /** Most recent thread, or null when the store is empty. */
    public suspend fun mostRecentConversationId(): String?

    /** Create a fresh empty thread and return its id. */
    public suspend fun newConversation(): String

    /**
     * Hot snapshot of the turns in [conversationId], in order. Empty list
     * when the id is unknown or new.
     */
    public fun messagesFor(conversationId: String): Flow<List<PersistedMessage>>

    /** One-shot load for restoring agent history at startup. */
    public suspend fun loadMessages(conversationId: String): List<PersistedMessage>

    /**
     * Append a turn. The store handles seq + last-activity bookkeeping.
     * [agentName] identifies which registered agent produced this turn
     * (multi-agent attribution); defaults to `"default"` so single-agent
     * callers don't need to thread anything new.
     */
    public suspend fun append(
        conversationId: String,
        role: PersistedRole,
        content: String,
        agentName: String = DEFAULT_AGENT_NAME,
    )

    /** Drop a single thread and all its messages. */
    public suspend fun deleteConversation(conversationId: String)

    /**
     * Roll back the trailing turn — delete from the most-recent USER
     * message through the end of the thread. Used by
     * [dev.weft.harness.agents.WeftAgent.regenerate] to discard the
     * previous user+assistant pair before re-running the same prompt
     * for a fresh reply.
     *
     * Returns true after a successful delete; false (no-op) when the
     * thread has no USER message to roll back from (empty thread or one
     * that's somehow assistant-only).
     *
     * After the delete the thread's last persisted message is the
     * ASSISTANT reply preceding the deleted user turn (or nothing if
     * the deleted user was the first message). The conversation's
     * `last_message_at_ms` resets to the new tail's createdAt so list
     * ordering reflects the rollback.
     *
     * Seq numbers reassign tightly on the next [append] — no gaps left
     * behind.
     */
    public suspend fun deleteLastTurn(conversationId: String): Boolean

    /** Wipe every thread + message. Used by "clear all history" affordances. */
    public suspend fun clearAll()

    public companion object {
        /**
         * Canonical name for the auto-default agent. Mirrors
         * `AgentDeclaration.DEFAULT_AGENT_NAME` in `:harness:agents` —
         * duplicated here to keep `:harness:conversation` free of a
         * cyclic dependency on `:harness:agents`.
         */
        public const val DEFAULT_AGENT_NAME: String = "default"
    }
}

public data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val lastMessageAtMs: Long,
)

public data class PersistedMessage(
    val id: String,
    val conversationId: String,
    val seq: Long,
    val role: PersistedRole,
    val content: String,
    val createdAtMs: Long,
    /**
     * Which registered agent produced this turn. `"default"` for
     * single-agent apps and any rows that predate the multi-agent
     * registry. Apps render this as a small label next to assistant
     * bubbles when more than one agent is registered.
     */
    val agentName: String = ConversationStore.DEFAULT_AGENT_NAME,
)

public enum class PersistedRole { USER, ASSISTANT }
