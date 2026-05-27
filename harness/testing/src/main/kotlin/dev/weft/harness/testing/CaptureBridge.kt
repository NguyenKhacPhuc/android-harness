package dev.weft.harness.testing

import dev.weft.harness.observability.wiredump.JsonLinesReader
import dev.weft.harness.observability.wiredump.WireCapture
import java.io.File

/**
 * Loads a `WireDumper` JSON-lines dump and turns it into a deterministic
 * [FakeWeftLLM] script. The fake replays the captured responses turn by
 * turn — what was recorded comes back, in order.
 *
 * Typical workflow:
 *
 *   1. **Capture once** — run your real agent against a real API with
 *      [dev.weft.harness.observability.wiredump.WireDumper] wired in,
 *      pointed at `dumps/session.jsonl`. Stop when you have a good
 *      session.
 *   2. **Replay in tests** — build a [FakeWeftLLM] from the dump file:
 *      ```kotlin
 *      val fake = FakeWeftLLM.fromCapture(File("dumps/session.jsonl"))
 *      ```
 *      Pass that to `WeftAgent(executor = fake, ...)` and your test
 *      runs against the recorded responses with zero API cost and
 *      perfect determinism.
 *
 * What's preserved:
 *  - Text vs tool-call vs text-then-tool-call response shape.
 *  - Parallel tool calls (a single response with multiple `tool_use`
 *    blocks → one [FakeStep.CallTools]).
 *  - Errors — captured errors replay as [FakeStep.Error].
 *
 * What's NOT preserved:
 *  - Token counts and meta-info — replayed messages get a fresh
 *    `ResponseMetaInfo` with the original `modelId` but no token
 *    counts (tests rarely care; if you do, post-process the fake's
 *    output yourself).
 *  - Latency — replays are instant. Add a small delay in your test
 *    harness if you need it.
 *  - Provider extras — `cache_control`, `response_format`, etc. are
 *    informational only in the capture.
 */
public object CaptureBridge {

    /** Build a script of [FakeStep]s from a captured session. */
    public fun toScript(captures: List<WireCapture>): List<FakeStep> =
        captures.map { it.toFakeStep() }

    /** Read a JSON-lines dump file and convert directly to a script. */
    public fun toScript(jsonLinesFile: File): List<FakeStep> =
        toScript(JsonLinesReader.readAll(jsonLinesFile))

    private fun WireCapture.toFakeStep(): FakeStep {
        val errorMsg = error
        if (errorMsg != null) return FakeStep.Error(errorMsg)
        val r = response ?: return FakeStep.Error("Capture had neither response nor error")
        val text = r.text
        val toolCalls = r.toolCalls

        return when {
            toolCalls.isEmpty() -> FakeStep.Text(text)
            toolCalls.size == 1 && text.isBlank() -> FakeStep.CallTool(
                name = toolCalls[0].name,
                argsJson = toolCalls[0].argsJson,
                id = toolCalls[0].id.ifBlank { "tu_replay_${turnNumber}" },
            )
            toolCalls.size == 1 -> FakeStep.TextThenCall(
                text = text,
                call = ToolCallSpec(
                    name = toolCalls[0].name,
                    argsJson = toolCalls[0].argsJson,
                    id = toolCalls[0].id.ifBlank { "tu_replay_${turnNumber}" },
                ),
            )
            else -> FakeStep.CallTools(
                calls = toolCalls.mapIndexed { i, c ->
                    ToolCallSpec(
                        name = c.name,
                        argsJson = c.argsJson,
                        id = c.id.ifBlank { "tu_replay_${turnNumber}_$i" },
                    )
                },
            )
        }
    }
}

/**
 * Convenience factory: `FakeWeftLLM.fromCapture(file)` returns a fake
 * that replays the dump file exactly. Unscripted policy is
 * [UnscriptedPolicy.Throw] — if your test makes more turns than the
 * capture covered, you'll get a clear error instead of silent fallback.
 */
public fun FakeWeftLLM.Companion.fromCapture(file: File): FakeWeftLLM =
    FakeWeftLLM(script = CaptureBridge.toScript(file))

/** Same but from in-memory captures (useful for tests of the bridge itself). */
public fun FakeWeftLLM.Companion.fromCaptures(captures: List<WireCapture>): FakeWeftLLM =
    FakeWeftLLM(script = CaptureBridge.toScript(captures))
