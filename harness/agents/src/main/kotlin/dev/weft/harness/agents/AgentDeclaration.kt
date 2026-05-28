package dev.weft.harness.agents

import dev.weft.harness.agents.strategy.DefaultStrategy
import dev.weft.harness.agents.strategy.WeftStrategy

/**
 * Host-side declaration of an agent the runtime should expose. One
 * declaration produces one [WeftAgent] when built via
 * `runtime.buildAgent(name, provider, ...)`.
 *
 * The host registers a list of these via `WeftRuntime.create(agents = ...)`.
 * Empty list = the runtime auto-synthesizes a single default declaration
 * named [DEFAULT_AGENT_NAME] with no allowlist and no role fragment,
 * which reproduces the pre-multi-agent behavior bit-for-bit.
 *
 * See `docs/architecture/multi-agent-registry.md` for the full design,
 * delegation semantics, and roadmap.
 */
data class AgentDeclaration(
    /**
     * Stable identifier. Used by `@mention` parsing (Phase 4.4) and
     * `delegate_to_agent` (Phase 4.2). Must be lowercase, kebab-or-
     * underscore-cased, and unique across the registered list.
     */
    val name: String,

    /** Human-readable label for the agent selector UI. */
    val displayName: String,

    /**
     * One-line description. Surfaced in `delegate_to_agent`'s tool
     * schema so an orchestrating LLM can pick the right delegate.
     * Lead with the action ("Use for deep, cited research"); keep
     * under ~250 chars per the substrate's tool-authoring guide.
     */
    val description: String,

    /**
     * Role preamble. Concatenated AFTER `WeftRuntime.appPromptPreamble`
     * so the host's identity wins on conflict. Empty string for the
     * default / orchestrator agent.
     */
    val systemFragment: String = "",

    /**
     * Tool allowlist by name (matched against
     * `WeftTool.descriptor.name`). Empty set = all tools the runtime
     * would normally expose to a default agent (substrate built-ins +
     * `extraToolsFactory` + MCP). Non-empty = strict allowlist; tools
     * not in the set are filtered out before the agent's catalog is
     * assembled.
     *
     * MCP tools are named `${serverName}:${remoteName}` — include them
     * here in qualified form if you want them in this agent's catalog.
     * `delegate_to_agent` (Phase 4.2) is always present regardless of
     * allowlist.
     */
    val allowedTools: Set<String> = emptySet(),

    /**
     * Per-agent loop policy. Defaults to status-quo `DefaultStrategy`.
     * Apps can pin a single agent to a cheap-tier
     * [dev.weft.harness.agents.strategy.FrugalStrategy] or a research
     * agent to a long-iteration
     * [dev.weft.harness.agents.strategy.BurstStrategy].
     */
    val strategy: WeftStrategy = DefaultStrategy(),

    /**
     * Whether the user can address this agent directly (via `@mention`
     * or the selector UI). `false` = the agent is only reachable via
     * `delegate_to_agent` from another agent — i.e., a "sub-agent" in
     * pre-#4 terms. Sub-agents register with `false`.
     */
    val userAddressable: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "AgentDeclaration name must be non-blank" }
        require(name == name.lowercase()) {
            "AgentDeclaration name must be lowercase (got '$name')"
        }
    }

    companion object {
        /**
         * Canonical name for the auto-synthesized default agent when
         * `WeftRuntime.create(agents = emptyList())`. Also the value
         * persisted in conversation rows that predate Phase 4.3's
         * per-agent attribution.
         */
        const val DEFAULT_AGENT_NAME: String = "default"

        /** The auto-default declaration installed when none are registered. */
        fun default(strategy: WeftStrategy = DefaultStrategy()): AgentDeclaration =
            AgentDeclaration(
                name = DEFAULT_AGENT_NAME,
                displayName = "Assistant",
                description = "The default agent. Handles all turns unless explicitly delegated.",
                systemFragment = "",
                allowedTools = emptySet(),
                strategy = strategy,
                userAddressable = true,
            )
    }
}
