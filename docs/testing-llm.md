# LLM Testing — WireDumper + FakeWeftLLM

Two pieces work together to make agent-loop tests fast, deterministic,
and hermetic:

- **WireDumper** ([`:harness:observability/wiredump`](../harness/observability/src/main/kotlin/dev/weft/harness/observability/wiredump/))
  — wraps any `PromptExecutor` and records every wire-level request +
  response. Used in dev to capture real sessions.
- **FakeWeftLLM** ([`:harness:testing`](../harness/testing/src/main/kotlin/dev/weft/harness/testing/FakeWeftLLM.kt))
  — scripted `PromptExecutor` that replays captures (or runs from a
  hand-written script) with zero API cost.

Captures from the dumper feed straight into the fake via
[`CaptureBridge`](../harness/testing/src/main/kotlin/dev/weft/harness/testing/CaptureBridge.kt).

## When to use which

| Phase | Tool | Why |
|---|---|---|
| Building a feature | WireDumper | See exactly what the model sees + emits while iterating |
| Reproducing a bug | WireDumper | Capture the misbehaving session, commit the dump as a test fixture |
| CI / unit tests | FakeWeftLLM | Deterministic, fast, no API keys |
| Integration tests | FakeWeftLLM via `fromCapture(...)` | Real-shaped traffic, hermetic execution |
| Edge cases (errors, parallel tools, retries) | FakeWeftLLM with hand-written script | Force shapes that are hard to produce naturally |

## Capturing a session with WireDumper

Wrap your normal executor with `WireDumper`. Compose two sinks so you
get both a queryable JSON-lines file AND readable per-turn files:

```kotlin
import dev.weft.harness.observability.wiredump.*
import java.io.File

val real = MultiLLMPromptExecutor(/* your normal clients */)
val dumper = WireDumper(
    delegate = real,
    sink = CompoundSink(
        JsonLinesSink(File("dumps/session.jsonl")),
        PerTurnSink(File("dumps/turns/")),
    ),
)

// Pass the dumper to WeftAgent's `executor` slot — drop-in replacement.
WeftAgent(executor = dumper, /* ... */)
```

Run your scenario. After the session:

```bash
# Quick scan
cat dumps/session.jsonl | jq -c '{turn: .turnNumber, model: .request.modelId, tools: [.response.toolCalls[]?.name], text_len: (.response.text | length)}'

# Inspect one turn
cat dumps/turns/turn-0003-req-0003.json | jq

# Find turns where a specific tool fired
cat dumps/session.jsonl | jq 'select(.response.toolCalls[]?.name == "create_persona")'
```

### What's captured

`WireCapture` schema (stable — additions get defaults, renames need migration):

```kotlin
WireCapture(
    turnNumber: Int,
    requestId: String,         // "req-0003" or "req-0003-stream"
    timestampMs: Long,
    durationMs: Long,
    streaming: Boolean,
    request: WireRequest(      // model, system, message history, tool names
        modelId, modelProvider, systemMessage, messages, toolNames,
        providerExtras,        // contextLength, maxTokens, temperature, …
    ),
    response: WireResponse?(   // null if error
        text, toolCalls, inputTokens, outputTokens, finishReason,
    ),
    error: String?,            // present when delegate threw
)
```

What's deliberately NOT captured:

- Full provider-specific JSON (cache control markers, structured-output
  schemas, etc.) — we capture enough to round-trip the response shape,
  not the request wire bytes byte-for-byte.
- Streaming frame timing — the dumper assembles streamed frames into a
  single `WireResponse` so replay is the same shape regardless of
  whether the original was streaming.

## Replaying a capture in a test

```kotlin
import dev.weft.harness.testing.FakeWeftLLM
import dev.weft.harness.testing.fromCapture
import java.io.File

@Test
fun `agent finishes persona-creation flow as captured`() = runTest {
    val fake = FakeWeftLLM.fromCapture(File("dumps/session.jsonl"))
    val agent = WeftAgent(
        executor = fake,
        // … normal wiring
    )

    val reply = agent.send("create a custom voice persona for code reviews")

    // Assertions on the agent's behaviour — same shape as if you ran
    // against the real API.
    assertThat(fake.callCount).isGreaterThan(2)
    assertThat(reply).contains("Created persona")
}
```

When the test runs more turns than the capture covered,
`FakeWeftLLM` throws a descriptive `FakeLlmException` — you'll know
immediately your capture is short.

## Hand-written scripts (no capture needed)

For shapes that are hard to elicit from a real model — parallel tools,
specific error responses, retry behaviour — hand-write the script:

```kotlin
val fake = FakeWeftLLM.build {
    // Rules fire first; first non-null match wins.
    whenUserSays("hello", "Hi! What would you like to do?")
    whenUserSaysCallTool("delete everything", "data_delete", """{"id":"all"}""")

    // Anything that doesn't match a rule pulls the next script step.
    text("This is the first scripted reply.")
    callTool("create_persona", """{"name":"Editor","kind":"voice"}""")
    callTools(
        ToolCallSpec("set_theme_palette", """{"palette":"vellum"}"""),
        ToolCallSpec("set_theme_mode",    """{"mode":"dark"}"""),
    )
    textThenCall(
        text = "I'll save that for you.",
        call = ToolCallSpec("memory_store", """{"content":"X"}"""),
    )
    error("Simulated 503 from the provider.")
    onUnscripted(UnscriptedPolicy.Default(FakeStep.Text("OK")))
}
```

Step types:

| Step | Becomes |
|---|---|
| `text("…")` | Plain text response, `finish_reason = "end_turn"` |
| `callTool(name, args)` | Single tool call, `finish_reason = "tool_use"` |
| `callTools(...)` | Multiple tool calls in one response (parallel) |
| `textThenCall(text, call)` | Text + tool call in same response (common Claude shape) |
| `error(message)` | Throws `FakeLlmException` from the executor |

## Asserting against what the agent sent

`FakeWeftLLM.calls` records every invocation:

```kotlin
fake.calls.shouldHaveSize(3)
fake.calls[0].source.shouldBe(CallSource.Rule)
fake.calls[1].source.shouldBe(CallSource.Script)
fake.calls[2].streaming.shouldBe(true)

// Inspect what the agent was told about the user
val lastUser = fake.calls.last().prompt.messages
    .lastOrNull { it is Message.User }?.textContent()
lastUser.shouldContain("approved")
```

## Patterns

### Capture once, replay forever

1. Wire WireDumper around your real executor in a one-off `main` or a
   debug build.
2. Run the scenario you care about end-to-end.
3. Move `dumps/session.jsonl` into `src/test/resources/fixtures/`.
4. Tests load `FakeWeftLLM.fromCapture(...)` and run.

If the model's behaviour changes (e.g. you bumped to a new Claude),
re-run step 2 — the rest stays the same.

### Mix rules + captured fallback

```kotlin
val captured = CaptureBridge.toScript(File("dumps/session.jsonl"))
val fake = FakeWeftLLM(
    rules = listOf(
        Rule { prompt, _, _ ->
            // Override one turn deterministically — e.g. always error on
            // the third call to simulate a flaky upstream.
            if (currentCallCount() == 3) FakeStep.Error("simulated 503") else null
        },
    ),
    script = captured,
)
```

### Reproducing a customer bug

A user reports "the agent keeps proposing wrong plans for habit tracker
mini-apps." Steps:

1. Ask them to capture (or enable the dumper in their build).
2. They send you `dumps/session.jsonl`.
3. You add it as a test fixture.
4. Write a test that loads it via `FakeWeftLLM.fromCapture` and asserts
   the desired behaviour.
5. Fix the bug. The same fixture verifies the fix; it stays as
   regression protection.

## What's not yet supported

- **Streaming-frame-level fidelity.** The dumper captures responses
  assembled from frames; replays emit a simplified frame sequence
  (`TextDelta` → `TextComplete` → `End`). If a test specifically
  cares about partial-text rendering during streaming, it sees a
  best-effort approximation.
- **Moderation API.** `FakeWeftLLM.moderate` throws
  `UnsupportedOperationException`. Subclass if you need it.
- **Multi-choice (`executeMultipleChoices`).** Falls back to running
  `execute` once and wrapping in a single-element list.

These are easy follow-ups if a test ever needs them.
