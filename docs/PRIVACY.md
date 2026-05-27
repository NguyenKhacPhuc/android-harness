# Privacy & Data Safety — Weft substrate

Paste-ready content for the **Play Console → App content → Data
Safety** form. Each row maps one substrate capability (or
behavior) to the Data Safety category Google asks about, with the
collected-or-shared answer and reasoning.

This document covers **only what the substrate itself does**. The
host app may collect additional data through its own UI, analytics,
or LLM provider configuration — those go in additional rows.

**Pair with:** [PLAY-POLICY.md](PLAY-POLICY.md) (which permissions
need declarations) and [manifest-merging.md](manifest-merging.md)
(how to strip unused permissions).

---

## TL;DR — the seven Data Safety questions for a Weft host

1. **Does your app collect or share any of the required user data
   types?** → Yes (see table below).
2. **Is all of the user data collected by your app encrypted in
   transit?** → Yes — the substrate routes all network traffic over
   HTTPS; LLM providers (Anthropic, OpenRouter) use TLS.
3. **Do you provide a way for users to request that their data be
   deleted?** → Yes — substrate persists conversation/memory/trace
   data in app-private SQLDelight stores; the host app's "clear
   data" / app-uninstall removes them. If the host syncs to a
   server (it doesn't by default), the host must implement the
   delete flow.
4. **Has your app been independently validated against a global
   security standard?** → No (default).
5. **Is data collection optional for the user?** → Depends on the
   tool — see table. Most collection happens only when the user
   explicitly invokes the matching tool.
6. **Is data shared with third parties?** → Yes, with the LLM
   provider the host configures (Anthropic Claude, OpenRouter,
   etc.). See "Sharing" rows.
7. **Is data sold?** → No.

---

## Data type → Weft surface → Data Safety form entry

Use the row's contents as the answer Play Console asks for each
checkbox. "Collected" means the substrate reads/persists it on the
device; "Shared" means it leaves the device.

### Personal info

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Name | If user includes in conversation | Yes (LLM provider) | Conversations sent to LLM |
| Email | Same | Same | Same |
| User IDs | No | No | Substrate doesn't mint user IDs |
| Address | If user includes | Yes (LLM provider) | Conversation content |
| Phone number | If user includes; also when `phone_dial`/`sms_compose` invoked | LLM provider only | Phone number is in conversation; Intent handoff doesn't send the number to a server |
| Race/ethnicity | If user includes | Yes (LLM provider) | Conversation content |
| Political/religious beliefs | If user includes | Yes (LLM provider) | Conversation content |
| Sexual orientation | If user includes | Yes (LLM provider) | Conversation content |
| Other info | Same | Same | Same |

### Financial info

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| User payment info | No | No | Substrate doesn't process payments |
| Purchase history | No | No | Substrate has no commerce |
| Credit score | No | No | N/A |
| Other financial info | If user discusses with the agent | Yes (LLM provider) | Conversation content |

### Health & fitness

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Health info | If user discusses; not from sensors | Yes (LLM provider) | Conversation content only |
| Fitness info | Yes if host uses `sensor_steps_today` | LLM provider only when agent reads the value | Step count via ACTIVITY_RECOGNITION |

### Messages

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Emails | No | No | Substrate doesn't read email |
| SMS / MMS | No | No | We use Intent handoff for SMS compose; never read SMS |
| Other in-app messages | **Yes** (conversation history) | **Yes** (sent to LLM provider on each turn) | Core function of an LLM app |

### Photos & videos

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Photos | Yes when `media_*`, `camera_capture`, `media_pick_image` invoked | Yes if user asks agent to share/describe | URI is read; image bytes go to LLM provider only if user asks |
| Videos | Same | Same | Same |

### Audio files

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Voice or sound recordings | Yes when `audio_record` invoked | Depends — if user asks to share/transcribe | Files stored in app cache |
| Music files | Yes when user opts into media library | LLM provider only if user asks | URI handling |
| Other audio files | Same | Same | Same |

### Files & docs

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Files and docs | Yes when `files_*`, `pdf_*` invoked | Same | URI handling |

### Calendar

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Calendar events | Yes (`calendar_read`) | Yes (LLM provider) | Events read are sent to LLM for reasoning |

### Contacts

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Contacts | Yes (`contacts_read`) | Yes (LLM provider) | Contact data sent to LLM |

### App activity

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| App interactions | Yes — traces in `:harness:observability` | No by default; host opts in to telemetry export | Local trace store; not exfiltrated |
| In-app search history | Same | Same | Same |
| Installed apps | Yes (`app_installed`, `app_list_launchable`) | LLM provider only if used in a turn | Package names sent to LLM for routing decisions |
| Other user-generated content | Same as Messages | Same | Same |
| Other actions | Same | Same | Same |

### Web browsing

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Web browsing history | No | No | Substrate doesn't read browser history |

### App info & performance

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Crash logs | Default: no | No | Host's choice to add Crashlytics/Sentry |
| Diagnostics | Same | Same | Same |
| Other app performance data | Same | Same | Same |

### Device or other IDs

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Device or other IDs | Per Anthropic's [Privacy Policy](https://www.anthropic.com/legal/privacy) the LLM provider may log a session ID server-side. Substrate doesn't collect IMEI / advertising ID. | LLM provider | Provider-side only |

### Location

| Sub-category | Collected? | Shared? | Why |
|---|---|---|---|
| Approximate location | Yes when `location_current` invoked with COARSE | Yes (LLM provider for reasoning) | One-shot fix |
| Precise location | Yes when `location_current` invoked with FINE | Same | One-shot fix |

---

## "Why this data is collected" answer (Play Console copy)

Most rows ask for a free-text reason. Defaults that pass review:

- **Messages (conversation history):** "Required for app functionality. The conversation between the user and the AI agent is the core data of this app. Stored locally and sent to the AI provider to generate responses."
- **Photos / Videos / Audio / Files:** "Optional. Sent only when the user explicitly attaches a file or asks the AI to operate on it."
- **Calendar / Contacts:** "Optional. Read only when the user asks the AI a task that requires this data ('what's on my calendar tomorrow', 'text mom')."
- **Location:** "Optional. Read only when the user asks a location-aware question. No background tracking."
- **Fitness (steps):** "Optional. Used to surface activity nudges. Stored on device only."
- **Installed apps:** "App functionality. Used to route user requests to installed apps (e.g. open Spotify if installed, fall back otherwise)."

## "Is this data optional?" — answer per category

- **Required:** Messages (the app doesn't function without conversation).
- **Optional:** Everything else. The user controls invocation via natural language; the agent calls a tool only when the user asks.

## Privacy policy URL — what to include

The hosting app's privacy policy must, at minimum, mention:

1. The LLM provider the app uses (Anthropic / OpenRouter / etc.) and link to that provider's policy.
2. That conversation data leaves the device to reach the LLM provider.
3. That all OS-touching data (photos, calendar, contacts, location, …) is read only on user request, processed locally, and sent to the LLM provider only as part of the relevant turn.
4. How users can clear their data (uninstall, app's clear-data button).
5. Children's privacy posture — Weft is not designed for users under 13; host apps targeting kids must add additional disclosures.

Suggested template skeleton:

```
This app uses the Weft SDK to provide an AI assistant. The assistant
sends your messages and contextual data (only the data you ask it to
use) to <Provider> for processing. See <Provider's Privacy Policy URL>.

We do not collect data for advertising. We do not sell data. We do
not share data with third parties beyond the AI provider.

To delete all on-device data: clear the app's data in Android Settings,
or uninstall the app.

Contact: <email>.
Last updated: <date>.
```

---

## When the host adds its own collection

Append rows for whatever the host does on top of Weft:

- Crash reporting (Crashlytics → "App info & performance" / "Crash logs")
- Authentication (Firebase Auth → "Personal info / User IDs")
- Analytics (Mixpanel/Amplitude → varies)
- Cloud sync (Supabase/Firebase → "App activity / Other user-generated content")
- Push notifications (FCM → "Device or other IDs / Device ID")

Each of these adds Data Safety rows the substrate doesn't pre-populate.
