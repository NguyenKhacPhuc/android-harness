# Writing a custom tool

The substrate ships ~30 built-in tools — see `tools/src/main/kotlin/dev/weft/tools/`.
Apps register their own via `extraToolsFactory` on `WeftRuntime.create`.
The execution mechanics (permission gate, destructive gate, storage,
schema generation) are documented on `WeftTool`'s KDoc. **This doc covers
the part the KDoc can't: how to name a tool and write a description so
the LLM actually picks it.**

If you remember nothing else from this page: **verb-noun names,
3 words max, lead the description with the action.** Most "my tool
isn't being called" bugs come down to this.

---

## Why naming matters more than you'd think

Tool selection is a soft attention process. The model reads the
catalog, the user's request, and the recent conversation, then picks
one (or more) tool to fire. With 30+ tools in a catalog, each tool
gets only a fraction of the model's selection budget per turn — and
names + descriptions are how that budget gets spent.

We've watched Claude consistently:

- Pick `open_map` over `show_location_on_map` (renaming alone fixed a
  bug where the model said "let me open the map" without firing any
  tool — full post-mortem in undercurrent's git history).
- Skip tools whose descriptions start with caveats rather than the
  action ("This tool is used when…" loses to "Open the map at…").
- Confuse tools that overlap semantically (`maps_open_directions` vs.
  `open_map` needed an explicit "NOT for directions" note to keep the
  model from misrouting).

These aren't theoretical concerns. They're the difference between a
working feature and a feature that silently no-ops.

---

## Naming rules

1. **Use `<verb>_<noun>`.** `open_map`, `send_email`, `get_weather`,
   `set_theme_palette`. This is Anthropic's documented convention and
   the form the model's training reinforces. Tools that follow it get
   picked far more often than ones that don't.
2. **Lowercase snake_case.** No camelCase. No dashes.
3. **≤3 words, ideally 2.** Every extra word dilutes the signal. If
   you can't fit it in 2-3 words, the tool is probably doing too much
   — split it.
4. **No prepositions in the name.** `show_location_on_map` reads as
   awkward; `open_map` reads as obvious. Move any qualifiers ("on a
   map", "in the background") into the description.
5. **Prefix-group related tools.** The substrate uses `location_*`,
   `calendar_*`, `maps_*`, `bluetooth_*`. If your app adds a third
   theme-related tool, name it `set_theme_*` so it lands next to the
   existing two when the model scans the catalog alphabetically.
6. **Don't shadow built-ins.** Check `dev.weft.tools.*` first. Two
   tools with similar names confuse the model far more than one with
   a slightly less idiomatic name.

### Good vs. bad

| Don't | Do |
| --- | --- |
| `show_location_on_map` | `open_map` |
| `get_user_current_location_now` | `location_current` (or just reuse the built-in) |
| `theme_palette_setter` | `set_theme_palette` |
| `fetchAndDisplayCalendarEntries` | `calendar_read` |
| `do_research` | `web_research` (verb is implicit if noun is strong; otherwise `start_research`) |

---

## Description rules

The description is what tells the model **when** to call the tool —
both how to spot the user intent and how the tool fits with others.
A bad description loses the model's attention; a great one makes
selection nearly automatic.

1. **Lead with the action, in plain present tense.** "Open the map
   app pinned at…" beats "This tool opens the map app pinned at…".
   The first verb is what the model latches onto when scanning.
2. **Include 2–4 user-phrasing examples.** "Use when the user says
   'show me my location on a map', 'pin this address', 'where is
   that on a map'." Concrete trigger phrases anchor the tool to
   recognizable utterances.
3. **Cap it around 3 sentences / ~250 chars.** Long descriptions
   are a known cause of tool-skip behavior. If you need more, ask
   whether the tool's scope is too wide.
4. **Disambiguate from neighbors when there's overlap.** If two
   tools sound similar, a single sentence like "NOT for directions
   — use `maps_open_directions` for A→B navigation" eliminates the
   confusion entirely. Worth the bytes.
5. **State preconditions for multi-step flows.** "Call after
   `location_current` returns coords" is the kind of hint that
   prevents the model from narrating step 2 without firing step 1
   first.
6. **Don't repeat the name in the description.** "`open_map`: Opens
   the map…" wastes tokens. The model already has the name; describe
   the *behavior*.

### Anatomy of a strong description

```kotlin
description = "Open the map app pinned at the given coordinates. " +
    "Call this whenever the user wants to see a location on a map. " +
    "Pair with location_current (for 'my location') or with " +
    "geocoded coords (for any address). Do NOT use for directions — " +
    "use maps_open_directions for A→B navigation."
```

Breaks down as:

- **Sentence 1**: action, leading verb. ("Open the map app…")
- **Sentence 2**: when to use, with paraphrased user intent.
- **Sentence 3**: how this tool composes with others.
- **Sentence 4**: disambiguation from the closest neighbor.

---

## When the model still won't pick your tool

Symptoms: the agent narrates "I'll do X" in text but no tool fires.
This is a soft-skip, not a hard error — the tool isn't broken, the
model just isn't choosing it.

Checklist, in rough order of likelihood:

1. **Did you rename and restart?** The runtime's tool catalog is
   fixed at `WeftRuntime.create` time. App restart is the only way
   to pick up a renamed / re-described tool.
2. **Did you start a fresh conversation?** Old conversation history
   reinforces whatever pattern the model used before. A new thread
   lets the new catalog take effect cleanly.
3. **Is the catalog over ~30 tools?** Each tool gets less attention
   per turn as the catalog grows. Look for tools you could remove
   or consolidate.
4. **Does the description start with a caveat?** "This tool is used
   when…" / "Available for…" buries the action. Rewrite to lead
   with the verb.
5. **Is there an obvious neighbor the model might be confusing it
   with?** Add a one-sentence disambiguation.
6. **Add an explicit anti-hallucination note to the app preamble.**
   For the worst cases, telling the model "never narrate a tool call
   you don't make — if you say you'll do X, you MUST emit the
   tool_use block" measurably helps. Lives in the `appPromptPreamble`
   passed to `WeftRuntime.create`.

For interactive diagnostics, log on entry to `executeWeft`:

```kotlin
android.util.Log.d("MyAppTools", "$name called with $args")
```

…then `adb logcat -s MyAppTools` tells you definitively whether the
tool is firing or being skipped at selection time. This is the
fastest path from "I think the tool's broken" to "the tool isn't
even being called."

---

## Permissions and destructive flags

Both are covered in `WeftTool`'s KDoc and shouldn't surprise you,
but two LLM-relevant notes:

- **Permission failures get parsed by host apps.** The substrate
  throws `PermissionDeniedException` with the message
  `Permission denied for {tool}: {PERMS}.`. Apps detect that shape
  and surface a settings deep-link dialog instead of letting the
  cryptic message into chat (see Undercurrent's `AppStore` for the
  reference implementation). Don't rephrase that exception message
  in subclasses — keep the format intact.
- **`destructive = true` adds a confirmation step**, which the user
  experiences as a dialog mid-conversation. Use it judiciously;
  every confirmation interrupts the agent's flow.

---

## A pragmatic checklist before you ship

Before registering a new tool, walk through this:

- [ ] Name follows `<verb>_<noun>`, ≤3 words, lowercase_snake_case.
- [ ] Name doesn't shadow a built-in (`grep -r 'name = "' tools/src`).
- [ ] Description leads with the action, in 2–4 sentences.
- [ ] Description includes at least one paraphrased user trigger.
- [ ] Description disambiguates from any similar-sounding tool.
- [ ] If part of a multi-step flow, the description states what
  must happen first.
- [ ] Added a `Log.d` on entry to `executeWeft` (at least until you
  see it working in your app).
- [ ] Tested on a **new** conversation, not one with stale history.

Most "tool isn't being called" bugs trace back to one of these.
