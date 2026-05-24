package dev.weft.harness.conversation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Process-local [ConversationStore]. Newest thread first by last activity;
 * lexical substring search across titles and message bodies (ASCII
 * case-insensitive). Loses all data on process exit — fine for tests and
 * stubs, never use in production. Apps that want persistence reach for
 * `SqlDelightConversationStore` in `:substrate:android`.
 *
 * Title behavior matches the SqlDelight impl: lazily set from the first
 * USER turn (truncated to [TITLE_MAX_LEN] chars), never overwritten once
 * non-blank. Retention is capped at [maxConversations]; the oldest threads
 * by last-activity are dropped when a new one would exceed the cap.
 *
 * Not thread-safe in the multi-writer sense — mutations are serialized
 * through `MutableStateFlow.update`, but the message map is mutated by
 * reference. Use one instance per logical "user" / test.
 */
public class InMemoryConversationStore(
    private val maxConversations: Int = DEFAULT_MAX_CONVERSATIONS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConversationStore {

    private val _conversations: MutableStateFlow<List<ConversationSummary>> =
        MutableStateFlow(emptyList())

    /** Per-thread message lists keyed by conversation id. Mutated under [lock]. */
    private val messagesByThread: MutableMap<String, MutableList<PersistedMessage>> = mutableMapOf()
    private val lock = Any()

    public override val conversations: StateFlow<List<ConversationSummary>> =
        _conversations.asStateFlow()

    public override fun search(query: String): Flow<List<ConversationSummary>> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return _conversations
        return _conversations.map { summaries ->
            synchronized(lock) {
                summaries.filter { s ->
                    s.title.lowercase().contains(needle) ||
                        messagesByThread[s.id]?.any { m -> m.content.lowercase().contains(needle) } == true
                }
            }
        }
    }

    public override suspend fun mostRecentConversationId(): String? =
        _conversations.value.firstOrNull()?.id

    public override suspend fun newConversation(): String {
        val id = "conv-${UUID.randomUUID().toString().take(CONVERSATION_ID_LEN)}"
        val now = clock()
        synchronized(lock) {
            messagesByThread[id] = mutableListOf()
            val withNew = listOf(
                ConversationSummary(id = id, title = "", createdAtMs = now, lastMessageAtMs = now),
            ) + _conversations.value
            val capped = if (withNew.size > maxConversations) {
                val kept = withNew.sortedByDescending { it.lastMessageAtMs }.take(maxConversations)
                val keptIds = kept.mapTo(mutableSetOf()) { it.id }
                // Evict messages for dropped threads.
                val droppedIds = messagesByThread.keys - keptIds
                droppedIds.forEach { messagesByThread.remove(it) }
                kept
            } else withNew
            _conversations.value = capped.sortedByDescending { it.lastMessageAtMs }
        }
        return id
    }

    public override fun messagesFor(conversationId: String): Flow<List<PersistedMessage>> =
        _conversations.map {
            synchronized(lock) { messagesByThread[conversationId]?.toList().orEmpty() }
        }

    public override suspend fun loadMessages(conversationId: String): List<PersistedMessage> =
        synchronized(lock) { messagesByThread[conversationId]?.toList().orEmpty() }

    public override suspend fun append(
        conversationId: String,
        role: PersistedRole,
        content: String,
        agentName: String,
    ) {
        val now = clock()
        val msgId = "msg-${UUID.randomUUID().toString().take(MESSAGE_ID_LEN)}"
        synchronized(lock) {
            val msgs = messagesByThread[conversationId] ?: return  // unknown thread — no-op
            val nextSeq = (msgs.maxOfOrNull { it.seq } ?: -1L) + 1L
            msgs += PersistedMessage(
                id = msgId,
                conversationId = conversationId,
                seq = nextSeq,
                role = role,
                content = content,
                createdAtMs = now,
                agentName = agentName,
            )
            _conversations.value = _conversations.value.map { s ->
                if (s.id != conversationId) s
                else s.copy(
                    lastMessageAtMs = now,
                    title = if (s.title.isBlank() && role == PersistedRole.USER) {
                        content.lineSequence().firstOrNull().orEmpty().take(TITLE_MAX_LEN).trim()
                    } else s.title,
                )
            }.sortedByDescending { it.lastMessageAtMs }
        }
    }

    public override suspend fun deleteConversation(conversationId: String) {
        synchronized(lock) {
            messagesByThread.remove(conversationId)
            _conversations.value = _conversations.value.filterNot { it.id == conversationId }
        }
    }

    public override suspend fun deleteLastTurn(conversationId: String): Boolean {
        synchronized(lock) {
            val msgs = messagesByThread[conversationId] ?: return false
            val lastUserIdx = msgs.indexOfLast { it.role == PersistedRole.USER }
            if (lastUserIdx == -1) return false
            // Drop everything from lastUserIdx onward (the user message
            // plus any assistant reply that followed it).
            while (msgs.size > lastUserIdx) msgs.removeAt(msgs.size - 1)
            // Reset last_message_at to the new tail (or to createdAt when
            // the thread is now empty) so list ordering reflects the
            // rollback rather than showing this thread as "just active."
            val tailAt = msgs.lastOrNull()?.createdAtMs
            _conversations.value = _conversations.value.map { s ->
                if (s.id != conversationId) s
                else s.copy(lastMessageAtMs = tailAt ?: s.createdAtMs)
            }.sortedByDescending { it.lastMessageAtMs }
            return true
        }
    }

    public override suspend fun clearAll() {
        synchronized(lock) {
            messagesByThread.clear()
            _conversations.value = emptyList()
        }
    }

    public companion object {
        public const val DEFAULT_MAX_CONVERSATIONS: Int = 50
        private const val CONVERSATION_ID_LEN: Int = 12
        private const val MESSAGE_ID_LEN: Int = 12
        private const val TITLE_MAX_LEN: Int = 64
    }
}
