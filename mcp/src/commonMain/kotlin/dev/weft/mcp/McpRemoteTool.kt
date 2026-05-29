package dev.weft.mcp

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import ai.koog.serialization.typeToken
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A WeftTool that proxies calls to an MCP server. The wire-side
 * args/result are `JsonObject` / `String` — the LLM produces the
 * arguments through Koog's normal tool-call surface, we forward them
 * verbatim to the server, and we render whatever text content comes
 * back as the tool's result.
 *
 * **Permission model.** MCP tools never carry platform permissions. The
 * server might claim a tool called `calendar_write`, but that tool can
 * only call back into its own server's API — it can't touch the device's
 * calendar provider. Platform-permission gating is reserved for tools
 * defined in the host APK.
 *
 * **Destructive flag.** Defaults to `false` because the substrate can't
 * inspect a remote tool's effects ahead of time. Apps can wrap individual
 * MCP tools in a layer that adds the destructive gate if they want to
 * route specific tools through the confirm-destructive dialog.
 *
 * **Naming.** The constructor accepts a `qualifiedName` that's typically
 * `{serverName}:{toolName}` — the namespacing happens in [discoverMcpTools].
 */
public class McpRemoteTool(
    ctx: WeftContext,
    private val client: McpClient,
    private val serverConfig: McpServerConfig,
    private val remoteToolName: String,
    descriptor: ToolDescriptor,
) : WeftTool<JsonObject, String>(
    ctx = ctx,
    argsType = typeToken<JsonObject>(),
    resultType = typeToken<String>(),
    descriptor = descriptor,
    // Conservatively true: the substrate has no way to know an MCP
    // tool's effects ahead of time, and most server-side tools mutate
    // *something* (the server's own state, an external API, …).
    sideEffecting = true,
) {

    override suspend fun executeWeft(args: JsonObject): String {
        val result = client.callTool(serverConfig, remoteToolName, args)
        // Flatten the content list into a single string the LLM consumes.
        // Text blocks concatenate; images / resources pass through with
        // a placeholder so the LLM at least sees what kind of payload
        // came back.
        return result.content.joinToString(separator = "\n\n") { block ->
            when (block) {
                is McpContent.Text -> block.text
                is McpContent.Image -> "[image: ${block.mimeType}, ${block.data.length} chars base64]"
                is McpContent.Resource -> "[resource: ${block.resource}]"
            }
        }.ifEmpty { "(empty result)" }
    }
}

/**
 * Translate an MCP server's JSON Schema for a tool's input into Koog's
 * [ToolDescriptor]. The translation covers the subset of JSON Schema
 * most MCP tools use in practice:
 *
 *   - Top-level `type: object` with `properties` map
 *   - Per-property `type` of string / integer / number / boolean / array / object
 *   - `description` per property
 *   - Top-level `required` array
 *
 * Things deliberately not handled:
 *   - `oneOf` / `anyOf` / `allOf` — collapsed to `Object`
 *   - `enum` — surfaced in description text but not as a typed parameter
 *   - Nested object schemas — collapsed to `Object`
 *   - Pattern / format / minimum / maximum constraints
 *
 * For unsupported shapes the parameter falls back to a generic
 * [ToolParameterType.Object] so the LLM can still pass *something* —
 * the server validates on its end.
 */
public fun translateToKoogDescriptor(
    qualifiedName: String,
    mcpTool: McpToolDescriptor,
): ToolDescriptor {
    val schema = mcpTool.inputSchema
    val properties = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
    val required: Set<String> = (schema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.toSet()
        .orEmpty()

    val params = properties.map { (paramName, paramSchema) ->
        val obj = paramSchema as? JsonObject ?: JsonObject(emptyMap())
        val type = (obj["type"] as? JsonPrimitive)?.content
        val description = (obj["description"] as? JsonPrimitive)?.content
            ?: "(no description)"
        ToolParameterDescriptor(
            name = paramName,
            description = description.appendEnum(obj),
            type = type.toKoogType(obj),
        ) to (paramName in required)
    }

    return ToolDescriptor(
        name = qualifiedName,
        description = mcpTool.description?.takeIf { it.isNotBlank() }
            ?: "MCP tool '${mcpTool.name}' (remote).",
        requiredParameters = params.filter { it.second }.map { it.first },
        optionalParameters = params.filterNot { it.second }.map { it.first },
    )
}

/** Map a JSON Schema `type` string + the rest of the schema to Koog's parameter type. */
private fun String?.toKoogType(schema: JsonObject): ToolParameterType = when (this) {
    "string" -> ToolParameterType.String
    "integer" -> ToolParameterType.Integer
    "number" -> ToolParameterType.Float
    "boolean" -> ToolParameterType.Boolean
    "array" -> ToolParameterType.List(itemsType = inferArrayItemType(schema))
    "object" -> ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList())
    null -> ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList())
    // Unknown / unsupported (null type, multi-type union) → opaque object.
    else -> ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList())
}

private fun inferArrayItemType(schema: JsonObject): ToolParameterType {
    val items = schema["items"] as? JsonObject ?: return ToolParameterType.String
    val itemType = (items["type"] as? JsonPrimitive)?.content
    return itemType.toKoogType(items)
}

/** Append an "enum: a | b | c" hint to a description when the schema declares one. */
private fun String.appendEnum(schema: JsonObject): String {
    val enum = (schema["enum"] as? JsonArray) ?: return this
    val values = enum.mapNotNull { (it as? JsonPrimitive)?.content }
    if (values.isEmpty()) return this
    return "$this (one of: ${values.joinToString(" | ")})"
}
