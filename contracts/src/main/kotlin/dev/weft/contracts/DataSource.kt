package dev.weft.contracts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *
 * ### Reactivity
 *
 * Implementations emit a [Unit] on [changes] every time a mutation
 * (`upsert` / `delete`) lands. The Compose-side data-binding renderer
 * subscribes to this flow so display bindings auto-refresh after a
 * direct tool call — i.e. button taps that execute `data_upsert` via
 * the action-binding path don't need an LLM round-trip to make the
 * numbers on screen update. Implementations that don't (or can't)
 * support live mutation notifications can leave [changes] as an
 * empty flow; the binding renderer falls back to the initial value.
 */
interface DataSource {
    val name: String

    /**
     * Short human-readable label for the agent's system prompt
     * (e.g. "Free-form notes — water logs, mood entries, snippets",
     * "To-do items"). The substrate appends every registered source's
     * `name → description` pair into the system prompt automatically,
     * so apps don't need to hand-document their data layer in the
     * preamble.
     *
     * Empty by default — apps can ignore this on legacy sources, in
     * which case the substrate just lists the name without a description.
     */
    val description: String get() = ""

    /**
     * Emits one [Unit] per mutation (upsert or delete). Subscribers
     * re-query for whatever they need. Implementations should signal
     * AFTER the durable write completes — not optimistically — so
     * subscribers can trust that a re-query will reflect the change.
     */
    val changes: SharedFlow<Unit>

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

        /**
         * Off-the-shelf [changes] flow for implementations that opt
         * out of reactivity. Buffer of zero, no replay — subscribers
         * get nothing until the next mutation, which is the right
         * semantics for an empty change stream.
         */
        val NEVER_CHANGES: SharedFlow<Unit> =
            MutableSharedFlow<Unit>(replay = 0).asSharedFlow()
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
