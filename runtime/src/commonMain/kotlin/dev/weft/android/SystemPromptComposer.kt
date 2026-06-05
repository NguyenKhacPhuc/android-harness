package dev.weft.android

import dev.weft.contracts.ComponentMetadata
import dev.weft.contracts.DataSource
import dev.weft.harness.prompt.assembleSystemPrompt
import dev.weft.tools.WeftTool

/**
 * Assembles the substrate system prompt for a given tool catalog.
 *
 * Captures the per-runtime-stable inputs (app preamble, UI component
 * metadata, data-source descriptions, extra notes) so the only thing
 * that varies per call is the tool list. [WeftRuntime] used to inline
 * three near-identical `assembleSystemPrompt(...)` calls — the pre-MCP
 * prompt, the MCP-resolved prompt, and the per-agent filtered prompt;
 * they now all route through [forTools], so the stable inputs are wired
 * once.
 */
internal class SystemPromptComposer(
    private val appPreamble: String,
    private val components: List<ComponentMetadata>,
    private val dataSources: List<DataSource>,
    private val extraNotes: String?,
) {
    /**
     * Build the system prompt advertising exactly [tools]. Pure — callers
     * own any caching (see [WeftRuntime.resolvedSystemPrompt]).
     */
    fun forTools(tools: List<WeftTool<*, *>>): String =
        assembleSystemPrompt(
            appPreamble = appPreamble,
            tools = tools,
            components = components,
            dataSources = dataSources,
            extraNotes = extraNotes,
        )
}
