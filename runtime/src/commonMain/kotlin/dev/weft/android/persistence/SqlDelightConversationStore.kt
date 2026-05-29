@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.weft.android.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.weft.harness.conversation.ConversationStore
import dev.weft.harness.conversation.ConversationSummary
import dev.weft.harness.conversation.PersistedMessage
import dev.weft.harness.conversation.PersistedRole
import dev.weft.android.db.WeftDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * SQLDelight-backed [ConversationStore]. One thread per row in
 * `conversations`; each turn a row in `messages` keyed by `conversation_id`.
 * Cascade delete is enforced in Kotlin since the driver doesn't enable
 * `foreign_keys = ON`.
 *
 * Cap policy: when a new conversation is created, the oldest beyond
 * [maxConversations] are pruned (with their messages) in the same
 * transaction.
 *
 * The store interface + data classes live in `:harness:conversation` so
 * apps/tests can stub them with `InMemoryConversationStore` without
 * pulling in Android + SQLDelight.
 */
public class SqlDelightConversationStore(
    private val db: WeftDatabase,
    coroutineScope: CoroutineScope,
    private val maxConversations: Int = DEFAULT_MAX_CONVERSATIONS,
) : ConversationStore {

    public override val conversations: StateFlow<List<ConversationSummary>> = db.conversationsQueries
        .selectAllConversations()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows ->
            rows.map { ConversationSummary(it.id, it.title, it.created_at_ms, it.last_message_at_ms) }
        }
        .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    public override suspend fun mostRecentConversationId(): String? =
        db.conversationsQueries.selectMostRecentConversation().executeAsOneOrNull()?.id

    public override suspend fun newConversation(): String {
        val id = "conv-${Uuid.random().toHexString().take(CONVERSATION_ID_LEN)}"
        val now = Clock.System.now().toEpochMilliseconds()
        db.conversationsQueries.transaction {
            db.conversationsQueries.insertConversation(id = id, nowMs = now)
            val toEvict = db.conversationsQueries
                .selectOldestConversationIdsBeyond(maxConversations.toLong())
                .executeAsList()
            for (oldId in toEvict) {
                db.conversationsQueries.deleteMessagesByConversation(oldId)
                db.conversationsQueries.deleteConversationById(oldId)
            }
        }
        return id
    }

    public override fun search(query: String): Flow<List<ConversationSummary>> {
        val trimmed = query.trim()
        // FTS5 rejects empty match patterns and prefix-only matches are
        // pointless for an empty query — fast-path to selectAll.
        val source = if (trimmed.isEmpty()) {
            db.conversationsQueries.selectAllConversations().asFlow().mapToList(Dispatchers.Default)
        } else {
            // FTS5 prefix match. We sanitize the user-supplied string into a
            // safe FTS5 expression: strip everything that isn't word-class
            // or whitespace, split into tokens, append `*` to each for prefix
            // matching, and rejoin with spaces (implicit AND in FTS5).
            val fts = trimmed.toFtsPrefixExpression()
            if (fts.isEmpty()) {
                // All sanitized away — punctuation-only query. Title-LIKE
                // only via the existing LIKE-based query.
                db.conversationsQueries.searchConversations(trimmed)
                    .asFlow()
                    .mapToList(Dispatchers.Default)
            } else {
                db.conversationsQueries.searchConversationsFts(like = trimmed, fts = fts)
                    .asFlow()
                    .mapToList(Dispatchers.Default)
            }
        }
        return source.map { rows ->
            rows.map { ConversationSummary(it.id, it.title, it.created_at_ms, it.last_message_at_ms) }
        }
    }

    public override fun messagesFor(conversationId: String): Flow<List<PersistedMessage>> =
        db.conversationsQueries.selectMessagesByConversation(conversationId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map {
                    PersistedMessage(
                        id = it.id,
                        conversationId = it.conversation_id,
                        seq = it.seq,
                        role = PersistedRole.valueOf(it.role),
                        content = it.content,
                        createdAtMs = it.created_at_ms,
                        agentName = it.agent_name,
                    )
                }
            }

    public override suspend fun loadMessages(conversationId: String): List<PersistedMessage> =
        db.conversationsQueries.selectMessagesByConversation(conversationId).executeAsList()
            .map {
                PersistedMessage(
                    id = it.id,
                    conversationId = it.conversation_id,
                    seq = it.seq,
                    role = PersistedRole.valueOf(it.role),
                    content = it.content,
                    createdAtMs = it.created_at_ms,
                )
            }

    public override suspend fun append(
        conversationId: String,
        role: PersistedRole,
        content: String,
        agentName: String,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val msgId = "msg-${Uuid.random().toHexString().take(MESSAGE_ID_LEN)}"
        db.conversationsQueries.transaction {
            val seq = db.conversationsQueries.nextMessageSeq(conversationId).executeAsOne()
            db.conversationsQueries.insertMessage(
                id = msgId,
                conversation_id = conversationId,
                seq = seq,
                role = role.name,
                content = content,
                created_at_ms = now,
                agent_name = agentName,
            )
            db.conversationsQueries.bumpConversationLastMessage(nowMs = now, id = conversationId)
            // Title comes from the first user message, truncated. Lazy-set so
            // an early failed turn doesn't lock in a bad title.
            if (role == PersistedRole.USER) {
                val candidate = content.lineSequence().firstOrNull().orEmpty()
                    .take(TITLE_MAX_LEN)
                    .trim()
                if (candidate.isNotEmpty()) {
                    db.conversationsQueries.updateConversationTitleIfBlank(
                        title = candidate,
                        id = conversationId,
                    )
                }
            }
        }
    }

    public override suspend fun deleteConversation(conversationId: String) {
        db.conversationsQueries.transaction {
            db.conversationsQueries.deleteMessagesByConversation(conversationId)
            db.conversationsQueries.deleteConversationById(conversationId)
        }
    }

    public override suspend fun deleteLastTurn(conversationId: String): Boolean {
        // Load outside the transaction (loadMessages already runs on
        // IO via its own dispatcher hop); compute the rollback target,
        // then do the batch delete in a single transaction.
        val msgs = loadMessages(conversationId)
        val lastUserIdx = msgs.indexOfLast { it.role == PersistedRole.USER }
        if (lastUserIdx == -1) return false
        // Tail timestamp for the conversation's last_message_at — the
        // message just before the deleted user, or the conversation's
        // own createdAt when we emptied the thread.
        val newLastAt = if (lastUserIdx == 0) {
            db.conversationsQueries.selectConversation(conversationId)
                .executeAsOneOrNull()?.created_at_ms ?: Clock.System.now().toEpochMilliseconds()
        } else {
            msgs[lastUserIdx - 1].createdAtMs
        }
        db.conversationsQueries.transaction {
            for (i in lastUserIdx until msgs.size) {
                db.conversationsQueries.deleteMessageById(msgs[i].id)
            }
            db.conversationsQueries.bumpConversationLastMessage(
                nowMs = newLastAt,
                id = conversationId,
            )
        }
        return true
    }

    public override suspend fun clearAll() {
        db.conversationsQueries.transaction {
            db.conversationsQueries.deleteAllMessages()
            db.conversationsQueries.deleteAllConversations()
        }
    }

    public companion object {
        public const val DEFAULT_MAX_CONVERSATIONS: Int = 50
        private const val CONVERSATION_ID_LEN: Int = 12
        private const val MESSAGE_ID_LEN: Int = 12
        private const val TITLE_MAX_LEN: Int = 64
    }
}

/**
 * Convert a free-text search query into a safe FTS5 prefix-match expression.
 *
 *   "  metric  units! "  →  "metric* units*"
 *   "café"                →  "café*"        (unicode61 tokenizer accepts it)
 *   "?!!"                 →  ""              (nothing to match — caller falls back)
 *
 * FTS5 has its own grammar (`AND`, `OR`, `NEAR`, `"phrase"`, `col:`, `*`).
 * Naive concatenation of user input would let a stray `"` or `-` blow up
 * the query or change semantics. Sanitizing to word-class chars + spaces
 * collapses every edge case to a plain whitespace-separated token list,
 * which FTS5 interprets as implicit AND. Each token gets `*` appended for
 * prefix matching (so "metr" finds "metric").
 *
 * Visible to other persistence stores via `internal` (Kotlin module-private).
 */
internal fun String.toFtsPrefixExpression(): String =
    trim()
        .replace(FTS_SANITIZE_REGEX, " ")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { "$it*" }

// Letter/digit/underscore in any Unicode script — matches what unicode61
// would tokenize anyway. Everything else (punctuation, FTS5 operators)
// becomes whitespace and gets split out.
private val FTS_SANITIZE_REGEX: Regex = Regex("[^\\p{L}\\p{N}_]")
