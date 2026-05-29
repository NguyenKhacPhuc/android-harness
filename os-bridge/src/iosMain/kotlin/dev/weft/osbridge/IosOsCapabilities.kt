package dev.weft.osbridge

import dev.weft.contracts.AppShortcuts
import dev.weft.contracts.Apps
import dev.weft.contracts.Audio
import dev.weft.contracts.Biometrics
import dev.weft.contracts.Bluetooth
import dev.weft.contracts.Calendar
import dev.weft.contracts.Camera
import dev.weft.contracts.Clipboard
import dev.weft.contracts.Contacts
import dev.weft.contracts.Files
import dev.weft.contracts.Haptics
import dev.weft.contracts.ImageOps
import dev.weft.contracts.Intents
import dev.weft.contracts.KeyVault
import dev.weft.contracts.Location
import dev.weft.contracts.MediaLibrary
import dev.weft.contracts.MediaPicker
import dev.weft.contracts.Notifications
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.Pdf
import dev.weft.contracts.Permissions
import dev.weft.contracts.Power
import dev.weft.contracts.Sensors
import dev.weft.contracts.Sharing
import dev.weft.contracts.Speech
import dev.weft.contracts.SystemInfo
import dev.weft.contracts.SystemSettings
import dev.weft.contracts.Telephony
import dev.weft.contracts.Translation
import dev.weft.contracts.UserContext
import dev.weft.contracts.Vision
import dev.weft.contracts.Volume
import dev.weft.contracts.Wifi
import dev.weft.osbridge.apps.IosApps
import dev.weft.osbridge.audio.IosAudio
import dev.weft.osbridge.biometrics.IosBiometrics
import dev.weft.osbridge.bluetooth.IosBluetooth
import dev.weft.osbridge.calendar.IosCalendar
import dev.weft.osbridge.camera.IosCamera
import dev.weft.osbridge.clipboard.IosClipboard
import dev.weft.osbridge.contacts.IosContacts
import dev.weft.osbridge.files.IosFiles
import dev.weft.osbridge.haptics.IosHaptics
import dev.weft.osbridge.imageops.IosImageOps
import dev.weft.osbridge.intents.IosIntents
import dev.weft.osbridge.keyvault.IosKeyVault
import dev.weft.osbridge.location.IosLocation
import dev.weft.osbridge.medialibrary.IosMediaLibrary
import dev.weft.osbridge.mediapicker.IosMediaPicker
import dev.weft.osbridge.notifications.IosNotifications
import dev.weft.osbridge.pdf.IosPdf
import dev.weft.osbridge.permissions.IosPermissions
import dev.weft.osbridge.power.IosPower
import dev.weft.osbridge.sensors.IosSensors
import dev.weft.osbridge.sharing.IosSharing
import dev.weft.osbridge.shortcuts.IosAppShortcuts
import dev.weft.osbridge.speech.IosSpeech
import dev.weft.osbridge.systeminfo.IosSystemInfo
import dev.weft.osbridge.settings.IosSystemSettings
import dev.weft.osbridge.telephony.IosTelephony
import dev.weft.osbridge.translation.IosTranslation
import dev.weft.osbridge.usercontext.IosUserContext
import dev.weft.osbridge.vision.IosVision
import dev.weft.osbridge.volume.IosVolume
import dev.weft.osbridge.wifi.IosWifi

/**
 * iOS [OsCapabilities] composer — mirrors the shape of
 * [dev.weft.osbridge.AndroidOsCapabilities.create] from androidMain.
 *
 * Every sub-interface defaults to its `Ios<Capability>` stub. Each
 * stub's methods throw [NotImplementedError] via [TODO] with a
 * descriptive message pointing at the iOS-native API to wrap.
 *
 * **Usage pattern.** Hosts compose by overriding the subsystems they've
 * actually implemented; the rest stay loud-failure stubs:
 *
 * ```kotlin
 * val osCapabilities = IosOsCapabilities(
 *     keyVault = MyKeychainKeyVault(),                  // implemented
 *     clipboard = MyUIPasteboardClipboard(),            // implemented
 *     location = MyCLLocationManagerLocation(),         // implemented
 *     // every other capability defaults to its Ios stub → TODO()
 * )
 * WeftRuntime.create(WeftPlatform(), osCapabilities, ...)
 * ```
 *
 * **Loud-failure semantics.** When a tool routes through an
 * unimplemented capability the LLM gets a clear
 * `NotImplementedError("IosVision.ocr — wrap Vision.framework
 * VNRecognizeTextRequest")` at the call site instead of the silent
 * empty-result no-ops the [dev.weft.harness.testing.FakeOsCapabilities]
 * baseline returns. The substrate prefers loud failure for production
 * iOS hosts so missing-impl bugs surface at the agent loop instead of
 * sliding past as "the capability returned nothing."
 *
 * Hosts that prefer silent no-ops can still wire
 * `FakeOsCapabilities(...)` from `:harness:testing` instead — that's
 * the test-fixture path.
 *
 * See [`docs/architecture/ios-os-capabilities.md`][backlog] for the
 * implementation backlog (native API to wrap, effort, priority order).
 *
 * [backlog]: ../../../../../../../docs/architecture/ios-os-capabilities.md
 */
public class IosOsCapabilities(
    override val notifications: Notifications = IosNotifications(),
    override val calendar: Calendar = IosCalendar(),
    override val contacts: Contacts = IosContacts(),
    override val files: Files = IosFiles(),
    override val sharing: Sharing = IosSharing(),
    override val intents: Intents = IosIntents(),
    override val keyVault: KeyVault = IosKeyVault(),
    override val userContext: UserContext = IosUserContext(),
    override val permissions: Permissions = IosPermissions(),
    override val clipboard: Clipboard = IosClipboard(),
    override val biometrics: Biometrics = IosBiometrics(),
    override val haptics: Haptics = IosHaptics(),
    override val vision: Vision = IosVision(),
    override val location: Location = IosLocation(),
    override val speech: Speech = IosSpeech(),
    override val audio: Audio = IosAudio(),
    override val camera: Camera = IosCamera(),
    override val systemInfo: SystemInfo = IosSystemInfo(),
    override val pdf: Pdf = IosPdf(),
    override val bluetooth: Bluetooth = IosBluetooth(),
    override val mediaLibrary: MediaLibrary = IosMediaLibrary(),
    override val apps: Apps = IosApps(),
    override val sensors: Sensors = IosSensors(),
    override val telephony: Telephony = IosTelephony(),
    override val wifi: Wifi = IosWifi(),
    override val volume: Volume = IosVolume(),
    override val power: Power = IosPower(),
    override val settings: SystemSettings = IosSystemSettings(),
    override val shortcuts: AppShortcuts = IosAppShortcuts(),
    override val translation: Translation = IosTranslation(),
    override val imageOps: ImageOps = IosImageOps(),
    override val mediaPicker: MediaPicker = IosMediaPicker(),
) : OsCapabilities
