package dev.weft.harness.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Persistent storage for agent-curated memories.
 *
 * Per [ADR-002](../../../../../../../docs/adr/ADR-002-explicit-memory-only.md)
 * the substrate ships explicit-only memory in v1:
 *   - Memories are stored ONLY when the LLM calls `memory_store`.
 *   - Every stored memory is visible in the AgentMemoriesScreen.
 *   - The user can delete individually or wipe everything.
 *   - No background inference, no silent persistence.
 *
 * Lexical substring search ships as the v1 recall mechanism (FTS5 lands
 * with the SQLDelight persistence pass — see `docs/follow-ups.md`).
 * Embedding-based semantic recall is v1.1.
 */
interface MemoryStore {

    /** Snapshot of all stored memories, newest first. */
    val memories: StateFlow<List<MemoryEntry>>

    suspend fun store(content: String, tags: List<String>, scope: MemoryScope): MemoryEntry

    /**
     * Substring search across content. Filters by scope and tags. Returns
     * up to [limit] matches, most recent first.
     */
    suspend fun recall(
        query: String,
        scope: MemoryScope = MemoryScope.ANY,
        tags: List<String> = emptyList(),
        limit: Int = 5,
    ): List<MemoryEntry>

    suspend fun delete(id: String): Boolean

    suspend fun clear(scope: MemoryScope = MemoryScope.ANY)
}

@Serializable
data class MemoryEntry(
    val id: String,
    val content: String,
    val tags: List<String>,
    val scope: MemoryScope,
    val storedAtEpochMs: Long,
)

@Serializable
enum class MemoryScope {
    /** Scoped to the current chat session. Cleared on app restart (today) or session end (with persistence). */
    SESSION,

    /** Persistent across sessions. Survives app restarts (when persistence lands). */
    PERMANENT,

    /** Match either scope on recall. Only valid for recall(), not for store(). */
    ANY,
}

/**
 * In-memory implementation. Newest first; case-insensitive substring
 * matching. Persistence (SQLDelight + FTS5) lands in the persistence pass
 * tracked in `docs/follow-ups.md`.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class InMemoryMemoryStore : MemoryStore {

    private val _memories: MutableStateFlow<List<MemoryEntry>> = MutableStateFlow(emptyList())
    override val memories: StateFlow<List<MemoryEntry>> = _memories.asStateFlow()

    override suspend fun store(content: String, tags: List<String>, scope: MemoryScope): MemoryEntry {
        require(scope != MemoryScope.ANY) { "Cannot store with scope=ANY — pick SESSION or PERMANENT" }
        val entry = MemoryEntry(
            id = "mem-${Uuid.random().toString().take(12)}",
            content = content,
            tags = tags.distinct(),
            scope = scope,
            storedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
        _memories.update { listOf(entry) + it }
        return entry
    }

    override suspend fun recall(query: String, scope: MemoryScope, tags: List<String>, limit: Int): List<MemoryEntry> {
        val needle = query.trim().lowercase()
        val tagFilter = tags.map { it.lowercase() }.toSet()
        return _memories.value.asSequence()
            .filter { scope == MemoryScope.ANY || it.scope == scope }
            .filter { tagFilter.isEmpty() || it.tags.any { t -> t.lowercase() in tagFilter } }
            .filter { needle.isEmpty() || it.content.lowercase().contains(needle) || it.tags.any { t -> t.lowercase().contains(needle) } }
            .take(limit.coerceIn(1, MAX_RECALL_LIMIT))
            .toList()
    }

    override suspend fun delete(id: String): Boolean {
        var removed = false
        _memories.update { list ->
            val filtered = list.filterNot { it.id == id }
            removed = filtered.size != list.size
            filtered
        }
        return removed
    }

    override suspend fun clear(scope: MemoryScope) {
        _memories.update { list ->
            when (scope) {
                MemoryScope.ANY -> emptyList()
                else -> list.filterNot { it.scope == scope }
            }
        }
    }

    companion object {
        const val MAX_RECALL_LIMIT: Int = 50
    }
}
