package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.DataSourceRegistry
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class DataQueryTool(
    ctx: WeftContext,
    private val sources: DataSourceRegistry,
) : WeftTool<DataQueryTool.Args, DataQueryTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "data_query",
        description = "Read records from a known data source. " +
            "'source' must be one of the registered DataSources for this app.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "source",
                "Data source name. Must be a name registered for this app.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "filter",
                "Optional filter object (source-specific). Pass an empty object to return everything.",
                ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList()),
            ),
            ToolParameterDescriptor(
                "limit",
                "Maximum number of items (1–500). Defaults to 50.",
                ToolParameterType.Integer,
            ),
        ),
    ),
) {

    @Serializable
    data class Args(
        val source: String,
        val filter: JsonObject = JsonObject(emptyMap()),
        val limit: Int = 50,
    )

    @Serializable
    data class Result(
        val items: List<JsonElement>,
        val total: Int,
        val hasMore: Boolean,
    )

    override suspend fun executeWeft(args: Args): Result {
        val ds = sources.get(args.source)
            ?: error("Unknown data source '${args.source}'. Available: ${sources.names().joinToString()}")
        val r = ds.query(filter = args.filter, limit = args.limit.coerceIn(1, 500))
        return Result(items = r.items, total = r.total, hasMore = r.hasMore)
    }
}

class DataUpsertTool(
    ctx: WeftContext,
    private val sources: DataSourceRegistry,
) : WeftTool<DataUpsertTool.Args, DataUpsertTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "data_upsert",
        description = "Create or update a record in a known data source. " +
            "Include an 'id' field in the record to update; omit to create. " +
            "Pass 'idempotencyKey' to make retries safe.",
        requiredParameters = listOf(
            ToolParameterDescriptor("source", "Data source name.", ToolParameterType.String),
            ToolParameterDescriptor(
                "record",
                "The record to write. Include 'id' to update; omit to create.",
                ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList()),
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "idempotencyKey",
                "Optional key — repeat calls with the same key are no-ops.",
                ToolParameterType.String,
            ),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    data class Args(
        val source: String,
        val record: JsonObject,
        val idempotencyKey: String? = null,
    )

    @Serializable
    data class Result(val id: String, val created: Boolean)

    override suspend fun executeWeft(args: Args): Result {
        val ds = sources.get(args.source)
            ?: error("Unknown data source '${args.source}'. Available: ${sources.names().joinToString()}")
        val r = ds.upsert(record = args.record, idempotencyKey = args.idempotencyKey)
        return Result(id = r.id, created = r.created)
    }
}
