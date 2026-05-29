# iOS OsCapabilities — implementation backlog

- **Status:** Open
- **Date:** 2026-05-30
- **Tracks:** [Phase 3 of the KMP migration](../../runtime/src/commonMain/kotlin/dev/weft/android/WeftRuntime.kt) is shipping today with the substrate's runtime + persistence + agent loop in commonMain, but `:os-bridge` still ships androidMain-only `Android*` impls of every capability. iOS hosts that want to call [`WeftRuntime.create(WeftPlatform, …)`][ios-factory] need at minimum an `OsCapabilities` value to pass in.
- **Stop-gap today:** Compose `FakeOsCapabilities()` from `:harness:testing` (now KMP-published) — every sub-interface returns no-op success defaults; iOS hosts override the specific subsystems they need with native impls and let the rest stay Fake. Mirrors the substrate's `EmbedComponents` empty-list-on-iOS pattern.
- **End state:** A proper `IosOsCapabilities` composer in `:os-bridge`'s `iosMain` that wires the table below by default. Hosts then pass `IosOsCapabilities(applicationContext)` the same way they pass `AndroidOsCapabilities.create(context)` today.

[ios-factory]: ../../runtime/src/iosMain/kotlin/dev/weft/android/WeftRuntimeIos.kt

## Why iOS impls are not shipped yet

Each of the 33 sub-interfaces wraps a platform API surface. Some are 20-line shims (`Clipboard` → `UIPasteboard`); others (PDF text extraction; ML Kit's translation models; vendor-specific Bluetooth profiles) need genuine porting work against the iOS equivalents. We've intentionally deferred this until an iOS host actually needs to call the relevant tool — premature implementation pays storage + bundle-size cost for no agent-loop benefit.

The `FakeOsCapabilities` baseline means **every substrate tool that calls one of these interfaces will work syntactically on iOS today** — it just won't *do* anything. That's a deliberate trade. The substrate prefers visible-no-op semantics over a "this capability isn't available on iOS" error, because the agent loop already treats empty/null returns as failure-shaped data ("no events scheduled," "no devices paired," etc.) and can recover by asking the user.

## The backlog

Effort scale: **XS** = <30 LoC trivial wrapper; **S** = 30–80 LoC, single-API; **M** = 80–250 LoC, multi-step async flow or model-pulling; **L** = 250+ LoC or genuinely tricky (ML pipelines, vendor Bluetooth profiles).

Tools unblocked are the substrate `:tools` module entries that route through this sub-interface — i.e. the LLM tools that go silently dead on iOS today.

| Sub-interface | iOS native API | Effort | Tools unblocked | Notes |
| --- | --- | ---: | --- | --- |
| **KeyVault** | Keychain (Security.framework) — `SecItemAdd` / `SecItemCopyMatching` | S | All of `:oauth`, every provider credential lookup | Highest priority. Undercurrent already ships `KeychainKeyVaultGateway` in its host; that code lifts straight into `:os-bridge/iosMain` with minor signature changes. |
| **Clipboard** | `UIPasteboard.generalPasteboard` | XS | `clipboard_read`, `clipboard_write` | One-method wrapper. |
| **Sharing** | `UIActivityViewController` | S | `share_content` | Needs a top-VC lookup helper. |
| **Intents (open URL)** | `UIApplication.shared.openURL` | XS | `intent_open_url` | One call. |
| **Haptics** | `UIImpactFeedbackGenerator`, `UINotificationFeedbackGenerator` | XS | `haptics_impact`, `haptics_notify` | Map the substrate's `HapticEffect` enum to UIKit feedback styles. |
| **SystemInfo** | `UIDevice`, `NSLocale`, `NWPathMonitor`, `ProcessInfo` | S | `system_info`, `battery_status` | Already partly implemented by `iosDeviceSnapshot()` for the per-turn prefix — the trace-store variant just needs to surface it through the interface. |
| **Permissions** | `AVCaptureDevice.requestAccess`, `PHPhotoLibrary.requestAuthorization`, `CLLocationManager.requestWhenInUseAuthorization`, etc. | M | Gates every capability above that needs runtime authorization | iOS permissions are per-API; the substrate's `Permission` enum maps to ~7 iOS authorization domains. Cross-references the substrate's [`AndroidPermissions.toAndroidPermission`](../../os-bridge/src/androidMain/kotlin/dev/weft/osbridge/permissions/AndroidPermissions.kt) pattern. |
| **Notifications** | `UNUserNotificationCenter` (`add(UNNotificationRequest)`, `removePendingNotificationRequests`) | M | `notification_schedule`, `notification_cancel`, `notification_list_scheduled` | Needs a `UNUserNotificationCenterDelegate` to surface taps. The substrate already provides the `ScheduledNotificationKeyStore` persistence layer; the iOS impl just needs the platform-call wrappers + the SQLDelight-backed store from `:runtime`'s commonMain. |
| **Calendar** | EventKit (`EKEventStore.events(matching:)`, `EKEvent`) | M | `calendar_create`, `calendar_read`, `calendar_update`, `calendar_delete` | EventKit predicate construction matches the substrate's `CalendarFilter` shape. Need entitlement: `NSCalendarsFullAccessUsageDescription`. |
| **Contacts** | Contacts.framework (`CNContactStore`, `CNContactFetchRequest`) | M | `contacts_list`, `contacts_search` | Same pattern as Calendar. Entitlement: `NSContactsUsageDescription`. |
| **Files** | NSFileManager + URL-bookmark-based persistence | S | `files_save`, `files_read` | iOS's sandbox model means most "files" use UIDocumentPicker; the substrate's `FileSaveSpec` maps naturally. |
| **Location** | CoreLocation (`CLLocationManager`, `CLGeocoder`) | M | `location_current`, `location_geocode` | Delegate-based async; bridge through a `suspendCoroutine` adapter. Entitlement: `NSLocationWhenInUseUsageDescription`. |
| **Biometrics** | `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` | S | `biometric_authenticate` | One async call + reason string. |
| **Speech** | `SFSpeechRecognizer` + `SFSpeechAudioBufferRecognitionRequest` | M | `speech_recognize` | Undercurrent's voice-input path already wraps SFSpeechRecognizer in its iOS host; that code can lift directly. Entitlement: `NSSpeechRecognitionUsageDescription`. |
| **Audio recording** | `AVAudioRecorder` | M | `audio_record` | File-output to app sandbox; bridge through a coroutine adapter for the awaited result. |
| **Camera** | `UIImagePickerController(sourceType: .camera)` or `AVFoundation` capture session | M | `camera_capture` | UIImagePickerController is faster to wire; AVFoundation needed for custom UI. Either way needs a top-VC presenter. |
| **Vision (OCR + barcode)** | Vision.framework (`VNRecognizeTextRequest`, `VNDetectBarcodesRequest`) | M | `vision_ocr`, `vision_barcode_scan` | Pure async pipeline once a CIImage is in hand; the substrate's `OcrBlock` shape maps cleanly. Substantially less ceremony than ML Kit. |
| **MediaLibrary** | PhotoKit (`PHAsset.fetchAssets`, `PHImageManager`) | M | `media_library_query`, `media_library_load` | Async `PHFetchResult` enumeration. Entitlement: `NSPhotoLibraryUsageDescription`. |
| **MediaPicker** | `PHPickerViewController` (iOS 14+) | S | `media_picker_pick` | Pre-PhotoKit-style picker that handles auth internally — simpler than `MediaLibrary`. |
| **Pdf** | PDFKit (`PDFDocument`, `PDFPage`) | M | `pdf_read`, `pdf_create` | PDFKit's text extraction is much better than PDFBox-Android — the iOS path may end up cleaner than the Android one. |
| **Translation + Language ID** | NaturalLanguage.framework (`NLTokenizer`, `NLLanguageRecognizer`); on-device translation via `Translation` framework (iOS 17.4+) | M | `translate_text`, `detect_language` | Language detection is built in. Translation is iOS-version-gated; for older versions, return `Unsupported`. |
| **ImageOps (resize / crop / rotate)** | CIImage transforms or UIGraphicsImageRenderer | S | `image_resize`, `image_crop`, `image_rotate` | Pure data in / data out, no permissions. |
| **Apps** | `LSApplicationProxy` (private API; not viable) or `UIApplication.shared.canOpenURL` heuristic | S | `apps_list_launchable`, `apps_installed` | iOS deliberately doesn't expose the installed-app list. The substrate's `Apps.installed(bundleId)` can be implemented via `canOpenURL` with a URL scheme whitelist; full enumeration stays no-op. |
| **Telephony** | `CTTelephonyNetworkInfo` (deprecated in iOS 16 for carrier name) | S | `telephony_info` | Limited surface on iOS; CallKit can place calls but doesn't expose dial intent like Android. |
| **Bluetooth** | CoreBluetooth (`CBCentralManager`, `CBPeripheral`) — paired-device list not directly exposed | L | `bluetooth_list_paired`, `bluetooth_device_battery` | iOS hides the paired-device list outside of MFi accessories. The substrate's `BluetoothDeviceInfo` shape only fills for actively-connected peripherals discoverable via CoreBluetooth. Battery via the Service+Characteristic UUID standard. |
| **Wifi** | `NWPathMonitor` + `CNCopyCurrentNetworkInfo` (requires entitlement) | S | `wifi_info` | SSID requires `Access WiFi Information` entitlement and Location authorization. |
| **Volume** | `AVAudioSession` (system volume is observable, not directly settable on iOS without media playback) | M | `volume_get`, `volume_set` | Setting volume directly is restricted; the substrate's `Volume.set(...)` returns false on iOS until/unless a workaround is shipped (MPVolumeView slider injection — fragile). |
| **Power** | `UIApplication.shared.isIdleTimerDisabled`, `UIDevice.batteryLevel`, `UIScreen.brightness` | S | `power_keep_screen_on`, `power_set_brightness` | Three properties; one-method wrappers each. |
| **AppShortcuts** | NSUserActivity (or App Shortcuts framework iOS 16+) | M | `shortcut_push`, `shortcut_list`, `shortcut_remove` | App Shortcuts (donated from `AppIntents`) is the iOS-16+ path. NSUserActivity is the older fallback. The substrate's `ShortcutSpec` shape requires translation. |
| **Sensors** | CoreMotion (`CMPedometer`, `CMAmbientLightSensor` via `CMSensorRecorder`) | M | `sensors_step_count`, `sensors_ambient_light` | Step counter is straightforward (`CMPedometer`); ambient light is harder (`CMAmbientLightSensor` is rarely exposed; `UIScreen.brightness` is a proxy). |
| **UserContext** | `NSUserDefaults` + the substrate's `ContextRegistry` already commonMain | XS | `system_user_context` (provided already) | Just a host-supplied data block; commonMain code already does the work — no platform-specific code needed. |
| **SystemSettings** | `UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)` and the various `prefs:root=…` deep links | S | `settings_open` | Deep-link URL switching; map the substrate's `SettingsPanel` enum. |

## Recommended order of work

1. **KeyVault** (S) — unlocks every credential-bearing flow including OAuth.
2. **Clipboard** + **Sharing** + **Intents** + **Haptics** + **ImageOps** (XS/S each, ~5 hours total) — five quick wins.
3. **SystemInfo** + **Power** + **Volume(read-only)** + **Permissions** (S/M, ~1 day) — covers the per-turn context block and the substrate's quota policies.
4. **Notifications** + **Speech** + **MediaPicker** + **Camera** (M each, ~3 days) — the headline "show me / hear me" capability set.
5. **Location** + **Calendar** + **Contacts** + **Vision** + **Pdf** (M each, ~5 days) — the heavier permission flows and data extraction.
6. **Translation** + **Language ID** + **AppShortcuts** + **Sensors** + **Telephony** + **MediaLibrary** (M each, ~5 days) — long-tail surface.
7. **Bluetooth** + **Wifi(SSID)** (L, ~2 days) — entitlement-heavy; defer until a host needs it.
8. **Files** + **SystemSettings** + **Apps** (S, ~1 day) — finishers.

Total: ~3 weeks of focused work for one engineer for full iOS parity. The order above is roughly value-per-day; the first three groups cover ~85% of agent-loop usage.

## What to do when an iOS host hits an unsupported capability

The current `FakeOsCapabilities` baseline returns conservative no-ops: reads return empty/null, writes succeed silently. That's intentional for hermetic JVM tests but can hide bugs in production iOS use ("I asked the agent to schedule a notification — why didn't it fire?").

When real iOS hosts start shipping:

- **Recommended:** wrap each `FakeXxx` the host hasn't overridden with a thin `UnsupportedXxx` proxy that throws `UnsupportedOperationException("$capability.$method not supported by this host")`. That makes the missing-impl error visible at the LLM-tool-call site instead of producing silently empty data.
- **Cheaper alternative:** keep the FakeXxx baseline and rely on the agent loop's existing "empty result" recovery (it'll ask the user, or pick a different tool). This is less surfacing but requires no new code.

The substrate is willing to ship a `UnsupportedOsCapabilities` companion class in `:contracts` if hosts adopt this pattern often enough. For now it stays on this backlog.
