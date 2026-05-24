package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.ContextRegistry
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Fetch structured context from one or more registered [ContextProvider]s.
 *
 * The substrate ships `device` (time / timezone / locale / device class).
 * Apps register additional providers — `user_profile`, `subscription`,
 * `active_screen`, etc. — and the LLM discovers them via this tool's
 * description (which lists the currently-registered names at construction
 * time).
 *
 * Returns a JsonObject keyed by provider name; each value is that provider's
 * snapshot. If the LLM asks for an unknown provider, that key is absent and
 * the available names are returned under `_available`.
 */
public class SystemUserContextTool(
    ctx: WeftContext,
    private val contextRegistry: ContextRegistry,
) : WeftTool<SystemUserContextTool.Args, JsonObject>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<JsonObject>(),
    descriptor = ToolDescriptor(
        name = "system_user_context",
        description = "Fetch fresh substrate context, keyed by provider name. " +
            "Available providers depend on the app; common ones include: ${contextRegistry.names().sorted().joinToString()}. " +
            "Use this when you need a value that isn't already in the system prompt, or when you want a fresh read mid-conversation.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "providers",
                "List of provider names to snapshot. Pass empty to snapshot all available providers.",
                ToolParameterType.List(ToolParameterType.String),
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {

    @Serializable
    public data class Args(val providers: List<String> = emptyList())

    override suspend fun executeWeft(args: Args): JsonObject {
        val names = if (args.providers.isEmpty()) contextRegistry.names() else args.providers.toSet()
        val results: Map<String, JsonObject> = names.associateWith { name ->
            contextRegistry.get(name)?.snapshot() ?: buildJsonObject { /* missing */ }
        }
        return buildJsonObject {
            for ((providerName, snapshot) in results) put(providerName, snapshot)
            // Always echo back what was available, so the LLM can correct itself on unknown names.
            put(
                "_available",
                buildJsonArray {
                    for (n in contextRegistry.names().sorted()) add(JsonPrimitive(n))
                },
            )
        }
    }
}
