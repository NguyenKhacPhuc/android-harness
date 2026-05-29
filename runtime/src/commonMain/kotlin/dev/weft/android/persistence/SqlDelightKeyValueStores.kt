package dev.weft.android.persistence

import dev.weft.contracts.ScriptStorage
import dev.weft.android.db.WeftDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * SQLDelight-backed [ScriptStorage] used by every [WeftTool]. Stored as
 * JSON strings; the `JsonElement` API stays the same as
 * [InMemoryScriptStorage].
 *
 * Each tool gets its own namespace (the tool's `name`), so two tools
 * can use the same key without collision.
 *
 * Backed by the shared `key_value` table; TTL is honored on read (lazy
 * eviction), and [pruneExpiredKeyValues] sweeps the rest in bulk.
 */
public class SqlDelightScriptStorage(
    private val db: WeftDatabase,
    private val namespace: String,
) : ScriptStorage {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @OptIn(ExperimentalTime::class)
    override suspend fun get(key: String): JsonElement? {
        val row = db.keyValueQueries.get(namespace, key).executeAsOneOrNull() ?: return null
        if (row.expires_at_ms > 0 && row.expires_at_ms < Clock.System.now().toEpochMilliseconds()) {
            db.keyValueQueries.remove(namespace, key)
            return null
        }
        return runCatching { json.parseToJsonElement(row.value_) }.getOrNull()
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun put(key: String, value: JsonElement, ttl: Duration?) {
        val expiresAt = if (ttl != null) {
            Clock.System.now().toEpochMilliseconds() + ttl.inWholeMilliseconds
        } else 0L
        db.keyValueQueries.put(
            namespace = namespace,
            key = key,
            value_ = json.encodeToString(JsonElement.serializer(), value),
            expires_at_ms = expiresAt,
        )
    }

    override suspend fun remove(key: String) {
        db.keyValueQueries.remove(namespace, key)
    }

    override suspend fun list(prefix: String): List<String> =
        db.keyValueQueries.listKeys(namespace, prefix).executeAsList()
}

/**
 * Sweep TTL-expired rows across the entire `key_value` table — covers
 * every namespace ([SqlDelightScriptStorage] per-tool namespaces + the
 * scheduled-notification namespace) in one DELETE.
 *
 * Lazy eviction happens on every [SqlDelightScriptStorage.get], so
 * values that are read keep the table tidy automatically. This function
 * is the cleanup for values that were written-with-TTL and then never
 * read again (idempotency keys for one-shot operations, abandoned
 * cursors). Call it at substrate construction so accumulated cruft
 * doesn't outlive its usefulness.
 */
@OptIn(ExperimentalTime::class)
public fun pruneExpiredKeyValues(
    db: WeftDatabase,
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
) {
    db.keyValueQueries.deleteExpired(nowMs)
}
