package dev.weft.harness.testing

import dev.weft.contracts.ScriptStorage
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/**
 * Process-local [ScriptStorage]. No TTL enforcement — values stay until
 * explicitly removed or until the instance is garbage-collected. Tests
 * that care about TTL semantics should use the SQLDelight-backed impl.
 */
public class InMemoryScriptStorage : ScriptStorage {
    private val data: MutableMap<String, JsonElement> = mutableMapOf()

    override suspend fun get(key: String): JsonElement? = data[key]
    override suspend fun put(key: String, value: JsonElement, ttl: Duration?) { data[key] = value }
    override suspend fun remove(key: String) { data.remove(key) }
    override suspend fun list(prefix: String): List<String> =
        data.keys.filter { it.startsWith(prefix) }
}
