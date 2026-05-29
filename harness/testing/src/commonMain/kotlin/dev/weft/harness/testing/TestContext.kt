package dev.weft.harness.testing

import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.ScriptStorage
import dev.weft.contracts.UiBridge
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool

/**
 * One-shot factory for a [WeftContext] suitable for unit tests.
 *
 * Default wiring:
 *   - `os` = [FakeOsCapabilities] with empty defaults (override per-test)
 *   - `ui` = [FakeUiBridge] (record + answer pending requests)
 *   - `storageFactory` = a per-tool [InMemoryScriptStorage]
 *
 * Override any of the three by passing your own — useful when you want to
 * reuse the same FakeOsCapabilities across multiple `weftToolContext()`
 * calls in a single test, or when you need a real SQLDelight ScriptStorage
 * for TTL-specific assertions.
 *
 * Example:
 * ```kotlin
 * val ui = FakeUiBridge()
 * val os = FakeOsCapabilities()
 * val ctx = weftToolContext(os = os, ui = ui)
 *
 * val tool = ClipboardWriteTool(ctx)
 * tool.executeSubstrate(ClipboardWriteTool.Args(text = "hello"))
 *
 * assertThat(os.clipboard.contents).isEqualTo("hello")
 * ```
 */
public fun weftToolContext(
    os: OsCapabilities = FakeOsCapabilities(),
    ui: UiBridge = FakeUiBridge(),
    storageFactory: (toolName: String) -> ScriptStorage = { InMemoryScriptStorage() },
): WeftContext = WeftContext(
    os = os,
    ui = ui,
    storageFactory = storageFactory,
)

/**
 * Invoke a tool's `executeSubstrate` directly, bypassing the agent loop.
 *
 * Use this when you want to verify the tool's behavior without bringing up
 * a full agent. The supplied [args] go straight into the tool — no JSON
 * round-trip, no LLM, no Koog. The permission gate + destructive gate
 * still run (so the FakeUiBridge / FakePermissions should be configured
 * appropriately).
 *
 * ```kotlin
 * val ui = FakeUiBridge().apply { autoConfirm = true }
 * val tool = MyDestructiveTool(weftToolContext(ui = ui))
 * val result = runTool(tool, MyDestructiveTool.Args(target = "..."))
 * assertThat(ui.confirmRequests).hasSize(1)
 * ```
 */
public suspend fun <TArgs, TResult> runTool(
    tool: WeftTool<TArgs, TResult>,
    args: TArgs,
): TResult = tool.execute(args)
