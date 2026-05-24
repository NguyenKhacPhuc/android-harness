package dev.weft.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A named source of structured app data. Implemented by the app, registered
 * with [DataSourceRegistry], invoked by data.* scripts.
 *
 * The substrate doesn't know what records look like — sources are opaque
 * key-value collections from data.*'s perspective. Validation of filter
 * shapes is the app's responsibility.
 */
interface DataSource {
    val name: String

    suspend fun query(
        filter: JsonObject = JsonObject(emptyMap()),
        sort: List<SortSpec> = emptyList(),
        projection: List<String> = emptyList(),
        limit: Int = LIMIT_DEFAULT,
    ): QueryResult

    suspend fun upsert(
        record: JsonObject,
        idempotencyKey: String? = null,
    ): UpsertResult

    suspend fun delete(id: String): Boolean

    companion object {
        const val LIMIT_DEFAULT = 50
        const val LIMIT_MAX = 500
    }
}

@Serializable
data class SortSpec(val field: String, val order: SortOrder = SortOrder.ASC)

@Serializable
enum class SortOrder { ASC, DESC }

@Serializable
data class QueryResult(
    val items: List<JsonElement>,
    val total: Int,
    val hasMore: Boolean = false,
)

@Serializable
data class UpsertResult(val id: String, val created: Boolean)

/**
 * Lookup for named DataSources. The app builds this once at startup with
 * every source it wants to be exposed to the agent.
 */
class DataSourceRegistry(sources: List<DataSource>) {
    private val byName: Map<String, DataSource> =
        sources.associateBy { it.name }
            .also { require(it.size == sources.size) { "Duplicate data source names" } }

    fun get(name: String): DataSource? = byName[name]

    fun names(): Set<String> = byName.keys
}
