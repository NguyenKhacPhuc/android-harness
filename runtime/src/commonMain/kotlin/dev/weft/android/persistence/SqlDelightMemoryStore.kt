package dev.weft.android.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.weft.harness.memory.MemoryEntry
import dev.weft.harness.memory.MemoryScope
import dev.weft.harness.memory.MemoryStore
import dev.weft.android.db.Memories
import dev.weft.android.db.WeftDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * SQLDelight-backed [MemoryStore]. Memories survive app restart.
 *
 * Tags are persisted as a JSON array column for v1 — substring search
 * uses `LIKE` on content + tags. FTS5 is a future enhancement (see
 * docs/follow-ups.md).
 *
 * `memories` is a [StateFlow] backed by a SQLDelight query Flow so the
 * `AgentMemoriesScreen` updates immediately when entries are added,
 * deleted, or cleared from any thread.
 */
public class SqlDelightMemoryStore(
    private val db: WeftDatabase,
    coroutineScope: CoroutineScope,
) : MemoryStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    public override val memories: StateFlow<List<MemoryEntry>> = db.memoriesQueries
        .selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> rows.map { it.toEntry() } }
        .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
    public override suspend fun store(content: String, tags: List<String>, scope: MemoryScope): MemoryEntry {
        require(scope != MemoryScope.ANY) { "Cannot store with scope=ANY — pick SESSION or PERMANENT" }
        val entry = MemoryEntry(
            id = "mem-${Uuid.random().toHexString().take(MEMORY_ID_LEN)}",
            content = content,
            tags = tags.distinct(),
            scope = scope,
            storedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
        db.memoriesQueries.insert(
            id = entry.id,
            content = entry.content,
            tags_json = json.encodeToString(entry.tags),
            scope = entry.scope.name,
            stored_at_ms = entry.storedAtEpochMs,
        )
        return entry
    }

    public override suspend fun recall(
        query: String,
        scope: MemoryScope,
        tags: List<String>,
        limit: Int,
    ): List<MemoryEntry> {
        val effectiveLimit = limit.coerceIn(1, MAX_RECALL_LIMIT).toLong()
        val trimmedQuery = query.trim()
        val rows = if (trimmedQuery.isEmpty()) {
            // Empty query = "give me the most recent N" — bypass FTS entirely
            // since FTS5 rejects empty MATCH and ranking has nothing to rank.
            db.memoriesQueries.selectMatching(
                scope = scope.name,
                query = "",
                limit = effectiveLimit,
            ).executeAsList().map { it.toEntry() }
        } else {
            // FTS5 prefix match — tokenize, sanitize, append `*` to each token.
            // If sanitization strips everything (punctuation-only query), fall
            // back to the LIKE path so the user still gets something.
            val fts = trimmedQuery.toFtsPrefixExpression()
            if (fts.isEmpty()) {
                db.memoriesQueries.selectMatching(
                    scope = scope.name,
                    query = trimmedQuery,
                    limit = effectiveLimit,
                ).executeAsList().map { it.toEntry() }
            } else {
                db.memoriesQueries.selectMatchingFts(
                    scope = scope.name,
                    query = fts,
                    limit = effectiveLimit,
                ).executeAsList().map { it.toEntry() }
            }
        }
        if (tags.isEmpty()) return rows
        val tagFilter = tags.map { it.lowercase() }.toSet()
        return rows.filter { entry -> entry.tags.any { t -> t.lowercase() in tagFilter } }
    }

    public override suspend fun delete(id: String): Boolean {
        // SQLDelight's basic delete doesn't return affected row count;
        // do the existence check + delete in a transaction for atomicity.
        return db.memoriesQueries.transactionWithResult {
            val existed = db.memoriesQueries.selectAll().executeAsList().any { it.id == id }
            if (existed) db.memoriesQueries.deleteById(id)
            existed
        }
    }

    public override suspend fun clear(scope: MemoryScope) {
        when (scope) {
            MemoryScope.ANY -> db.memoriesQueries.deleteAll()
            else -> db.memoriesQueries.deleteByScope(scope.name)
        }
    }

    private fun Memories.toEntry(): MemoryEntry = MemoryEntry(
        id = id,
        content = content,
        tags = json.decodeFromString(tags_json),
        scope = MemoryScope.valueOf(scope),
        storedAtEpochMs = stored_at_ms,
    )

    public companion object {
        public const val MAX_RECALL_LIMIT: Int = 50
        private const val MEMORY_ID_LEN: Int = 12
    }
}
