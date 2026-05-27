# Test prompts for new substrate tools

Natural-language prompts the user can paste into Undercurrent (or any
host app that registers the full substrate tool catalog) to exercise
each tool category. Each section lists:

- **Tools exercised** — which `WeftTool`s should fire.
- **Try saying** — at least one phrasing that should pick the tool. Add
  more phrasings to verify the LLM is robust to wording shifts.
- **Expected behavior** — what the agent should do; useful for spotting
  silent skips, wrong-tool-picked, or hallucinated narration.
- **Watch for** — common failure modes (skip, wrong arg shape, tool
  loops).

Tools that gate on a permission assume the user has already granted it
in app settings; the agent should NOT need to ask. If a tool prompts
for permission anyway the gate logic regressed.

---

## Round 1 — first batch

### `speech_recognize` (STT)

- **Try saying:**
  - "Listen — I want to dictate a quick note."
  - "Turn on the mic and tell me what I say."
- **Expected:** agent calls `speech_recognize`, suspends ~5–10s while
  you speak, returns transcribed text inside the conversation.
- **Watch for:** narrating "I'm listening…" without firing the tool;
  using `audio_record` instead (it would return a file URI, not text).

### `media_list_recent` / `media_query`

- **Try saying:**
  - "Show me my last 5 photos."
  - "Find videos from last weekend in my gallery."
- **Expected:** `media_list_recent` for the first; `media_query` with a
  `sinceEpochMs` (compute via `date_compute`) for the second. Result
  has content:// URIs.
- **Watch for:** asking the user to attach a photo manually; trying to
  call `files_read` directly on a guessed path.

### `app_installed` / `app_list_launchable`

- **Try saying:**
  - "Is Spotify installed?"
  - "What music apps do I have?"
- **Expected:** first picks `app_installed` with `com.spotify.music`;
  second picks `app_list_launchable` and filters to music-y labels.
- **Watch for:** `external_launch_app` fired without checking install
  first.

### `sensor_steps_today` / `sensor_ambient_light`

- **Try saying:**
  - "How many steps have I walked today?"
  - "Is it dark in here?"
- **Expected:** the matching sensor tool. `available=false` is a valid
  outcome on devices without the sensor — agent should report it
  gracefully, not retry.

### `date_compute`

- **Try saying:**
  - "What date is 17 days from today?"
  - "How many days until 2027-01-01?"
  - "What day of the week is March 14, 2027?"
- **Expected:** `date_compute` with op=add/diff/weekday. Tool returns
  ISO + epoch; agent restates in friendly form.
- **Watch for:** the agent doing the math in its head — that's exactly
  the regression `date_compute` exists to prevent.

### `display_info`

- **Try saying:**
  - "Is dark mode on?"
  - "What's my screen refresh rate?"
- **Expected:** `display_info` fires; agent reports the field.

---

## Round 2 — telephony, wifi, volume, power

### `phone_dial` / `sms_compose`

- **Try saying:**
  - "Call +14155551234."
  - "Text my mechanic 'running 10 minutes late' to 555-0144."
- **Expected:** dialer opens pre-filled (NOT auto-called); SMS app
  opens with body. Agent surfaces success/failure based on whether the
  intent launched.
- **Watch for:** agent claiming the call was placed (it never is —
  user must tap).

### `telephony_info`

- **Try saying:**
  - "What carrier am I on?"
  - "Am I in airplane mode?"
- **Expected:** single `telephony_info` call. Tablets without SIM
  return null carrier — agent should say "no SIM detected" not
  fabricate.

### `wifi_info`

- **Try saying:**
  - "What wifi am I on?"
  - "Is my signal strong?"
- **Expected:** `wifi_info`. If SSID returns null on Android 9+ without
  LOCATION granted, agent should mention that limitation rather than
  saying "I can't tell."

### `volume_get` / `volume_set`

- **Try saying:**
  - "What's my media volume?"
  - "Mute the ringer."
  - "Set the alarm to 70%."
- **Expected:** `volume_get` with stream=MEDIA; `volume_set` with
  stream=RING normalized=0; same with stream=ALARM normalized=0.7.

### `power_keep_screen_on` / `power_set_brightness`

- **Try saying:**
  - "Keep the screen on while you read this to me."
  - "Dim the screen to 20%."
- **Expected:** the keep-screen-on call pairs with a release after the
  read-aloud ends. Brightness call uses 0.2.
- **Watch for:** agent forgetting to release the wake flag (regression
  symptom: screen never sleeps).

### `settings_open`

- **Try saying:**
  - "Open WiFi settings."
  - "Take me to notification settings for this app."
- **Expected:** `settings_open` with panel=WIFI or
  panel=APP_NOTIFICATIONS. Agent should NOT try to toggle settings
  itself.

### `shortcut_push` / `shortcut_list` / `shortcut_remove`

- **Try saying:**
  - "Pin a launcher shortcut that opens the dialer for my mom at
    555-0100. Label it 'Call Mom'."
  - "What shortcuts have I pinned?"
  - "Unpin 'Call Mom'."
- **Expected:** push uses target="tel:5550100", id="call_mom",
  shortLabel="Call Mom"; list returns it; remove uses id="call_mom".

---

## Round 3 — translation, images, pure utility

### `translate_text` / `detect_language`

- **Try saying:**
  - "Translate 'good morning' to Japanese."
  - "What language is this: 'Bonne nuit, mes amis'?"
- **Expected:** translate_text with target=ja (auto-detects source);
  detect_language returns "fr". First-time-for-pair adds latency from
  model download (~5-30s on wifi).
- **Watch for:** agent answering in its own training-data translation
  instead of calling the tool — verifiable by checking the trace.

### `image_resize` / `image_crop` / `image_rotate`

- **Try saying:**
  - "Take my last photo and shrink it to 800px."
  - "Rotate this image 90 degrees clockwise: [URI]"
- **Expected:** `media_list_recent` then `image_resize`; or
  `image_rotate` directly. Result is a new content:// URI.
- **Watch for:** agent assuming the original URI was modified in place
  (it isn't — substrate always writes a new file).

### `math_eval`

- **Try saying:**
  - "What's 18% tax on $450 plus a $12.50 service fee?"
  - "How much is sqrt(2) times pi?"
  - "If I run at 6 minutes per mile, how long for a 13.1 mile half
    marathon?"
- **Expected:** `math_eval` for every numeric step. Agent should
  surface the computed number, not reason about it.
- **Watch for:** the agent doing arithmetic inline ("That's about
  $94…"). That's the failure mode.

### `text_transform`

- **Try saying:**
  - "Slugify 'My Cool Blog Post 2026!'."
  - "Base64-encode 'secret message'."
  - "Reverse 'hello world'."
- **Expected:** `text_transform` with op=slug / base64_encode /
  reverse.

### `crypto_hash`

- **Try saying:**
  - "Give me the SHA-256 of 'password123'."
- **Expected:** `crypto_hash` with algorithm=SHA-256.

### `regex_match`

- **Try saying:**
  - "Pull all the phone numbers out of this text: 'Call 555-0100 or
    555-0200.'"
- **Expected:** `regex_match` op=find pattern=`\d{3}-\d{4}` (or
  similar).

### `url_parse`

- **Try saying:**
  - "What's the host and path for
    https://example.com/foo/bar?x=1&y=2#section?"
- **Expected:** `url_parse`. Agent surfaces host=example.com,
  path=/foo/bar, queryParams={x=[1], y=[2]}, fragment=section.

### `color_convert`

- **Try saying:**
  - "What's #3399ff in RGB?"
  - "Convert HSL 210,100,60 to hex."
- **Expected:** `color_convert` with the right from/to/value.

### `random_choice`

- **Try saying:**
  - "Roll a d20."
  - "Pick one: pizza, sushi, tacos, ramen."
- **Expected:** `random_choice` op=int min=1 max=20; op=pick choices=…

### `json_query`

- **Try saying:**
  - "From this JSON `{\"users\":[{\"name\":\"Ada\"},{\"name\":\"Bob\"}]}`
    pull out the second user's name."
- **Expected:** `json_query` path="users.1.name", value=`"Bob"`.

---

## Regression checks

Run after every substrate change that touches `:tools` /
`:os-bridge` / the system prompt assembly:

1. **Tool naming regressions.** Confirm each tool gets picked from at
   least 2 different phrasings. If a tool stops firing after a name or
   description change, that's a regression (see CLAUDE.md's tool-author
   rules — short edits can silently shift attention).
2. **No silent narration.** Watch the trace for assistant text that
   describes a tool action without a matching `tool_use` block. The
   anti-hallucination preamble note in CLAUDE.md is the fix when this
   creeps back.
3. **Permission gate flow.** With permissions pre-granted, a tool
   call should run without a `request` prompt. If a prompt appears
   anyway, the permission mapping or gate logic regressed.
4. **Approval mode.** Flip to `ApprovalMode.ReadOnly` and try a write
   tool — expect `ApprovalDeniedException` surfaced to the LLM. Flip
   to `Yolo` and the destructive prompt should be bypassed.

## How to run

Open Undercurrent (or another host registering the substrate catalog),
paste a prompt, watch the trace view to confirm the expected tool fires.
For pure tools the result is in the conversation text. For OS tools,
either confirm by side-effect (dialer opened, screen stays on,
shortcut appears under long-press) or by inspecting the result block
in the trace.

For each prompt, log results in a checklist file (one row per prompt)
so regressions are obvious next time.
