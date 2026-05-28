package dev.weft.tools

import dev.weft.contracts.ResolvedTool
import dev.weft.contracts.ToolMetadata
import dev.weft.contracts.ToolProvider

/**
 * Back-compat shim — wraps a list of already-constructed [WeftTool]s
 * as a [ToolProvider]. Every wrapped tool is `alwaysOn` by default
 * (matching the pre-Stage-2 eager-catalog behavior), unless
 * [defaultAlwaysOn] is set false or per-tool overrides are passed.
 *
 * Host apps that haven't migrated to a lazy provider see zero
 * behavior change — `WeftRuntime.create(toolProvider = ...)` defaults
 * to this wrapping today's prebuilt list.
 *
 * Example:
 * ```kotlin
 * val provider = EagerToolProvider(
 *     tools = listOf(myTool, myOtherTool),
 *     overrides = mapOf("my_other_tool" to ToolMetadataOverride(alwaysOn = false)),
 * )
 * ```
 */
class EagerToolProvider(
    private val tools: List<WeftTool<*, *>>,
    /**
     * If true (default), every wrapped tool is reported with
     * `alwaysOn = true`. Set false when migrating to lazy semantics
     * but you still want the prebuilt tools available without a
     * deeper provider rewrite.
     */
    defaultAlwaysOn: Boolean = true,
    /** Per-tool metadata overrides keyed by descriptor name. */
    private val overrides: Map<String, ToolMetadataOverride> = emptyMap(),
) : ToolProvider {

    private val byName: Map<String, WeftTool<*, *>> =
        tools.associateBy { it.descriptor.name }.also { built ->
            require(built.size == tools.size) {
                val dupes = tools.groupBy { it.descriptor.name }
                    .filterValues { it.size > 1 }
                    .keys
                "EagerToolProvider: duplicate tool names: $dupes"
            }
        }

    override val available: List<ToolMetadata> = tools.map { tool ->
        val ovr = overrides[tool.descriptor.name]
        ToolMetadata(
            name = tool.descriptor.name,
            description = tool.descriptor.description,
            category = ovr?.category,
            alwaysOn = ovr?.alwaysOn ?: defaultAlwaysOn,
        )
    }

    override suspend fun resolve(name: String): ResolvedTool? =
        byName[name]?.let(::ResolvedWeftTool)
}

/**
 * Per-tool metadata override for [EagerToolProvider]. Pass on the
 * `overrides` map keyed by tool name to tag a category, flip
 * always-on, or both.
 */
data class ToolMetadataOverride(
    val category: String? = null,
    val alwaysOn: Boolean? = null,
)

/**
 * Compose multiple [ToolProvider]s into a single facade. Each child's
 * [ToolProvider.available] is concatenated; [ToolProvider.resolve]
 * dispatches to the first child whose `available` advertises that name.
 *
 * Throws [IllegalArgumentException] at construction if two providers
 * advertise the same tool name — name collisions are silent corruption
 * (which `resolve` would win? non-deterministic), so we fail loud.
 *
 * Use to combine:
 *   - `SubstrateToolProvider` (the built-in substrate catalog)
 *   - app-supplied provider(s)
 *   - `McpToolProvider` (per-server MCP tools)
 */
fun compositeToolProvider(vararg providers: ToolProvider): ToolProvider =
    CompositeToolProvider(providers.toList())

/** List overload — convenient when assembling providers dynamically. */
fun compositeToolProvider(providers: List<ToolProvider>): ToolProvider =
    CompositeToolProvider(providers)

private class CompositeToolProvider(
    private val children: List<ToolProvider>,
) : ToolProvider {

    init {
        val seen = HashMap<String, Int>()
        children.forEachIndexed { idx, child ->
            for (meta in child.available) {
                val prior = seen[meta.name]
                require(prior == null) {
                    "compositeToolProvider: tool '${meta.name}' advertised by both " +
                        "provider #$prior and provider #$idx"
                }
                seen[meta.name] = idx
            }
        }
    }

    override val available: List<ToolMetadata> =
        children.flatMap { it.available }

    override suspend fun resolve(name: String): ResolvedTool? {
        for (child in children) {
            if (child.available.any { it.name == name }) {
                return child.resolve(name)
            }
        }
        return null
    }
}
