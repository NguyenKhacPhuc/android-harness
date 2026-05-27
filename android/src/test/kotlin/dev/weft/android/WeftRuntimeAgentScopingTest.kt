package dev.weft.android

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.harness.prompt.assembleSystemPrompt
import dev.weft.harness.testing.weftToolContext
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 1 of [docs/architecture/tool-provider.md] — per-agent prompt
 * scoping.
 *
 * The substrate's [dev.weft.android.WeftRuntime.buildAgentForDeclaration]
 * now branches on whether the agent's
 * [dev.weft.harness.agents.AgentDeclaration.allowedTools] is empty:
 *
 *   - **Empty allowlist (default agent):** reuses
 *     `resolvedSystemPrompt()` — the cached full-catalog prompt.
 *     Behavior unchanged from before Stage 1.
 *   - **Non-empty allowlist:** rebuilds the prompt against the
 *     filtered tool list via `systemPromptFor(allTools)`. The
 *     resulting prompt describes ONLY the tools that agent can call.
 *
 * Constructing a full [WeftRuntime] in a unit test requires an
 * Android Context. We instead lock the contract Stage 1 leans on:
 * `assembleSystemPrompt(tools = filtered)` produces a catalog that
 * mentions only the filtered tools' names. The conditional in
 * `buildAgentForDeclaration` is a 4-line branch that's verified by
 * inspection alongside this test.
 */
class WeftRuntimeAgentScopingTest {

    private val ctx: WeftContext = weftToolContext()

    @Test
    fun `prompt mentions only tools passed in`() {
        val alpha = StubTool(ctx, name = "alpha_tool", description = "Do alpha.")
        val beta = StubTool(ctx, name = "beta_tool", description = "Do beta.")
        val gamma = StubTool(ctx, name = "gamma_tool", description = "Do gamma.")

        val full = assembleSystemPrompt(
            appPreamble = "Test preamble.",
            tools = listOf(alpha, beta, gamma),
        )
        // Full catalog mentions every tool.
        assertTrue(full.contains("- alpha_tool:"), "full prompt should list alpha")
        assertTrue(full.contains("- beta_tool:"), "full prompt should list beta")
        assertTrue(full.contains("- gamma_tool:"), "full prompt should list gamma")

        // Filtered to just alpha — beta + gamma must not appear.
        val filtered = assembleSystemPrompt(
            appPreamble = "Test preamble.",
            tools = listOf(alpha),
        )
        assertTrue(filtered.contains("- alpha_tool:"), "filtered prompt should still list alpha")
        assertFalse(filtered.contains("- beta_tool:"), "filtered prompt must NOT list beta")
        assertFalse(filtered.contains("- gamma_tool:"), "filtered prompt must NOT list gamma")
    }

    @Test
    fun `filtered prompt drops the bytes belonging to omitted tools`() {
        // Stage 1's whole point is token savings. The shape we lock here:
        // every omitted tool's name + description disappears from the
        // assembled prompt. We measure the delta directly instead of
        // picking an arbitrary percentage threshold, so the assertion
        // doesn't drift when STANDARD_TRAILING_NOTES changes shape.
        val tools = (1..10).map { i ->
            StubTool(
                ctx,
                name = "tool_$i",
                description = "Description for tool number $i with some realistic length.",
            )
        }
        val full = assembleSystemPrompt(appPreamble = "P", tools = tools)
        val filtered = assembleSystemPrompt(appPreamble = "P", tools = listOf(tools.first()))

        // Filtered must be strictly smaller.
        assertTrue(
            filtered.length < full.length,
            "filtered prompt (${filtered.length}) should be smaller than full (${full.length})",
        )

        // None of the 9 omitted tools' names appear in the filtered prompt.
        for (i in 2..10) {
            assertFalse(
                filtered.contains("- tool_$i:"),
                "filtered prompt must not list tool_$i",
            )
        }

        // The savings should at least match the sum of each omitted tool's
        // catalog line ("- name: description\n"). Verifies we're really
        // dropping the bytes, not just hiding them somewhere else.
        val omittedLinesBytes = (2..10).sumOf { i ->
            val name = "tool_$i"
            val desc = "Description for tool number $i with some realistic length."
            "- $name: $desc\n".length
        }
        val delta = full.length - filtered.length
        assertTrue(
            delta >= omittedLinesBytes,
            "expected at least $omittedLinesBytes bytes of catalog savings, got $delta",
        )
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Minimum [WeftTool] subclass with just enough descriptor for
     * `assembleSystemPrompt` to render. `executeWeft` is never called
     * in these tests.
     */
    private class StubTool(
        ctx: WeftContext,
        name: String,
        description: String,
    ) : WeftTool<StubTool.Args, String>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<String>(),
        descriptor = ToolDescriptor(
            name = name,
            description = description,
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Placeholder — ignored.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
    ) {
        @Serializable
        data class Args(val context: String = "")

        override suspend fun executeWeft(args: Args): String =
            error("StubTool should not be executed in prompt-scoping tests.")
    }
}
