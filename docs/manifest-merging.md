# Manifest merging — opting out of Weft permissions

Weft's `:os-bridge` ships a manifest declaring permissions for every
substrate tool. The Android Gradle Plugin's manifest merger combines
this with the host app's manifest. A host app that doesn't use every
tool can — and **should** — strip the corresponding permission via the
manifest-merger's `tools:node="remove"` mechanism. This:

- Avoids Play Console Permissions Declaration form work for tools you
  don't use (especially the 🔴-restricted ones from
  [PLAY-POLICY.md](PLAY-POLICY.md)).
- Cuts down what shows in the OS-level "App permissions" screen,
  which users inspect before installing.
- Lowers attack surface — fewer manifest-declared permissions = less
  rights-escalation potential in a compromised process.

This is **only** the manifest side. The matching Weft tool will still
build — it just fails at the permission gate or the platform API.

---

## Quick reference: what to keep, what to strip

If you use… | …keep these permissions
---|---
Conversation only (no OS tools) | `INTERNET`, `POST_NOTIFICATIONS`
`audio_record`, `speech_recognize` | `RECORD_AUDIO`
`location_*` (or `wifi_info` SSID) | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
`contacts_read` | `READ_CONTACTS`
`calendar_*` | `READ_CALENDAR`, `WRITE_CALENDAR`
`media_pick_*` (Photo Picker) | nothing extra
`media_list_recent`, `media_query` | `READ_MEDIA_*` + `READ_EXTERNAL_STORAGE`
`bluetooth_*` | `BLUETOOTH_CONNECT` + `BLUETOOTH`
`sensor_steps_today` | `ACTIVITY_RECOGNITION`
`schedule_create` with **exact** timing | `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM` (Play scrutiny)
`schedule_create` with **inexact** timing | nothing (substrate falls back automatically)
`volume_set` | `MODIFY_AUDIO_SETTINGS`
`wifi_info` | `ACCESS_WIFI_STATE`
`network_fetch`, `network_status` | `INTERNET`, `ACCESS_NETWORK_STATE`
`haptics_feedback` | `VIBRATE`
`biometric_authenticate` | `USE_BIOMETRIC`
`notify_*` | `POST_NOTIFICATIONS`

---

## How to remove a permission

Add the `tools` namespace to your host app's manifest and use
`tools:node="remove"`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">

    <!-- Remove permissions the host doesn't use. The merger drops
         them BEFORE the merged manifest goes to the build / Play. -->
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"
                     tools:node="remove" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM"
                     tools:node="remove" />

    <application …>
        …
    </application>
</manifest>
```

Verify by running `./gradlew :app:mergeReleaseManifest` and inspecting
`app/build/intermediates/merged_manifest/release/AndroidManifest.xml`.
The stripped permissions should NOT appear in the merged output.

---

## Recipe 1 — minimal LLM chat app, no OS tools

Strips everything except `INTERNET`. Suitable for a host that uses Weft
as a chat substrate without delegating to the device.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">

    <!-- Keep INTERNET (LLM calls). -->

    <!-- Strip everything else. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" tools:node="remove" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" tools:node="remove" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_CONTACTS" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_CALENDAR" tools:node="remove" />
    <uses-permission android:name="android.permission.WRITE_CALENDAR" tools:node="remove" />
    <uses-permission android:name="android.permission.VIBRATE" tools:node="remove" />
    <uses-permission android:name="android.permission.USE_BIOMETRIC" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" tools:node="remove" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" tools:node="remove" />
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" tools:node="remove" />
    <uses-permission android:name="android.permission.BLUETOOTH" tools:node="remove" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" tools:node="remove" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" tools:node="remove" />

    <application>…</application>
</manifest>
```

Also drop the tools you don't register — strip them in your
`extraToolsFactory`/`coreTools` filter or via Koog's tool registry.
Manifest stripping prevents the OS from advertising the permission;
unregistered tools prevent the LLM from picking them.

---

## Recipe 2 — productivity host without exact alarms

Most general-purpose Weft hosts. Strips the Play-scrutinized exact
alarm permissions; everything else stays so the substrate's other
tools work out of the box.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" tools:node="remove" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" tools:node="remove" />

    <application>…</application>
</manifest>
```

The `schedule_create` tool automatically falls back to
`AlarmManager.set` (inexact) when the host can't grant exact-alarm
permission — no code change needed in the substrate or the host.

---

## Recipe 3 — drop the gallery, keep the Photo Picker

If your host never enumerates the gallery (only the user picks files),
strip the 🟡 media permissions. The `media_pick_*` tools still work.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" tools:node="remove" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" tools:node="remove" />

    <application>…</application>
</manifest>
```

Then in your `extraToolsFactory`, filter out the `MediaListRecentTool`
and `MediaQueryTool` so the LLM doesn't try to call them and get a
permission-denied error.

---

## Recipe 4 — no Bluetooth

```xml
<uses-permission android:name="android.permission.BLUETOOTH" tools:node="remove" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:node="remove" />
```

---

## Recipe 5 — no step counter

```xml
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" tools:node="remove" />
```

---

## Removing `<queries>` entries

Same `tools:node="remove"` pattern works on `<queries>` children. If
your host never uses `phone_dial`, drop the `tel` scheme query:

```xml
<queries>
    <intent tools:node="remove">
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="tel" />
    </intent>
</queries>
```

For the MAIN/LAUNCHER query (used by `app_list_launchable`), removing
it means that tool returns only your own app — usually fine; the LLM
will fall back to other tools.

---

## Adding `<queries><package>` for specific apps

The substrate doesn't pre-declare arbitrary app packages because every
host needs a different set. To make `app_installed` work for specific
packages, add them in your host manifest:

```xml
<queries>
    <package android:name="com.spotify.music" />
    <package android:name="com.google.android.apps.maps" />
    <package android:name="com.whatsapp" />
</queries>
```

Without these, `app_installed("com.spotify.music")` returns false even
if Spotify is installed on the device — Android 11+ package visibility
hides it from your app.

---

## Troubleshooting the merger

**Symptom:** "Manifest merger failed" with a conflict on a permission.

Cause: the substrate declares an attribute that conflicts with your
override (typically `maxSdkVersion`).

Fix: use `tools:node="replace"` to override completely, or
`tools:replace="android:maxSdkVersion"` to override that one attribute:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
                 android:maxSdkVersion="28"
                 tools:replace="android:maxSdkVersion" />
```

**Symptom:** "tools:node='remove' has no effect".

Cause: another library (Firebase, Play services, AndroidX) is also
declaring the permission. `tools:node="remove"` only removes the host
+ substrate declaration; the other library re-adds it.

Fix: identify the library via the merge report
(`app/build/outputs/logs/manifest-merger-*.txt`) and either upgrade
that library, or accept the permission stays. The Play Console doesn't
care WHO declared a permission, only that it's in the final manifest.

---

## Verification

After editing the host manifest:

```bash
# Inspect the merged output:
./gradlew :app:mergeReleaseManifest
cat app/build/intermediates/merged_manifest/release/AndroidManifest.xml | grep uses-permission

# Read the merge report (shows who added/removed what):
cat app/build/outputs/logs/manifest-merger-release-report.txt
```

The merged manifest is what Play sees. If a permission's there, it's
declared. If it's not, it isn't.
