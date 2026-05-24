package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.contracts.DataSourceRegistry
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import ai.koog.serialization.typeToken

/**
 * Delete a record from a registered data source.
 *
 * destructive=true so the substrate runs the user-confirmation gate before
 * the actual delete. Even if the LLM "decided" to delete, the user gets the
 * "are you sure?" prompt automatically.
 */
public class DataDeleteTool(
    ctx: WeftContext,
    private val sources: DataSourceRegistry,
) : WeftTool<DataDeleteTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "data_delete",
        description = "Delete a record from a known data source. " +
            "The user is automatically asked to confirm before deletion.",
        requiredParameters = listOf(
            ToolParameterDescriptor("source", "Data source name.", ToolParameterType.String),
            ToolParameterDescriptor("id", "Id of the record to delete.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
    destructive = true,
    sideEffecting = true,
) {

    @Serializable
    public data class Args(val source: String, val id: String)

    override suspend fun executeWeft(args: Args): String {
        val ds = sources.get(args.source)
            ?: error("Unknown data source '${args.source}'. Available: ${sources.names().joinToString()}")
        val deleted = ds.delete(args.id)
        return if (deleted) "Deleted ${args.id} from ${args.source}" else "No record with id ${args.id} in ${args.source}"
    }
}
