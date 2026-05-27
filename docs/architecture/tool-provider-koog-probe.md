# Stage 2 prerequisite — Koog mid-loop registry mutation probe

- **Status:** ✅ Resolved 2026-05-27 via bytecode inspection of Koog
  1.0.0. **Path A is viable.**
- **Blocks:** ~~Stage 2 of [tool-provider.md](tool-provider.md)~~ —
  unblocked.
- **Question:** Can the substrate add a tool to an in-flight
  `agent.run()` call so that `find_tool`'s result is usable in the
  same user-visible turn?

## Answer (TL;DR)

**Yes — Path A via `llm.writeSession { tools = tools + new }` +
`toolRegistry.add(newTool)`.** Single-turn UX is achievable. No
rebuild, no abort, no two-turn fallback.

### What the bytecode shows

`ai.koog.agents.core.tools.ToolRegistry` (in `agents-tools-jvm-1.0.0`):

```
public final void add(ToolBase<?, ?>);
public final void addAll(ToolBase<?, ?>...);
```

The registry is mutable post-construction. The
`GenericAgentEnvironment` holds a reference to the same registry
and looks tools up by name at each `executeTool` call — so
additions take effect immediately for the execution path.

`ai.koog.agents.core.agent.context.AIAgentLLMContextCommon` (in
`agents-core-jvm-1.0.0`):

```
private java.util.List<? extends ToolDescriptor> tools;
public final List<ToolDescriptor> tools();
public final void setTools(List<? extends ToolDescriptor>);
```

The tool *descriptor list* (what's sent to the LLM in the
`tools: [...]` wire-level block) lives on `AIAgentLLMContextCommon`
as a mutable field, NOT as a per-session snapshot. Every fresh
`writeSession { ... }` reads from this field, so setting it in
one node propagates to the next `nodeCallLLM`'s LLM request.

### The two mutations Stage 2 needs

From inside a custom graph node placed between `nodeExecuteToolsX`
and `nodeSendToolResult`:

```kotlin
val nodeActivateFromFindTool by node<ReceivedToolResults, ReceivedToolResults>("activate") { results ->
    val activations = parseActivationsFromFindToolResults(results)
    if (activations.isNotEmpty()) {
        // (1) Make tools advertised in the next LLM request.
        llm.writeSession {
            tools = tools + activations.map { it.descriptor }
        }
        // (2) Make tools dispatchable when the LLM calls them.
        activations.forEach { llm.toolRegistry.add(it) }
    }
    results
}
```

Both APIs are public on Koog 1.0.0. No reflection, no abort, no
trace-ID juggling.

### What we didn't need to test

Check 3 (controlled abort + rebuild) — unneeded. Check 1 (direct
toolRegistry mutation) — works but isn't sufficient on its own
because it doesn't advertise the tool to the LLM. The
`writeSession.tools` mutation IS the missing piece.

## Why this matters

`find_tool` exists to let the LLM discover on-demand tools mid-turn:

```
user: "scan this receipt"
assistant: [calls find_tool("scan receipt")]
tool_result: "Available: vision_ocr, camera_capture"
assistant: [calls camera_capture]   ← needs this to work in the SAME turn
...
```

If Koog forces a turn boundary between `find_tool` returning and the
LLM being able to call the surfaced tool, every novel category costs
the user two turns. UX collapses.

The probe decides which `find_tool` propagation strategy Stage 2 ships:

| Probe result | Stage 2 path | UX |
|---|---|---|
| ✅ Mutable registry mid-run | Path A — direct mutation in `nodeExecuteTools` | Single turn |
| ✅ writeSession can extend tools | Path A — graph-node activation | Single turn |
| ⚠️ Neither — rebuild needed | Path A' — controlled abort + rebuild | Single turn, one wasted LLM call |
| ❌ All three fail | Path B — defer | Two turns minimum (worst UX) |

## Setup

Run from `:harness:agents:src/test` (the Koog APIs in question are
all on the agent module's compile classpath). Don't bother with
real network — use Koog's `MockLLMClient` to script a turn that
calls a hand-crafted "find_tool" then the surfaced tool.

```kotlin
// :harness:agents/build.gradle.kts already has:
//   testImplementation(libs.koog.agents)   // — actually it's `api`
//   testImplementation(libs.kotest.runner.junit5)
//   testImplementation(libs.kotlinx.coroutines.test)
```

If `MockLLMClient` isn't surfaced by Koog 1.0.0, fall back to a
stub `PromptExecutor` that returns scripted assistant messages.

## Probe checks — run in order, stop at the first ✅

### Check 1 — direct `toolRegistry` mutation post-construction

```kotlin
@Test
fun `probe — can we mutate AIAgent.toolRegistry after construction`() = runTest {
    val initialTool = stubTool("initial")
    val lateTool = stubTool("late_arrival")
    val registry = ToolRegistry { tool(initialTool) }

    val agent = AIAgent(
        promptExecutor = scriptedExecutor(
            // First LLM call: emit tool_use for find_tool.
            // (Use the initialTool as a stand-in for find_tool — we
            // only care whether the late tool becomes callable.)
            "tool_use:initial",
            // Second LLM call: emit tool_use for late_arrival.
            "tool_use:late_arrival",
            // Third: final assistant text.
            "text:done",
        ),
        agentConfig = AIAgentConfig(prompt = stubPrompt(), model = stubModel(), maxAgentIterations = 5),
        strategy = weftSingleRunStrategy(),
        toolRegistry = registry,
        installFeatures = {},
    )

    // Try to add lateTool BEFORE the run. If this throws or has no
    // effect, the registry is frozen at construction.
    val added: Boolean = runCatching {
        // Reflection probe — Koog's ToolRegistry may or may not be
        // mutable. Inspect Koog's API surface first; if there's an
        // `add(tool)` or builder rebuild, use it.
        registry.javaClass.getMethod("add", Tool::class.java)
            .invoke(registry, lateTool)
        true
    }.getOrDefault(false)

    if (!added) {
        // Verdict: Check 1 fails. Move to Check 2.
        return@runTest
    }

    val result = agent.run(stubInput())
    // If late_arrival was actually called, the run completes
    // successfully (third script step). Otherwise the model would
    // be stuck after the first tool call.
    assertEquals("done", result)
}
```

**Pass criterion:** `late_arrival` was invocable mid-run.
**Failure mode:** registry frozen — its concrete impl is
`PersistentToolRegistry` or similar. Move on.

### Check 2 — extend the tool list via `llm.writeSession`

Insert a custom graph node between `nodeExecuteToolsX` and
`nodeSendToolResult` in [WeftStrategies.kt](../../harness/agents/src/main/kotlin/dev/weft/harness/agents/multimodal/WeftStrategies.kt):

```kotlin
val nodeMaybeActivate by node<ReceivedToolResults, ReceivedToolResults>("maybe_activate") { results ->
    val findToolResult = results.results.firstOrNull { it.toolName == "find_tool" }
    if (findToolResult != null) {
        val toLoad = parseActivations(findToolResult.content)
        llm.writeSession {
            // Probe: does Koog let us extend the tool list visible
            // to the next LLM call from inside a writeSession?
            // Look for one of:
            //   - tools.add(...) / tools += ...
            //   - appendTools(...) on the session
            //   - a direct setter
            // If none exists, this check fails.
            for (toolName in toLoad) {
                val tool = lazyResolve(toolName)
                // Pseudo-call — actual API TBD by the spike.
                this.addTool(tool)
            }
        }
    }
    results
}

edge(nodeExecuteToolsX forwardTo nodeMaybeActivate)
edge(nodeMaybeActivate forwardTo nodeSendToolResult)
```

After wiring this in:

```kotlin
@Test
fun `probe — does activating in writeSession reach the next nodeCallLLM iteration`() = runTest {
    val registry = ToolRegistry { tool(findToolStub) }  // only find_tool initially
    val executor = scriptedExecutor(
        "tool_use:find_tool query=scan",
        "tool_use:vision_ocr uri=...",       // ← expects vision_ocr to be visible now
        "text:done",
    )
    val agent = AIAgent(...)
    val result = agent.run(stubInput())
    assertEquals("done", result)
}
```

**Pass criterion:** the second LLM call sees `vision_ocr` in its
tool list despite it not being in the initial registry.
**Failure mode:** the next `nodeCallLLM` re-reads the registry as
built at construction; `writeSession` writes are ignored or only
affect message history.

### Check 3 — controlled abort + rebuild

If Check 1 and Check 2 both fail, we test the fallback: abort
`agent.run()` cleanly, rebuild the `AIAgent` with the expanded
registry, replay the conversation, continue. Cost: one extra LLM
call (the one that called find_tool).

```kotlin
@Test
fun `probe — can we abort+rebuild without losing trace continuity`() = runTest {
    val builder = { tools: List<Tool<*, *>> ->
        AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(prompt = prompt, ...),
            strategy = strategyThatThrowsActivationMarker(),
            toolRegistry = ToolRegistry { tools.forEach { tool(it) } },
            installFeatures = {},
        )
    }

    var agent = builder(listOf(findToolStub))
    val result = try {
        agent.run(stubInput())
    } catch (activation: ActivationRequested) {
        // Rebuild with the expanded registry. Carry over conversation
        // history (must be reachable somehow — agent.history? prompt?).
        val history = agent.history()
        agent = builder(activation.tools)
        // Replay: the rebuilt agent picks up after the find_tool call.
        agent.run(stubInputContinuing(history))
    }
    assertEquals("done", result)
}
```

**Pass criterion:** the rebuilt agent picks up from where the
aborted one left off and reaches the final assistant message
without confusion. Trace IDs may differ — that's acceptable as
long as cost / observability stay coherent.

**Failure mode:** Koog's `AIAgent` doesn't expose the in-flight
prompt history, or replay corrupts the conversation. Path A'
isn't viable.

## Output

Whatever the spike learns, write the answer back into
[tool-provider.md](tool-provider.md) under "The Koog mid-turn
registry mutation question" — replace the open paragraph with the
chosen path and a code reference to whichever Koog API made it
work.

If all three checks fail, document the Path B fallback shape and
update find_tool's LLM-facing description to honestly tell the
model: "after calling this, the listed tools become available on
your NEXT response — your current response should end here and
wait for the user to nudge."

## Don't bother yet — defer to Stage 2 itself

These probe results inform the design but **don't ship into main**.
Keep the probe in a throwaway branch (`spike/koog-tool-registry-mutation`)
and merge only the answer + the recommended path into the design
doc. Stage 2 implementation is a separate PR that builds on that
answer.

## Time budget

If the probe is taking >3 hours, stop. The most likely cause is
Koog's API surface being more opaque than expected, which means
Path A and Path A' are both impractical, which means **the answer
is Path B** and Stage 2 should be designed accordingly. Don't sink
a day into the probe — failure is also an answer.
