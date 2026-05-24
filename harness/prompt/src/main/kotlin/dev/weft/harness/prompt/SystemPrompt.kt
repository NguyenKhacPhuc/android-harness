package dev.weft.harness.prompt

import dev.weft.contracts.ComponentCategory
import dev.weft.contracts.ComponentMetadata
import dev.weft.contracts.DataSource
import dev.weft.tools.WeftTool

/**
 * Assembles the substrate's system prompt at runtime from:
 *   - the app's role preamble ("You are an agent embedded in Undercurrent…"),
 *   - a generated tool catalog built from each registered tool's
 *     [ToolDescriptor.description],
 *   - the UI component catalog (per ADR-007: dynamic component-tree UI),
 *   - the substrate's standard trailing notes about permissions and tool
 *     usage etiquette.
 *
 * Apps add tools via `extraToolsFactory` and components via
 * `extraComponentsFactory`; both are advertised automatically.
 */
public fun assembleSystemPrompt(
    appPreamble: String,
    tools: List<WeftTool<*, *>>,
    components: List<ComponentMetadata> = emptyList(),
    dataSources: List<DataSource> = emptyList(),
    extraNotes: String? = null,
): String = buildString {
    if (appPreamble.isNotBlank()) {
        appendLine(appPreamble.trimEnd())
        appendLine()
    }
    appendLine("Available tools:")
    for (tool in tools) {
        val name = tool.descriptor.name
        val description = tool.descriptor.description.trim()
        appendLine("- $name: $description")
    }
    if (dataSources.isNotEmpty()) {
        appendLine()
        appendLine("Available data collections (use with data_query / data_upsert / data_delete):")
        for (source in dataSources) {
            val desc = source.description.trim()
            if (desc.isEmpty()) {
                appendLine("- ${source.name}")
            } else {
                appendLine("- ${source.name}: $desc")
            }
        }
        appendLine(
            "New collection names are NOT auto-created — passing an unknown name to " +
                "data_upsert fails. Distinguish categories within an existing collection by adding " +
                "a `type` field to the JSON record (e.g. {\"type\":\"water_log\", ...} inside `notes`).",
        )
    }
    if (components.isNotEmpty()) {
        appendLine()
        appendLine(UI_PROTOCOL_INTRO.trim())
        appendLine()
        appendLine("Available UI components (grouped by category):")
        val byCategory = components.groupBy { it.category }
        // Iterate in enum declaration order so the prompt always reads top-down
        // in the same order: DISPLAY → LAYOUT → ACTION → INPUT → MACRO → EMBED.
        for (category in ComponentCategory.entries) {
            val inCategory = byCategory[category].orEmpty()
            if (inCategory.isEmpty()) continue
            appendLine()
            appendLine("## ${category.label} — ${category.hint}")
            for (c in inCategory) {
                appendLine("- ${c.name}: ${c.description.trim()}")
                c.layoutNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    appendLine("    Layout: ${notes.trim()}")
                }
                c.example?.takeIf { it.isNotBlank() }?.let { example ->
                    appendLine("    Example: ${example.trim()}")
                }
            }
        }
        appendLine()
        appendLine(UI_EXAMPLE_TREES.trim())
    }
    appendLine()
    appendLine(STANDARD_TRAILING_NOTES.trim())
    if (!extraNotes.isNullOrBlank()) {
        appendLine()
        appendLine(extraNotes.trimEnd())
    }
}

private val UI_PROTOCOL_INTRO = """
You can render real UI inline by calling ui_render with a component tree (JSON):
  { "type": "<component>", "props": { ... }, "children": [ ... ] }
Compose layouts with Column or Row; put interactive widgets inside them.
Buttons take an `action` string — when the user taps, you receive that action
as a synthetic observation on the next turn, so you can react.
Use UI when it materially helps (timers, forms, picking from options, showing
structured data). Otherwise just respond in text.

**In-app first.** When the user wants to "see more" / "open X" / "drill into Y":
  1. PREFER: render a new component tree on the next turn — keeps the user
     in your branded surface, lets you control styling.
  2. WHEN web content is the right answer (full Wikipedia article, news page,
     generic web search): use the WebView component (in-app, user stays inside).
  3. LAST RESORT: external_open_url — only when the destination must be the
     system browser, or for app-specific URIs (tel:, mailto:, sms:).
Reach for external_open_url last; reach for ui_render first.
""".trimIndent()

private val UI_EXAMPLE_TREES = """
Patterns that look good — model your trees on these:

Example A — Timer (use the Timer macro, NOT Countdown + Buttons manually):
{
  "type": "Card", "props": {"variant": "elevated", "padding": "lg"},
  "children": [
    {"type": "Timer", "props": {"durationMs": 1500000, "title": "Focus Timer", "onComplete": "focus_done"}}
  ]
}
Timer is a macro component — it ships with Reset/Pause/Extend buttons that work
LOCALLY, with no LLM round-trip. You only hear the onComplete event when the
timer hits zero. Use Timer for any "set me a timer" request. Use bare Countdown
only when the user explicitly wants no controls.

Example B — Pick-one (use the Picker macro):
{
  "type": "Card", "props": {"variant": "elevated", "padding": "md"},
  "children": [
    {"type": "Picker", "props": {
      "title": "What's for dinner?",
      "options": ["🍕 Pizza", "🥗 Salad", "🍝 Pasta"],
      "style": "buttons",
      "onPicked": "dinner_choice"
    }}
  ]
}

Example C — Form (use the Form macro):
{
  "type": "Card", "props": {"variant": "elevated", "padding": "md"},
  "children": [
    {"type": "Form", "props": {
      "title": "Quick journal entry",
      "fields": [
        {"id": "mood", "label": "How are you feeling?", "placeholder": "one word", "required": true},
        {"id": "note", "label": "Anything to add?", "type": "multiline"}
      ],
      "submitLabel": "Save",
      "onSubmit": "journal_entry_saved"
    }}
  ]
}

Example D — Stopwatch (counts up):
{
  "type": "Card", "props": {"variant": "elevated", "padding": "lg"},
  "children": [
    {"type": "Stopwatch", "props": {"title": "Workout", "onDone": "workout_logged"}}
  ]
}

Example E — Countdown to a chosen date (use DateCountdown macro):
{
  "type": "DateCountdown",
  "props": {"title": "Days until launch", "onArrived": "launch_day"}
}
Render DateCountdown / DatePicker at the top level (not inside a padded Card)
— the embedded calendar needs ~360dp horizontal width to lay out correctly.
If you must wrap, use Card with padding="none".

Rules of thumb:
- Wrap top-level content in a Card. It gives breathing room and clear hierarchy.
- Use "title" or "display" variant for hero text; "body" for prose.
- Row with align="space_evenly" + buttons with fillWidth=true gives equal-width action bars.
- Keep trees shallow — 3-4 levels deep is plenty; deeper than 6 is rejected.
- Match buttons' visual weight to importance: primary for the main action, secondary for the rest.
- **Prefer macro components (Timer, …) over composing primitives manually.** Macros bundle their own
  controls + behavior locally — no round-trip to you on every tap. You hear only semantic events
  (e.g., the timer completed). If a user task fits a macro, use the macro.

**Html vs Material — strict decision rule:**

DEFAULT to Html. Use Material components ONLY when the user's request needs
one of these capabilities Html cannot provide:

1. **Persistence** — the user's data must survive (notes, journal entries,
   profile, scores, settings). Use Material data tools (data_upsert) +
   Material Form to capture, OR memory_store for facts about the user.
2. **Device features** — notifications, scheduling, calendar, contacts,
   sharing, file save, camera. Use the corresponding Material tool/component.
3. **Agent interaction** — the agent needs to know about an event the user
   triggered (timer completed, choice picked, form submitted, item tapped).
   Material components fire events back; Html cannot.
4. **Native navigation** — the UI is part of the app's primary structure
   (the chat surface, settings screens). Use Material screens, not Html.

EVERYTHING ELSE is Html. In particular:

- Static content (articles, summaries, recipes, definitions, explanations) →
  Html (runScripts=false).
- Self-contained interactive widgets that don't need the agent or device →
  Html (runScripts=true). Calculators, countdown timers for play, mini-games,
  animations, drawing canvases, drag-drop puzzles — all Html.

Decision tree (apply in order):

  Q1. Does the request need persistence, device features, agent events, or
      app navigation?
        YES → Material. Use the right tool / component / macro.
        NO  → Html.

  Q2. Does the Html need behavior (compute, animate, tick)?
        YES → Html with runScripts=true.
        NO  → Html with runScripts=false.

Worked examples:

| Request                                              | Choice                          | Why                        |
|------------------------------------------------------|---------------------------------|----------------------------|
| "Create me a simple game"                            | Html, runScripts=true           | Self-contained             |
| "Create me a game that tracks high score"            | Html + data_upsert tool         | Persistence needed         |
| "Show me a 60-second countdown to relax"             | Html, runScripts=true           | No agent reaction needed   |
| "Set a 25-min focus timer and log the session"       | Material Timer macro            | Agent needs onComplete     |
| "Pick one of these lunch options"                    | Material Picker macro           | Agent needs the choice     |
| "Show me three lunch options visually"               | Html                            | Display only               |
| "Summarize quantum entanglement"                     | Html, runScripts=false          | Prose content              |
| "Form to collect my contact info"                    | Material Form macro             | Captured data goes to you  |
| "Calculator"                                         | Html, runScripts=true           | Self-contained             |
| "Recipe for pasta carbonara"                         | Html, runScripts=false          | Pure prose                 |
| "Save this recipe to my notes"                       | Html (recipe) + Material Button | Action needs agent         |
| "Daily Pomodoro that adds to my focus stats"         | Material Timer + data tool      | Persistence + event        |
| "Remind me at 9am tomorrow to drink water"           | Material — schedule_create tool | Device feature             |

When in doubt: read the request again. If it doesn't mention or imply saving,
notifying, scheduling, sharing, or "tell me when X happens" — it's probably
Html.
""".trimIndent()

private val STANDARD_TRAILING_NOTES = """
Tools that need permissions will fail with PERMISSION_DENIED if the user hasn't granted them yet —
call ui_request_permission first, or ask the user to grant in system settings.

**Memory protocol — proactive, not passive.**

You have `memory_recall` and `memory_store`. Use them as part of normal conversation,
not as a separate task.

At the START of any non-trivial turn (anything beyond a one-shot question like "what
time is it?"), call `memory_recall` with a query relevant to the user's message. This
costs almost nothing and dramatically improves continuity — if they mentioned a
preference last week, you'll know.

During the turn, whenever the user reveals a **durable fact**, call `memory_store`
on the same turn — don't ask permission, just do it. Durable facts include:

  - Preferences: "I prefer dark mode", "I like espresso, not drip"
  - Identity / context: "my name is Maya", "I'm a vegetarian", "I have two kids"
  - Recurring patterns: "I usually wake up at 6", "every Friday I work from home"
  - Constraints: "I'm allergic to peanuts", "I can't drink coffee after 2pm"
  - Goals: "I'm training for a marathon", "I'm learning Spanish"

Pass `scope='permanent'` for facts that should outlive the current conversation
(almost everything above), `scope='session'` for chat-local context only.

Skip ephemeral stuff: today's todo, the question they just asked, intermediate
tool results. The user can delete any stored memory; bias toward storing.

If a stored memory turns out to be wrong (user corrects you), call `memory_compact`
to consolidate or replace it rather than just adding another contradictory entry.

Use tools when they help. Otherwise just respond in text.
""".trimIndent()
