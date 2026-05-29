package dev.weft.android.persistence

import dev.weft.contracts.ScriptStorage
import dev.weft.osbridge.notifications.StringKeyStore
import dev.weft.android.db.WeftDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/**
 * SQLDelight-backed implementations of the two key-value-shaped stores
 * the substrate uses internally:
 *
 *   - [SqlDelightScriptStorage] — per-tool persistent storage for
 *     idempotency keys, polling cursors, transient state.
 *   - [SqlDelightScheduledNotificationKeyStore] — the [StringKeyStore]
 *     [ScheduledNotificationStore] uses for survives-restart
 *     notification persistence.
 *
 * Both share one `key_value` table, distinguished by a `namespace`
 * column. TTL is honored on read (lazy eviction); a background sweeper
 * isn't worth it at this scale.
 */

/**
 * Persistent [ScriptStorage] used by every [WeftTool]. Stored as
 * JSON strings; the `JsonElement` API stays the same as
 * [InMemoryScriptStorage].
 *
 * Each tool gets its own namespace (the tool's `name`), so two tools
 * can use the same key without collision.
 */
public class SqlDelightScriptStorage(
    private val db: WeftDatabase,
    private val namespace: String,
) : ScriptStorage {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun get(key: String): JsonElement? {
        val row = db.keyValueQueries.get(namespace, key).executeAsOneOrNull() ?: return null
        if (row.expires_at_ms > 0 && row.expires_at_ms < System.currentTimeMillis()) {
            db.keyValueQueries.remove(namespace, key)
            return null
        }
        return runCatching { json.parseToJsonElement(row.value_) }.getOrNull()
    }

    override suspend fun put(key: String, value: JsonElement, ttl: Duration?) {
        val expiresAt = if (ttl != null) {
            System.currentTimeMillis() + ttl.inWholeMilliseconds
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
 * Persistent [StringKeyStore] — what `ScheduledNotificationStore`
 * needs to survive app restart. All entries live under namespace
 * [NAMESPACE].
 */
public class SqlDelightScheduledNotificationKeyStore(
    private val db: WeftDatabase,
) : StringKeyStore {

    override fun get(key: String): String? =
        db.keyValueQueries.get(NAMESPACE, key).executeAsOneOrNull()?.value_

    override fun put(key: String, value: String) {
        db.keyValueQueries.put(
            namespace = NAMESPACE,
            key = key,
            value_ = value,
            expires_at_ms = 0L,
        )
    }

    override fun remove(key: String): Boolean {
        val existed = get(key) != null
        if (existed) db.keyValueQueries.remove(NAMESPACE, key)
        return existed
    }

    override fun keys(prefix: String): List<String> =
        db.keyValueQueries.listKeys(NAMESPACE, prefix).executeAsList()

    public companion object {
        public const val NAMESPACE: String = "scheduled_notifications"
    }
}

/**
 * Sweep TTL-expired rows across the entire `key_value` table — covers
 * every namespace ([SqlDelightScriptStorage] per-tool namespaces + the
 * scheduled-notification namespace) in one DELETE.
 *
 * Lazy eviction happens on every [SqlDelightScriptStorage.get], so values
 * that are read keep the table tidy automatically. This function is the
 * cleanup for values that were written-with-TTL and then never read
 * again (idempotency keys for one-shot operations, abandoned cursors).
 * Call it at substrate construction so accumulated cruft doesn't
 * outlive its usefulness.
 */
public fun pruneExpiredKeyValues(
    db: WeftDatabase,
    nowMs: Long = System.currentTimeMillis(),
) {
    db.keyValueQueries.deleteExpired(nowMs)
}

