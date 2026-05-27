package dev.weft.contracts

/**
 * Lazy materialization for the substrate's tool catalog. Stage 2 of
 * `docs/architecture/tool-provider.md`.
 *
 * The eager catalog model (every tool constructed at runtime startup,
 * every description embedded into every system prompt) doesn't scale
 * past ~50 tools. A host with 4 MCP integrations + the substrate
 * prelude can reach 80+ tools — paying tens of thousands of tokens
 * per turn for descriptions of tools the LLM won't touch.
 *
 * [ToolProvider] inverts this:
 *
 *   - [available] is the cheap, side-effect-free catalog index used
 *     by `find_tool` (search) and the system-prompt assembler
 *     (display of always-on tools only).
 *   - [resolve] is the materialization point — called once per
 *     (agent, tool) tuple when an activation actually happens. May
 *     return null when a tool is advertised but isn't currently
 *     constructible (network-dependent MCP tool, missing
 *     credential, etc.).
 *
 * Provider authors decide which subset is [ToolMetadata.alwaysOn]
 * (visible in every prompt) vs on-demand (discoverable via
 * `find_tool`). Substrate-internal tools that the LLM relies on
 * being able to call without prompting (memory_*, system_user_context,
 * find_tool itself) are always-on; everything else (camera, file,
 * BLE, MCP, app-domain tools) is on-demand.
 */
public interface ToolProvider {
    /**
     * Lightweight metadata for every tool this provider can produce.
     * Reading this is O(catalog) and side-effect-free — it runs on
     * the hot path of system-prompt assembly + `find_tool`'s search.
     *
     * Implementations should cache aggressively: callers expect this
     * property to be free-or-cheap to read repeatedly.
     */
    public val available: List<ToolMetadata>

    /**
     * Materialize a tool by descriptor name. Called once per (agent,
     * tool) tuple at agent-build time for [ToolMetadata.alwaysOn] tools,
     * and once per activation for on-demand tools.
     *
     * Returns null when the tool was advertised in [available] but
     * isn't currently constructible. Callers handle null gracefully —
     * the tool simply isn't activated; the LLM sees no error.
     *
     * @param name the [ToolMetadata.name] from [available]
     */
    public suspend fun resolve(name: String): ResolvedTool?
}

/**
 * Lightweight descriptor used by [ToolProvider.available]. Doesn't
 * carry any execution capability — just enough information for
 * `find_tool` to rank and for the system-prompt assembler to render
 * a one-line catalog entry.
 *
 * The full execution surface is materialized only when
 * [ToolProvider.resolve] is called.
 */
public data class ToolMetadata(
    /** Stable name. Same as the eventual `ToolDescriptor.name`. */
    public val name: String,

    /** One-paragraph description, lead-with-the-action style. */
    public val description: String,

    /**
     * Optional grouping label used by `find_tool` for category-filtered
     * search ("show me memory tools"). Free-form string; suggested
     * taxonomy is documented in `docs/writing-a-custom-tool.md`.
     */
    public val category: String? = null,

    /**
     * Whether this tool's full descriptor goes into every prompt
     * (`alwaysOn = true`) or is hidden until `find_tool` surfaces it.
     *
     * Default false — most tools are on-demand once Stage 2 ships.
     * Substrate-supplied always-on tools: memory_store, memory_recall,
     * memory_compact, system_user_context, find_tool, plus
     * app-author-tagged "core" tools.
     */
    public val alwaysOn: Boolean = false,
)

/**
 * The materialized tool returned by [ToolProvider.resolve]. Kept
 * opaque at the contract level (the concrete tool type lives in
 * `:tools`) so this module doesn't gain a Koog dependency.
 *
 * Concrete substrate implementation: `dev.weft.tools.ResolvedWeftTool`,
 * which wraps a `WeftTool<*, *>` and exposes both the descriptor
 * (for LLM advertising) and the dispatch entry point (for execution).
 */
public interface ResolvedTool {
    /** The descriptor name. Must match the [ToolMetadata.name]. */
    public val name: String
}
