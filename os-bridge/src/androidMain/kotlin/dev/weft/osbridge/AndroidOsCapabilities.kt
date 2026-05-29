package dev.weft.osbridge

import android.content.Context
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
import dev.weft.contracts.UserContextField
import dev.weft.contracts.Vision
import dev.weft.contracts.Volume
import dev.weft.contracts.Wifi
import dev.weft.osbridge.apps.AndroidApps
import dev.weft.osbridge.audio.AndroidAudio
import dev.weft.osbridge.biometrics.AndroidBiometrics
import dev.weft.osbridge.bluetooth.AndroidBluetooth
import dev.weft.osbridge.camera.AndroidCamera
import dev.weft.osbridge.clipboard.AndroidClipboard
import dev.weft.osbridge.haptics.AndroidHaptics
import dev.weft.osbridge.imageops.AndroidImageOps
import dev.weft.osbridge.keyvault.AndroidKeyVault
import dev.weft.osbridge.location.AndroidLocation
import dev.weft.osbridge.medialibrary.AndroidMediaLibrary
import dev.weft.osbridge.mediapicker.AndroidMediaPicker
import dev.weft.osbridge.notifications.AndroidNotifications
import dev.weft.osbridge.pdf.AndroidPdf
import dev.weft.osbridge.permissions.AndroidPermissions
import dev.weft.osbridge.power.AndroidPower
import dev.weft.osbridge.sensors.AndroidSensors
import dev.weft.osbridge.settings.AndroidSystemSettings
import dev.weft.osbridge.shortcuts.AndroidAppShortcuts
import dev.weft.osbridge.speech.AndroidSpeech
import dev.weft.osbridge.systeminfo.AndroidSystemInfo
import dev.weft.osbridge.telephony.AndroidTelephony
import dev.weft.osbridge.translation.AndroidTranslation
import dev.weft.osbridge.vision.AndroidVision
import dev.weft.osbridge.volume.AndroidVolume
import dev.weft.osbridge.wifi.AndroidWifi
import dev.weft.osbridge.notifications.InMemoryStringKeyStore
import dev.weft.osbridge.notifications.ScheduledNotificationStore
import dev.weft.osbridge.notifications.StringKeyStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Single entry point the app uses to obtain platform OS capabilities.
 *
 * Currently implements: Notifications, KeyVault, Intents, Sharing, Calendar,
 * Contacts. Files remains stubbed (Phase 5+).
 */
class AndroidOsCapabilities private constructor(
    override val notifications: Notifications,
    override val keyVault: KeyVault,
    override val calendar: Calendar,
    override val contacts: Contacts,
    override val files: Files,
    override val sharing: Sharing,
    override val intents: Intents,
    override val userContext: UserContext,
    override val permissions: Permissions,
    override val clipboard: Clipboard,
    override val biometrics: Biometrics,
    override val haptics: Haptics,
    override val vision: Vision,
    override val location: Location,
    override val speech: Speech,
    override val audio: Audio,
    override val camera: Camera,
    override val systemInfo: SystemInfo,
    override val pdf: Pdf,
    override val bluetooth: Bluetooth,
    override val mediaLibrary: MediaLibrary,
    override val apps: Apps,
    override val sensors: Sensors,
    override val telephony: Telephony,
    override val wifi: Wifi,
    override val volume: Volume,
    override val power: Power,
    override val settings: SystemSettings,
    override val shortcuts: AppShortcuts,
    override val translation: Translation,
    override val imageOps: ImageOps,
    override val mediaPicker: MediaPicker,
) : OsCapabilities {

    companion object {
        @JvmStatic
        fun create(
            context: Context,
            /**
             * Backing store for scheduled-notification persistence. Default is
             * in-memory (loses schedules on app restart). `WeftRuntime`
             * passes a SQLDelight-backed implementation so schedules survive.
             */
            scheduledNotificationStore: StringKeyStore = InMemoryStringKeyStore(),
        ): AndroidOsCapabilities {
            val appContext = context.applicationContext
            val store = ScheduledNotificationStore(scheduledNotificationStore)
            return AndroidOsCapabilities(
                notifications = AndroidNotifications(appContext, store),
                keyVault = AndroidKeyVault.create(appContext),
                calendar = dev.weft.osbridge.calendar.AndroidCalendar(appContext),
                contacts = dev.weft.osbridge.contacts.AndroidContacts(appContext),
                files = dev.weft.osbridge.files.AndroidFiles(appContext),
                sharing = dev.weft.osbridge.sharing.AndroidSharing(appContext),
                intents = dev.weft.osbridge.intents.AndroidIntents(appContext),
                userContext = MinimalUserContext,
                permissions = AndroidPermissions(appContext),
                clipboard = AndroidClipboard(appContext),
                biometrics = AndroidBiometrics(appContext),
                haptics = AndroidHaptics(appContext),
                vision = AndroidVision(appContext),
                location = AndroidLocation(appContext),
                speech = AndroidSpeech(appContext),
                audio = AndroidAudio(appContext),
                camera = AndroidCamera(appContext),
                systemInfo = AndroidSystemInfo(appContext),
                pdf = AndroidPdf(appContext),
                bluetooth = AndroidBluetooth(appContext),
                mediaLibrary = AndroidMediaLibrary(appContext),
                apps = AndroidApps(appContext),
                sensors = AndroidSensors(appContext),
                telephony = AndroidTelephony(appContext),
                wifi = AndroidWifi(appContext),
                volume = AndroidVolume(appContext),
                power = AndroidPower(appContext),
                settings = AndroidSystemSettings(appContext),
                shortcuts = AndroidAppShortcuts(appContext),
                translation = AndroidTranslation(appContext),
                imageOps = AndroidImageOps(appContext),
                mediaPicker = AndroidMediaPicker(appContext),
            )
        }
    }
}

private object MinimalUserContext : UserContext {
    override suspend fun snapshot(fields: Set<UserContextField>): JsonObject = buildJsonObject {
        if (UserContextField.TIME in fields) put("timeEpochMs", System.currentTimeMillis())
        if (UserContextField.TIMEZONE in fields) put("timezone", java.util.TimeZone.getDefault().id)
        if (UserContextField.LOCALE in fields) put("locale", java.util.Locale.getDefault().toLanguageTag())
        // Other fields land in Phase 5.
    }
}

// AndroidPermissions + the Permission→Android-manifest mapper moved to
// dev.weft.osbridge.permissions.AndroidPermissions (its own file) — it
// now needs Application-lifecycle tracking + ActivityResultRegistry
// access for real runtime prompting, which is too much to inline here.

private fun nyi(): Nothing = error("Not implemented yet — lands in a later phase. See docs/09-roadmap.md.")
