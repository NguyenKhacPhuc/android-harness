package dev.weft.contracts

import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/**
 * Namespaced key-value storage scoped to a single tool. The substrate
 * provides each tool its own [ScriptStorage] instance keyed by tool name,
 * isolated from every other tool.
 *
 * Used for idempotency keys, polling cursors, retry bookkeeping — small
 * per-tool state. NOT for app data (that goes through the data_* tools
 * and the app's own database).
 */
public interface ScriptStorage {
    public suspend fun get(key: String): JsonElement?
    public suspend fun put(key: String, value: JsonElement, ttl: Duration? = null)
    public suspend fun remove(key: String)
    public suspend fun list(prefix: String = ""): List<String>
}
