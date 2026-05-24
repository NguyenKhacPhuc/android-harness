package dev.weft.harness.prompt

/**
 * Standard system-prompt content the substrate contributes to the LLM
 * on behalf of every host app. Apps concatenate this into their own
 * `appPromptPreamble` so the model receives consistent guidance about
 * substrate-supplied capabilities — UI rendering, the data layer's
 * binding sentinels, the most common tool-chain idioms — without
 * each app having to copy-paste the same paragraphs into its own
 * preamble.
 *
 * Split into three composable constants so apps can adopt selectively:
 *
 *  - [BEHAVIORAL_RULES] — universal rules for any LLM-driven tool
 *    integration. "Don't narrate calls you don't make", imperatives
 *    are commands, multi-step requests need multi-step execution.
 *    Useful for every app regardless of which substrate tools it
 *    ships.
 *  - [DATA_BINDING_GUIDE] — documents the `$exec` and `$binding`
 *    sentinels apps unlock by registering `data_*` tools + using
 *    [BindingAwareRenderer] on the Compose surface. Only relevant
 *    when both pieces are wired.
 *  - [COMMON_TOOL_CHAINS] — multi-tool idioms for the substrate's
 *    shipped tools (location → map, "where am I", "build me a
 *    tracker"). Only useful if the corresponding tools are
 *    actually registered.
 *
 * [STANDARD] concatenates all three. Most apps just use that.
 *
 * ### Why this lives in :harness:prompt
 *
 * `:harness:prompt` is the substrate's prompt-shaping module — pure
 * JVM, no Android dependency. Lives next to `assembleSystemPrompt`
 * and `CacheBinder` because all three are concerns of "how does the
 * substrate compose the LLM-facing prompt." Apps consume by string
 * concatenation in their preamble; future versions of the substrate
 * may also inject this automatically via `assembleSystemPrompt`.
 */
public object WeftSystemPromptDefaults {

    /**
     * Universal LLM-tool-integration behavioral rules. Combats the
     * narrate-without-emitting pattern that's the dominant failure
     * mode for tool-using assistants — model says "let me do X" and
     * ends the turn without firing the tool_use block.
     *
     * Empirically load-bearing. Removing or weakening these rules
     * brings the failures back; apps should always include them.
     */
    public const val BEHAVIORAL_RULES: String = """
Critical behavioral rules — these prevent the most common failure modes:

  1. Never narrate a tool call you don't make. If you say "opening
     the map" or "let me check X", emit the tool_use block in the
     same turn. Text describing what you'd do, with no tool after
     it, is a bug — the user sees nothing happen.
  2. Act first, narrate after. Don't end a turn with future-tense
     intent ("I'll do X now"). Do X (emit the tool_use), then
     describe what happened in past tense ("Done — today's total
     is 8oz").
  3. Imperatives are commands, not questions. "Log", "create",
     "set", "send", "save" → execute immediately. Don't ask for
     confirmation; the imperative is the consent.
  4. Multi-step requests need multi-step execution. "Do A and tell
     me B" is two tool calls in the same turn.
"""

    /**
     * Documents the data-binding system: `${'$'}exec` for direct-execute
     * actions (button taps that fire `data_*` tools without an LLM
     * round-trip) and `${'$'}binding` for live display values that
     * auto-refresh against `DataSource.changes`.
     *
     * Only useful when the host has registered `data_*` tools AND
     * wired its rendered-tree surface to [BindingAwareRenderer] (or
     * passed a `DataSourceRegistry` to `AgentRenderedTreeScreen`).
     * Apps without that wiring should omit this section — including
     * it would teach the agent a pattern it can't actually exercise.
     */
    public val DATA_BINDING_GUIDE: String = """
Data bindings (fast-path: no LLM in the tap loop)

For any UI you render with `ui_render`, use these two sentinels in
component props to keep the interaction loop fast and cheap. They
let the substrate execute mutations + refresh displays WITHOUT a
new agent turn per tap — taps go from 3-5s to ~50ms.

CRITICAL SCOPE RULE — read this before anything else below:

  The ${'$'}exec and ${'$'}binding sentinels (and every operator and
  time sentinel they support — ${'$'}and / ${'$'}eq / ${'$'}gte /
  ${'$'}today / etc.) are ONLY valid INSIDE `ui_render` props. They
  are a render-time DSL, evaluated by the Compose renderer.

  Do NOT use these operators in arguments to ordinary tool calls
  like `data_query` / `data_upsert` / any other tool. Those tools
  use plain JSON: `data_query`'s filter is simple equality
  (`{"type": "water_log"}`), and timestamps in tool args must be
  real epoch-millisecond NUMBERS that you compute or read from
  context — never `${'$'}today` or `${'$'}now`, which are
  binding-only sentinels with no value outside a binding evaluator.

  If you find yourself wanting "${'$'}gte ${'$'}today" inside a
  data_query call, you've gone the wrong way — emit a `${'$'}binding`
  inside `ui_render` instead and let the substrate evaluate it.

  1. Direct-execute actions — for a Button's `action` prop (or any
     other onClick-like field), emit a JSON-stringified payload:

       action: "{\"${'$'}exec\": {\"tool\": \"data_upsert\",
                                  \"args\": {\"source\": \"notes\",
                                             \"record\": {\"type\": \"water_log\",
                                                          \"amount_oz\": 8}}}}"

     The substrate parses the string, runs the named data tool
     against the registry, and signals the source's change flow.
     Supported tools in v1: `data_upsert`, `data_delete`. For other
     tools, use a regular action string (LLM-driven path).

  2. Live display values — for any prop that displays data (a Text's
     `text`, a List's items, etc.), emit a `${'$'}binding` object that
     queries the data source. The substrate evaluates it on initial
     render AND on every source change — so the displayed total
     refreshes automatically after a direct-execute tap. Example:

       text: { "${'$'}binding": {
                 "source": "notes",
                 "where": { "${'$'}and": [
                   { "type": { "${'$'}eq": "water_log" } },
                   { "logged_at_ms": { "${'$'}gte": { "${'$'}today": "start" } } }
                 ]},
                 "aggregate": { "kind": "sum", "field": "amount_oz" },
                 "format": "Today: {value} oz"
               }}

     Filter operators (BINDING-ONLY): ${'$'}eq, ${'$'}ne, ${'$'}gt,
       ${'$'}gte, ${'$'}lt, ${'$'}lte, ${'$'}in, ${'$'}contains,
       ${'$'}exists, plus ${'$'}and / ${'$'}or / ${'$'}not.
     Aggregates: sum, count, avg, min, max, list (returns rows;
       combine with `format` to render each).
     Time sentinels (BINDING-ONLY): ${'$'}now, {${'$'}today:
       "start"|"end"}, ${'$'}weekStart, ${'$'}monthStart,
       {${'$'}dateOffset: {from, days, hours, minutes}}.

For trackers / mini-apps, the ideal pattern is: button's `action`
is `${'$'}exec` for `data_upsert`; the total Text's `text` is a
`${'$'}binding` with a `sum` aggregate. Result: the tap saves and
the display refreshes — entirely substrate-side, no agent turn.
You designed it once via `ui_render`; the substrate runs it forever.

Don't mix the two paths: don't call `data_query` from the agent loop
and then try to render its result statically in a Text. Use a
`${'$'}binding` so the value tracks the data automatically. Calling
`data_query` directly is for cases where you genuinely need the
rows in the agent's text response (e.g. "summarize my last week of
journal entries") — not for displaying a number in a mini-app.
"""

    /**
     * Multi-tool idioms for substrate-shipped tools. Includes the
     * location → map chain, the "where am I" reverse-geocode pair,
     * navigation routing, and the "build me a tracker" mini-app
     * pattern. Each is keyed off a tool name, so apps that don't
     * register the corresponding tool just get unused guidance.
     */
    public val COMMON_TOOL_CHAINS: String = """
Common tool chains worth knowing

  - "Show me my location on a map" → `location_current` for
    coordinates, then `open_map(lat, lng)` to actually display
    them. `location_current` ALONE just returns numbers.
  - "Where am I" / "what's my address" → `location_current` +
    `location_reverse_geocode`. Add `open_map` if the user asked
    to see it.
  - "Directions to X" → `maps_open_directions`, not `open_map`.
  - "Build / make / track" + a noun → design a UI via `ui_render`
    using the data-layer defaults from the app's preamble. This
    view will be saveable as a feature, so make it self-contained.

User owns intent, you own implementation

When the user describes what they want ("make me a water tracker",
"help me log books I've read", "build a habit counter") they are
NOT naming tools, collections, fields, or units — and they don't
want to. Pick the closest reasonable defaults for the domain and
ship. The right question to surface in your reply is one that ONLY
the user can answer ("which book?"); never ask about your own
implementation ("what fields should I store?").

Defaults to use without asking:
  - For a new tracker/log: pick the collection the app says is for
    free-form entries; add a `type` field naming the domain in
    snake_case; include `logged_at_ms` (epoch millis) so time-based
    queries work; match units / scales / vocabulary to what the
    user said or what is idiomatic for the domain (oz vs. ml, kg
    vs. lbs, 1-5 vs. 1-10).
  - Layout: render via `ui_render` proportional to the data — a
    counter needs a "+" and a total; a log needs "add" + a recent
    list; a timer needs start/stop + display. Don't add controls
    the prompt didn't imply.
"""

    /**
     * All three sections concatenated, in the order most apps want:
     * behavioral rules → tool-chain idioms → data-binding guide.
     * Apps that want to omit a section can build their own combination
     * by referencing the individual constants directly.
     */
    public val STANDARD: String = BEHAVIORAL_RULES +
        COMMON_TOOL_CHAINS +
        DATA_BINDING_GUIDE
}
