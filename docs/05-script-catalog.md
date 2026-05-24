# 05 — Script Catalog

The script catalog is the functional API surface. The LLM picks scripts and parameters; the substrate executes them.

## Design principles (recap from architecture discussion)

1. **Capability-shaped, not use-case-shaped.** `data.query` not `list_morning_habits`.
2. **Verbs over nouns.** Each script is an action with parameters.
3. **Polymorphic, with rich parameters.** Variation lives in parameters, not new scripts.
4. **One canonical script per OS capability.** No duplication.
5. **Escape hatches available but rare.** Run them sparingly, log them, gate them.

## Script inventory (v1: 24 scripts)

22 ship in `scripts-core`. The remaining 2 (`memory.store`, `memory.recall`) ship in `harness-memory` because they are wired directly to the memory storage backend — they're listed in this catalog because they're part of the LLM's tool surface.

Organized as namespaces.

### DATA

#### `data.query`
- **Purpose:** Read records from a known data source with filter/sort/projection.
- **Params:**
  - `source: string` — data source id (e.g., "habits", "journal_entries"; registered by the app)
  - `filter: object` — filter expression (typed; per-source schema)
  - `sort: array<{field, order}>` — optional
  - `projection: array<string>` — fields to include (default: all)
  - `limit: int` — default 50, max 500
- **Returns:** `{items: array, total: int, hasMore: bool}`
- **Permissions:** none (app-scoped data)
- **When to use:** any read of structured app data
- **When NOT to use:** for raw SQL needs, use `data.runQuery` (escape hatch, available only if app registers it)

#### `data.upsert`
- **Purpose:** Create or update a record.
- **Params:**
  - `source: string`
  - `record: object` — full record; if `id` present, update; else create
  - `idempotencyKey: string?` — for safe retries
- **Returns:** `{id, created: bool}`
- **Permissions:** none
- **Side-effecting:** true
- **When to use:** add or modify a record in app data

#### `data.delete`
- **Purpose:** Delete a record.
- **Params:**
  - `source: string`
  - `id: string`
- **Returns:** `{deleted: bool}`
- **Permissions:** none
- **Destructive:** true — requires user confirmation via `ui.ask`
- **Side-effecting:** true

### SCHEDULE

#### `schedule.create`
- **Purpose:** Schedule a one-time or recurring notification/reminder/calendar event.
- **Params:**
  - `kind: "notification" | "reminder" | "calendar_event"`
  - `content: {title, body?, action?}`
  - `when: ScheduleSpec` — natural-language ("in 5 minutes", "every Mon 9am") or structured
  - `idempotencyKey: string?`
- **Returns:** `{scheduleId, nextRunAt}`
- **Permissions:** `NOTIFICATIONS` (and `CALENDAR_WRITE` if kind=calendar_event)
- **Side-effecting:** true

#### `schedule.cancel`
- **Purpose:** Cancel a previously scheduled item.
- **Params:** `{scheduleId}`
- **Returns:** `{cancelled: bool}`
- **Side-effecting:** true

#### `schedule.list`
- **Purpose:** List active scheduled items, optionally filtered.
- **Params:** `{filter?: {kind, beforeDate, afterDate}}`
- **Returns:** `{items: array}`
- **Permissions:** `NOTIFICATIONS_READ` (Android only; iOS allows reading own app's schedules)

### NOTIFY

#### `notify.show`
- **Purpose:** Immediate (or near-immediate) notification or in-app banner.
- **Params:**
  - `kind: "push" | "toast" | "in_app_banner"`
  - `title: string`
  - `body: string?`
  - `action: {label, tool, params}?` — optional tap action
- **Returns:** `{shown: bool}`
- **Permissions:** `NOTIFICATIONS` for kind=push
- **When to use:** immediate user-facing notification, not scheduled
- **When NOT to use:** for scheduled reminders, use `schedule.create`

### CALENDAR

#### `calendar.read`
- **Purpose:** Read calendar events in a window with filters.
- **Params:**
  - `filter: {dateRange, calendarIds?, search?}`
- **Returns:** `{events: array}`
- **Permissions:** `CALENDAR_READ`

#### `calendar.create`
- **Purpose:** Create a calendar event in the user's calendar.
- **Params:** `{title, start, end, location?, notes?, attendees?, recurrence?, calendarId?}`
- **Returns:** `{eventId}`
- **Permissions:** `CALENDAR_WRITE`
- **Side-effecting:** true

### CONTACTS

#### `contacts.read`
- **Purpose:** Read user contacts with filters.
- **Params:**
  - `filter: {nameContains?, hasEmail?, hasPhone?, recentlyInteracted?, limit?}`
- **Returns:** `{contacts: array}`
- **Permissions:** `CONTACTS_READ`
- **When NOT to use:** for sending messages, use `external.share` or `external.openUrl(mailto/sms)` — substrate doesn't expose contact-write in v1

### FILES

#### `files.save`
- **Purpose:** Save content to a file in app-sandboxed storage.
- **Params:** `{content: string | base64Bytes, mimeType, name?, directory?}`
- **Returns:** `{uri, sizeBytes}`
- **Permissions:** none (app sandbox)
- **Side-effecting:** true

#### `files.read`
- **Purpose:** Read a file by uri.
- **Params:** `{uri, asBase64?: bool}`
- **Returns:** `{content, mimeType, sizeBytes}`
- **Permissions:** none if app's own; `READ_MEDIA_*` for shared storage on Android

#### `files.share`
- **Purpose:** Share a file via system share sheet.
- **Params:** `{uri, target: "system_sheet" | "specific_app", appHint?}`
- **Returns:** `{shared: bool, viaApp?: string}`
- **Permissions:** none
- **Side-effecting:** opens system UI

### UI

#### `ui.navigate`
- **Purpose:** Navigate to a screen in the app via the design system.
- **Params:** `{intent: SemanticIntent}` — see `06-design-system.md`
- **Returns:** `{navigated: bool}`
- **Permissions:** none
- **When to use:** show the user a screen as part of the agent's response

#### `ui.dialog`
- **Purpose:** Show a modal dialog (informational, not confirmation).
- **Params:** `{title, body?, actions?}`
- **Returns:** `{actionSelected?: string}` (suspends until user dismisses)
- **Permissions:** none

#### `ui.ask`
- **Purpose:** Ask the user a question, await their answer. Critical for destructive-action confirmation.
- **Params:**
  - `question: string`
  - `kind: "yes_no" | "choice" | "free_text"`
  - `options?: array<string>` (for choice)
  - `destructive?: bool` — affects styling
- **Returns:** `{answer, cancelled: bool}` (suspends)
- **Permissions:** none

#### `ui.requestPermission`
- **Purpose:** Explicitly request a permission from the user.
- **Params:** `{permission: Permission, rationale: string}`
- **Returns:** `{granted: bool, deniedForever?: bool}`
- **Permissions:** none (this *is* the permission flow)
- **When to use:** before a script that needs a permission, or as recovery from `PERMISSION_DENIED`

### EXTERNAL

#### `external.launchApp`
- **Purpose:** Launch another installed app with optional payload.
- **Params:** `{target: string, payload?: object}` — target is app id or scheme
- **Returns:** `{launched: bool}`
- **Permissions:** none for opening; specific permissions for some intents
- **Side-effecting:** opens external app

#### `external.openUrl`
- **Purpose:** Open a URL in browser or in-app webview.
- **Params:** `{url, inApp?: bool}`
- **Returns:** `{opened: bool}`
- **Permissions:** none

#### `external.share`
- **Purpose:** Share text/content via system share sheet.
- **Params:** `{content: {text?, url?, fileUri?}, target?: string}`
- **Returns:** `{shared: bool}`
- **Permissions:** none

### NETWORK

#### `network.fetch`
- **Purpose:** Make an HTTP request to an allowlisted domain.
- **Params:** `{url, method, headers?, body?, parseAs?: "json" | "text" | "bytes"}`
- **Returns:** `{status, headers, body}`
- **Permissions:** none, but domain must be in user-approved allowlist
- **When to use:** call public APIs the app needs; never for general web browsing
- **Note:** default allowlist is empty. User adds domains in settings; each addition is a deliberate trust decision.

### MEMORY (explicit only)

#### `memory.store`
- **Purpose:** Save a memory the LLM thinks is worth keeping across sessions.
- **Params:** `{content: string, tags: array<string>, scope: "session" | "permanent"}`
- **Returns:** `{memoryId}`
- **Permissions:** none
- **Side-effecting:** true
- **PII check:** if content matches PII patterns (SSN-like, credit-card-like, etc.), require user confirmation
- **User-visible:** every store call appears in "Agent Memories" screen

#### `memory.recall`
- **Purpose:** Search stored memories.
- **Params:** `{query: string, scope: "session" | "permanent" | "any", limit?: int}`
- **Returns:** `{memories: array<{id, content, tags, storedAt, scope}>}`
- **Permissions:** none

### SYSTEM

#### `system.userContext`
- **Purpose:** Get current device/user context. One canonical place to fetch "what's happening now."
- **Params:** `{fields: array<"location" | "time" | "timezone" | "locale" | "battery" | "network" | "deviceClass">}`
- **Returns:** values for requested fields (location only if permission granted; returns null otherwise)
- **Permissions:** `LOCATION` only if location requested

## Adding a new script

To add a script (substrate or app), the contributor:

1. Defines the interface in the appropriate module (`scripts-core` for substrate, app's domain module for app-specific).
2. Writes the script class implementing `Script` from `contracts`.
3. Implements OS-touching parts via `OsCapabilities` injected through `ScriptContext`.
4. Writes unit tests + integration tests on both platforms.
5. Writes docs entry following the template above.
6. Registers in `ScriptRegistry`.
7. Adds an entry to the substrate's regression test suite if substrate-level.

## Anti-patterns to refuse in review

A new script should be refused (or redirected to a parameter on an existing script) if:

- It does the same OS operation as an existing script with slightly different framing.
- Its parameters could be a filter field on an existing query script.
- Its description starts with "this is a convenience for..."
- Its name embeds a use case ("createMorningHabit") rather than a capability.
- It composes two existing scripts internally — the LLM should compose them.

## Per-script doc template

Each script ships with a markdown doc at `docs/scripts/<namespace>.<name>.md`:

```
# <namespace>.<name>

## Purpose
One sentence.

## Parameters
| Name | Type | Required | Description |

## Returns
Shape of the success result.

## Errors
| Code | When | Recovery |

## Permissions
List of required permissions.

## When to use
Examples of intents that should call this.

## When NOT to use
Other scripts that handle adjacent cases.

## Examples
2-3 sample LLM invocations with rationale.

## Implementation notes
Platform differences, gotchas.
```

## Estimated implementation cost

For v1's 24 scripts (22 in `scripts-core` + 2 memory scripts in `harness-memory`):

| Bucket | Count | Effort per script | Total |
|---|---|---|---|
| Pure data (no OS) | 3 | 0.5 day | 1.5 days |
| Schedule / notify | 4 | 2 days | 8 days |
| Calendar / contacts | 3 | 2 days | 6 days |
| Files / sharing | 4 | 1.5 days | 6 days |
| UI / dialogs | 4 | 1 day | 4 days |
| External / network | 3 | 1.5 days | 4.5 days |
| Memory¹ | 2 | 2 days | 4 days |
| System | 1 | 1 day | 1 day |
| **Total** | **24** | | **~35 days** |

¹ Memory scripts' effort is budgeted in `harness-memory` (see `07-harness.md`), not `scripts-core`. Counted here for completeness of the tool surface.

Plus ~50% overhead for testing, docs, and integration: **~50–55 days of work** for the script catalog, split across Android and iOS implementation tracks.
