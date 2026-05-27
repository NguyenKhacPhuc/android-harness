# Google Play policy checklist — Weft substrate permissions

Every host app that ships Weft inherits the permissions declared in
`:os-bridge/src/main/AndroidManifest.xml` via manifest merging. This
doc is the checklist a host app must run through before submitting to
Play to know which permissions need a Permissions Declaration form,
which trigger Data Safety entries, and which can be removed at the
host level when the host doesn't need them.

**Pair this doc with [PRIVACY.md](PRIVACY.md) (Data Safety form
content) and [manifest-merging.md](manifest-merging.md) (the
`tools:node="remove"` patterns).**

---

## At-a-glance status per permission

Legend:
- 🟢 **Normal** — auto-granted at install. No Play scrutiny.
- 🟡 **Dangerous (runtime)** — declared in manifest, prompted at runtime. Triggers a Data Safety entry but no Permissions Declaration form unless flagged below.
- 🔴 **Restricted by Play policy** — Permissions Declaration form required. App categorization may bar use.
- ⚫ **Conditionally restricted** — depends on what the host app uses it for.

| Permission | Status | Required by | Removable? |
|---|---|---|---|
| `INTERNET` | 🟢 | LLM client, `network_fetch`, `translate_text` | No (LLM-orchestrated app needs network) |
| `ACCESS_NETWORK_STATE` | 🟢 | `network_status`, `SystemInfo.network()` | Yes if `network_status` not used |
| `ACCESS_WIFI_STATE` | 🟢 | `wifi_info` | Yes if `wifi_info` not used |
| `MODIFY_AUDIO_SETTINGS` | 🟢 | `volume_set` (some streams) | Yes if `volume_*` not used |
| `VIBRATE` | 🟢 | `haptics_feedback` | Yes if no haptics |
| `USE_BIOMETRIC` | 🟢 | `biometric_authenticate` | Yes if no biometric flows |
| `BLUETOOTH` (≤30) | 🟢 | `bluetooth_*` pre-API-31 fallback | Yes if no BT tools |
| `POST_NOTIFICATIONS` | 🟡 | `notify_show`, scheduled notifications | Yes if no notifications |
| `READ_CONTACTS` | 🟡 | `contacts_read` | Yes if `contacts_read` not used |
| `READ_CALENDAR`, `WRITE_CALENDAR` | 🟡 | `calendar_*` | Yes if `calendar_*` not used |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | 🟡 | `location_*`, `wifi_info` SSID | Yes if neither used |
| `RECORD_AUDIO` | 🟡 | `audio_record`, `speech_recognize` | Yes if no audio capture |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` | 🟡 | `media_list_recent`, `media_query` | **Yes — prefer `media_pick_*` instead** |
| `READ_EXTERNAL_STORAGE` (≤32) | 🟡 | Pre-API-33 media fallback | Same as above |
| `ACTIVITY_RECOGNITION` | ⚫ | `sensor_steps_today` | Yes if no step tracking |
| `BLUETOOTH_CONNECT` (≥31) | ⚫ | `bluetooth_list_paired`, `bluetooth_device_battery` | Yes if no BT tools |
| `SCHEDULE_EXACT_ALARM` (31-32) | 🔴 | `schedule_create` precise alarms | **Strongly recommend removing unless host is an alarm/reminder app** |
| `USE_EXACT_ALARM` (≥33) | 🔴 | Same | Same |

---

## Permissions Declaration form — what to write

Submit these in **Play Console → Policy → App content → Sensitive permissions** before publishing.

### `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM`

> **Reviewer asks:** What user-facing feature requires exact alarms?

> **Suggested copy (alarm/reminder app):** "Our app schedules user-set reminders and timed notifications at exact moments — e.g. 'remind me at 3:00 PM tomorrow'. Inexact alarms can drift by several minutes which is unacceptable for a reminder UX."

**Eligible app categories (per [Play USE_EXACT_ALARM policy](https://support.google.com/googleplay/android-developer/answer/13161072)):** alarm clocks, timers, calendar apps, reminder apps.

**If your app is NOT in those categories:** remove these permissions via manifest-merging (see [manifest-merging.md](manifest-merging.md)). The Weft `schedule_create` tool falls back to inexact alarms with no code changes.

### `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`

> **Reviewer asks:** Why can't you use the Photo Picker?

> **Suggested copy (full gallery query needed):** "Our app surfaces user-requested searches across the entire photo library (e.g. 'find my receipts from last month') which requires programmatic enumeration with metadata. The Photo Picker is a single-pick UI that doesn't support discovery flows."

**If your host only uses `media_pick_*` tools:** remove `READ_MEDIA_*` via manifest-merging. The picker tools work without these permissions.

### `ACTIVITY_RECOGNITION`

> **Reviewer asks:** What user benefit comes from step data?

> **Suggested copy (wellness/fitness host):** "Our app uses step count to suggest activity nudges and report daily progress. Step data is processed on-device and never sent to a server."

**If your host doesn't surface step data to the user:** remove this permission. The `sensor_steps_today` tool will return `available=false`, which the agent handles gracefully.

### `BLUETOOTH_CONNECT`

> **Reviewer asks:** What does the user gain from Bluetooth access?

> **Suggested copy:** "Our app lists the user's paired Bluetooth devices and reads device-reported battery levels, e.g. 'is my headphone battery low?'. We do not scan, pair, or transfer data."

**Generally low-risk** because the Weft surface area is narrow (list + battery only, no scan/connect/pair).

### Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)

> **Reviewer asks:** What is location used for and is it foreground-only?

> **Suggested copy:** "Foreground only. We read a one-shot location fix when the user asks location-aware questions (e.g. 'weather here', 'where am I'). No background location, no continuous tracking."

`ACCESS_BACKGROUND_LOCATION` is **NOT** declared by Weft. Don't add it.

---

## What's already clean — no declaration needed

The Weft substrate intentionally avoids the most-scrutinized permissions:

| Avoided | Why we don't need it |
|---|---|
| `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS` | `sms_compose` uses Intent handoff — user reviews + sends in the SMS app |
| `READ_CALL_LOG`, `WRITE_CALL_LOG`, `PROCESS_OUTGOING_CALLS` | `phone_dial` uses `ACTION_DIAL` — user reviews + dials in the dialer app |
| `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` | `telephony_info` reads only public, permissionless `TelephonyManager` fields |
| `QUERY_ALL_PACKAGES` | We use explicit `<queries>` entries (MAIN/LAUNCHER + URI schemes) |
| `MANAGE_EXTERNAL_STORAGE` | Scoped storage + FileProvider + MediaStore |
| `ACCESS_BACKGROUND_LOCATION` | Foreground location only |
| `CAMERA` | `camera_capture` delegates to system camera via Intent |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | We don't read incoming notifications |
| `BIND_ACCESSIBILITY_SERVICE` | We deep-link to settings, don't bind a service |
| `WRITE_SETTINGS` | `power_set_brightness` is per-window, not system-wide |
| `SYSTEM_ALERT_WINDOW` | Not used |
| `PACKAGE_USAGE_STATS` | Not used |
| `REQUEST_INSTALL_PACKAGES` | Not used |
| `FOREGROUND_SERVICE_*` | No foreground services |

If you add any of these later in a host app, expect a Permissions Declaration form review.

---

## Submission checklist

Before tapping **Release** in Play Console:

1. **Audit manifest merge output.** Run `./gradlew :app:mergeReleaseManifest` and inspect `app/build/intermediates/merged_manifest/release/AndroidManifest.xml`. Confirm only the permissions you intended are present.
2. **Remove what you don't need.** For each unused tool, add `tools:node="remove"` for its permissions. See [manifest-merging.md](manifest-merging.md).
3. **Fill the Permissions Declaration form** for every 🔴 / ⚫ entry still present.
4. **Fill the Data Safety form** using [PRIVACY.md](PRIVACY.md) as the source.
5. **Add a privacy policy URL** to Play Console (mandatory).
6. **Test the runtime prompts end-to-end** for every 🟡 permission. Users hitting an undeclared runtime prompt = silent failure → 1-star reviews → policy violation reports.
7. **Set the right app category** in Play Console. "Productivity", "Health & Fitness", or "Tools" cover most Weft hosts. Category drives policy thresholds.
