package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.ApprovalModeHolder
import dev.weft.contracts.Audio
import dev.weft.contracts.Apps
import dev.weft.contracts.AppInfo
import dev.weft.contracts.BatteryInfo
import dev.weft.contracts.BiometricResult
import dev.weft.contracts.Biometrics
import dev.weft.contracts.Bluetooth
import dev.weft.contracts.BluetoothDeviceInfo
import dev.weft.contracts.Calendar
import dev.weft.contracts.CalendarEvent
import dev.weft.contracts.CalendarEventId
import dev.weft.contracts.CalendarFilter
import dev.weft.contracts.CalendarPatch
import dev.weft.contracts.Camera
import dev.weft.contracts.Clipboard
import dev.weft.contracts.ContactFilter
import dev.weft.contracts.ContactSummary
import dev.weft.contracts.Contacts
import dev.weft.contracts.DeviceInfo
import dev.weft.contracts.DisplayInfo
import dev.weft.contracts.FileContent
import dev.weft.contracts.FileRef
import dev.weft.contracts.FileSaveSpec
import dev.weft.contracts.Files
import dev.weft.contracts.GeoResult
import dev.weft.contracts.HapticEffect
import dev.weft.contracts.Haptics
import dev.weft.contracts.HookRegistry
import dev.weft.contracts.InMemoryPlanStore
import dev.weft.contracts.Intents
import dev.weft.contracts.KeyVault
import dev.weft.contracts.Location
import dev.weft.contracts.LocationFix
import dev.weft.contracts.MediaFilter
import dev.weft.contracts.MediaItem
import dev.weft.contracts.MediaKind
import dev.weft.contracts.MediaLibrary
import dev.weft.contracts.MediaPicker
import dev.weft.contracts.MediaPickerKind
import dev.weft.contracts.NetworkInfo
import dev.weft.contracts.NetworkTransport
import dev.weft.contracts.NotificationHandle
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.Notifications
import dev.weft.contracts.OcrResult
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.Pdf
import dev.weft.contracts.PdfRenderResult
import dev.weft.contracts.PdfTextResult
import dev.weft.contracts.Permission
import dev.weft.contracts.PermissionState
import dev.weft.contracts.Permissions
import dev.weft.contracts.PowerSource
import dev.weft.contracts.ScheduleFilter
import dev.weft.contracts.ScheduleSpec
import dev.weft.contracts.ScheduledNotification
import dev.weft.contracts.ScriptStorage
import dev.weft.contracts.AppShortcuts
import dev.weft.contracts.ImageOps
import dev.weft.contracts.Power
import dev.weft.contracts.RectPx
import dev.weft.contracts.Sensors
import dev.weft.contracts.SettingsPanel
import dev.weft.contracts.ShareContent
import dev.weft.contracts.ShareTarget
import dev.weft.contracts.Sharing
import dev.weft.contracts.ShortcutSpec
import dev.weft.contracts.Speech
import dev.weft.contracts.SpeechRecognitionResult
import dev.weft.contracts.SystemInfo
import dev.weft.contracts.SystemSettings
import dev.weft.contracts.Telephony
import dev.weft.contracts.TelephonyInfo
import dev.weft.contracts.Translation
import dev.weft.contracts.Volume
import dev.weft.contracts.VolumeStream
import dev.weft.contracts.Wifi
import dev.weft.contracts.WifiInfo
import dev.weft.contracts.UiBridge
import dev.weft.contracts.UserAnswer
import dev.weft.contracts.UserContext
import dev.weft.contracts.UserContextField
import dev.weft.contracts.Vision
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Pure unit tests for `date_compute`. Doesn't touch any OS capability —
 * the tool's logic is pure kotlinx.datetime arithmetic. We use a stub
 * OsCapabilities so we can construct WeftContext without depending on
 * :harness:testing (which would create a circular module dep).
 */
class DateComputeToolTest : StringSpec({

    val tool = DateComputeTool(stubContext())

    "op=now returns current iso + epoch" {
        runTest {
            val result = tool.executeWeft(DateComputeTool.Args(op = "now"))
            result.ok shouldBe true
            result.iso.shouldNotBeNull()
            result.epochMs.shouldNotBeNull()
        }
    }

    "op=add with DAY shifts the anchor forward" {
        runTest {
            val result = tool.executeWeft(
                DateComputeTool.Args(
                    op = "add",
                    anchorIso = "2026-05-26T12:00:00Z",
                    amount = 3,
                    unit = "DAY",
                    tz = "UTC",
                ),
            )
            result.ok shouldBe true
            result.iso shouldBe "2026-05-29T12:00:00Z"
        }
    }

    "op=subtract with HOUR shifts backward" {
        runTest {
            val result = tool.executeWeft(
                DateComputeTool.Args(
                    op = "subtract",
                    anchorIso = "2026-05-26T12:00:00Z",
                    amount = 5,
                    unit = "HOUR",
                    tz = "UTC",
                ),
            )
            result.ok shouldBe true
            result.iso shouldBe "2026-05-26T07:00:00Z"
        }
    }

    "op=diff returns delta in requested unit" {
        runTest {
            val result = tool.executeWeft(
                DateComputeTool.Args(
                    op = "diff",
                    anchorIso = "2026-05-26T00:00:00Z",
                    otherIso = "2026-05-29T00:00:00Z",
                    unit = "DAY",
                ),
            )
            result.ok shouldBe true
            result.amount shouldBe 3L
        }
    }

    "op=weekday names the day" {
        runTest {
            // 2026-05-26 is a Tuesday in UTC.
            val result = tool.executeWeft(
                DateComputeTool.Args(
                    op = "weekday",
                    anchorIso = "2026-05-26T12:00:00Z",
                    tz = "UTC",
                ),
            )
            result.ok shouldBe true
            result.weekday shouldBe "TUESDAY"
        }
    }

    "op=start_of DAY zeroes the time" {
        runTest {
            val result = tool.executeWeft(
                DateComputeTool.Args(
                    op = "start_of",
                    anchorIso = "2026-05-26T15:42:00Z",
                    unit = "DAY",
                    tz = "UTC",
                ),
            )
            result.ok shouldBe true
            result.iso shouldBe "2026-05-26T00:00:00Z"
        }
    }

    "op=end_of MONTH lands on last second of last day" {
        runTest {
            val result = tool.executeWeft(
                DateComputeTool.Args(
                    op = "end_of",
                    anchorIso = "2026-02-10T00:00:00Z",
                    unit = "MONTH",
                    tz = "UTC",
                ),
            )
            result.ok shouldBe true
            // Feb 2026 has 28 days; end-of-month = 2026-02-28T23:59:59.999Z
            result.iso shouldBe "2026-02-28T23:59:59.999Z"
        }
    }

    "unknown op returns ok=false with explanatory error" {
        runTest {
            val result = tool.executeWeft(DateComputeTool.Args(op = "nonsense"))
            result.ok shouldBe false
            result.error.shouldNotBeNull()
        }
    }

    "diff missing otherIso fails gracefully" {
        runTest {
            val result = tool.executeWeft(
                DateComputeTool.Args(op = "diff", anchorIso = "2026-05-26T00:00:00Z"),
            )
            result.ok shouldBe false
            result.amount.shouldBeNull()
        }
    }
})

// ----- Minimal stub OsCapabilities for the test --------------------------
// We can't depend on :harness:testing FakeOsCapabilities here (it depends
// on :contracts only via this module's API, but importing it would create
// a cycle: testing → tools doesn't exist, but tools test depending on
// testing would couple them at the wrong layer). So define the smallest
// stub that satisfies WeftContext.

internal fun stubContext(): WeftContext = WeftContext(
    os = StubOs,
    ui = StubUi,
    storageFactory = { StubScriptStorage() },
    hooks = HookRegistry.EMPTY,
    approvalMode = ApprovalModeHolder(),
    planStore = InMemoryPlanStore(),
)

private object StubOs : OsCapabilities {
    override val notifications = StubNotifications
    override val calendar = StubCalendar
    override val contacts = StubContacts
    override val files = StubFiles
    override val sharing = StubSharing
    override val intents = StubIntents
    override val keyVault = StubKeyVault
    override val userContext = StubUserContext
    override val permissions = StubPermissions
    override val clipboard = StubClipboard
    override val biometrics = StubBiometrics
    override val haptics = StubHaptics
    override val vision = StubVision
    override val location = StubLocation
    override val speech = StubSpeech
    override val audio = StubAudio
    override val camera = StubCamera
    override val systemInfo = StubSystemInfo
    override val pdf = StubPdf
    override val bluetooth = StubBluetooth
    override val mediaLibrary = StubMediaLibrary
    override val apps = StubApps
    override val sensors = StubSensors
    override val telephony = StubTelephony
    override val wifi = StubWifi
    override val volume = StubVolume
    override val power = StubPower
    override val settings = StubSystemSettings
    override val shortcuts = StubAppShortcuts
    override val translation = StubTranslation
    override val imageOps = StubImageOps
    override val mediaPicker = StubMediaPicker
}

private object StubNotifications : Notifications {
    override suspend fun showNow(spec: NotificationSpec) = NotificationHandle("stub")
    override suspend fun schedule(spec: NotificationSpec, schedule: ScheduleSpec) =
        NotificationHandle("stub")
    override suspend fun cancel(handle: NotificationHandle) = false
    override suspend fun listScheduled(filter: ScheduleFilter?) = emptyList<ScheduledNotification>()
}

private object StubCalendar : Calendar {
    override suspend fun read(filter: CalendarFilter) = emptyList<CalendarEvent>()
    override suspend fun create(event: CalendarEvent) = CalendarEventId("stub")
    override suspend fun update(id: CalendarEventId, patch: CalendarPatch) = false
    override suspend fun delete(id: CalendarEventId) = false
}

private object StubContacts : Contacts {
    override suspend fun read(filter: ContactFilter) = emptyList<ContactSummary>()
}

private object StubFiles : Files {
    override suspend fun save(spec: FileSaveSpec) = FileRef("stub", 0L)
    override suspend fun read(uri: String, asBase64: Boolean) = FileContent(mimeType = "", sizeBytes = 0L)
    override suspend fun share(uri: String, target: ShareTarget) = false
}

private object StubSharing : Sharing {
    override suspend fun share(content: ShareContent, target: ShareTarget) = false
}

private object StubIntents : Intents {
    override suspend fun launchApp(target: String, payload: JsonObject?) = false
    override suspend fun openUrl(url: String, inApp: Boolean) = false
    override suspend fun openMapsDirections(to: String, from: String?, mode: String) = false
    override suspend fun openAlarmSet(hour: Int, minute: Int, label: String?) = false
}

private object StubKeyVault : KeyVault {
    override suspend fun put(alias: String, secret: String) {}
    override suspend fun get(alias: String) = null
    override suspend fun remove(alias: String) = false
    override suspend fun exists(alias: String) = false
}

private object StubUserContext : UserContext {
    override suspend fun snapshot(fields: Set<UserContextField>): JsonObject = buildJsonObject {}
}

private object StubPermissions : Permissions {
    override suspend fun check(permission: Permission) = PermissionState.GRANTED
    override suspend fun request(permission: Permission) = PermissionState.GRANTED
}

private object StubClipboard : Clipboard {
    override suspend fun read() = null
    override suspend fun write(text: String) {}
    override suspend fun clear() {}
}

private object StubBiometrics : Biometrics {
    override suspend fun authenticate(reason: String) = BiometricResult.NotAvailable
}

private object StubHaptics : Haptics {
    override suspend fun perform(effect: HapticEffect) {}
}

private object StubVision : Vision {
    override suspend fun ocr(imageUri: String) = OcrResult(text = "")
    override suspend fun barcodes(imageUri: String) = emptyList<dev.weft.contracts.DecodedBarcode>()
}

private object StubLocation : Location {
    override suspend fun current() = null
    override suspend fun geocode(query: String, maxResults: Int) = emptyList<GeoResult>()
    override suspend fun reverseGeocode(latitude: Double, longitude: Double, maxResults: Int) =
        emptyList<GeoResult>()
}

private object StubSpeech : Speech {
    override suspend fun say(text: String, locale: String?) = false
    override suspend fun stop() {}
    override suspend fun recognize(locale: String?, maxDurationMs: Long): SpeechRecognitionResult? = null
}

private object StubAudio : Audio {
    override suspend fun record(maxDurationMs: Long, namePrefix: String) = null
    override suspend fun stop() {}
}

private object StubCamera : Camera {
    override suspend fun captureImage(namePrefix: String) = null
}

private object StubSystemInfo : SystemInfo {
    override suspend fun battery() = BatteryInfo(null, false, PowerSource.UNKNOWN, false)
    override suspend fun network() = NetworkInfo(false, NetworkTransport.NONE, false)
    override suspend fun device() = DeviceInfo("?", "?", "stub", "0", -1, "en-US", "UTC", 0, 0)
    override suspend fun display() = DisplayInfo(false, 0, 0, 1f, 60f, null, false)
}

private object StubPdf : Pdf {
    override suspend fun extractText(uri: String, pageRange: String?, maxPages: Int) =
        PdfTextResult(text = "", pageCount = 0, extractedPages = emptyList())
    override suspend fun renderPages(uri: String, pages: List<Int>?, scale: Float) =
        PdfRenderResult(imageUris = emptyList(), pageCount = 0)
    override suspend fun create(title: String, body: String, fileName: String) = FileRef("stub", 0L)
}

private object StubBluetooth : Bluetooth {
    override suspend fun listPaired() = emptyList<BluetoothDeviceInfo>()
    override suspend fun openSettings() = false
    override suspend fun deviceBattery(address: String) = null
}

private object StubMediaLibrary : MediaLibrary {
    override suspend fun listRecent(kinds: Set<MediaKind>, limit: Int) = emptyList<MediaItem>()
    override suspend fun query(filter: MediaFilter) = emptyList<MediaItem>()
}

private object StubApps : Apps {
    override suspend fun isInstalled(packageName: String) = false
    override suspend fun listLaunchable(limit: Int) = emptyList<AppInfo>()
}

private object StubSensors : Sensors {
    override suspend fun stepsToday(): Int? = null
    override suspend fun ambientLightLux(): Float? = null
}

private object StubTelephony : Telephony {
    override suspend fun dial(phoneNumber: String) = false
    override suspend fun composeSms(phoneNumber: String, body: String?) = false
    override suspend fun info() = TelephonyInfo()
}

private object StubWifi : Wifi {
    override suspend fun info() = WifiInfo(enabled = false, connected = false)
}

private object StubVolume : Volume {
    override suspend fun get(stream: VolumeStream) = 0f
    override suspend fun set(stream: VolumeStream, normalized: Float) = false
}

private object StubPower : Power {
    override suspend fun keepScreenOn(enabled: Boolean) = false
    override suspend fun setBrightness(normalized: Float) = false
}

private object StubSystemSettings : SystemSettings {
    override suspend fun open(panel: SettingsPanel) = false
}

private object StubAppShortcuts : AppShortcuts {
    override suspend fun push(spec: ShortcutSpec) = false
    override suspend fun remove(id: String) = false
    override suspend fun list() = emptyList<ShortcutSpec>()
}

private object StubTranslation : Translation {
    override suspend fun translate(text: String, target: String, source: String?): String? = null
    override suspend fun detectLanguage(text: String) = "und"
    override suspend fun supportedLanguages() = emptyList<String>()
}

private object StubImageOps : ImageOps {
    override suspend fun resize(uri: String, maxEdgePx: Int, namePrefix: String) = null
    override suspend fun crop(uri: String, rect: RectPx, namePrefix: String) = null
    override suspend fun rotate(uri: String, degrees: Int, namePrefix: String) = null
}

private object StubMediaPicker : MediaPicker {
    override suspend fun pick(kind: MediaPickerKind, maxItems: Int): List<String> = emptyList()
}

private object StubUi : UiBridge {
    override suspend fun askUser(
        question: String,
        kind: dev.weft.contracts.AskKind,
        options: List<String>,
    ): UserAnswer = UserAnswer.Text("")
    override suspend fun confirmDestructive(action: String, body: String?) = false
    override suspend fun showInfo(title: String, body: String?) {}
    override suspend fun emit(update: dev.weft.contracts.UIUpdate) {}
}

private class StubScriptStorage : ScriptStorage {
    private val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
    override suspend fun get(key: String): kotlinx.serialization.json.JsonElement? = map[key]
    override suspend fun put(
        key: String,
        value: kotlinx.serialization.json.JsonElement,
        ttl: kotlin.time.Duration?,
    ) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
    override suspend fun list(prefix: String): List<String> =
        map.keys.filter { it.startsWith(prefix) }
}
