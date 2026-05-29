package dev.weft.harness.testing

import dev.weft.contracts.AppInfo
import dev.weft.contracts.AppShortcuts
import dev.weft.contracts.Apps
import dev.weft.contracts.Audio
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
import dev.weft.contracts.DecodedBarcode
import dev.weft.contracts.DeviceInfo
import dev.weft.contracts.DisplayInfo
import dev.weft.contracts.FileContent
import dev.weft.contracts.FileRef
import dev.weft.contracts.FileSaveSpec
import dev.weft.contracts.Files
import dev.weft.contracts.GeoResult
import dev.weft.contracts.HapticEffect
import dev.weft.contracts.Haptics
import dev.weft.contracts.ImageOps
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
import dev.weft.contracts.OcrBlock
import dev.weft.contracts.OcrResult
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.Pdf
import dev.weft.contracts.PdfRenderResult
import dev.weft.contracts.PdfTextResult
import dev.weft.contracts.Permission
import dev.weft.contracts.PermissionState
import dev.weft.contracts.Permissions
import dev.weft.contracts.Power
import dev.weft.contracts.PowerSource
import dev.weft.contracts.RectPx
import dev.weft.contracts.ScheduleFilter
import dev.weft.contracts.ScheduleSpec
import dev.weft.contracts.ScheduledNotification
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
import dev.weft.contracts.UserContext
import dev.weft.contracts.UserContextField
import dev.weft.contracts.Vision
import dev.weft.contracts.Volume
import dev.weft.contracts.VolumeStream
import dev.weft.contracts.Wifi
import dev.weft.contracts.WifiInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.atomicfu.atomic

/**
 * In-memory [OsCapabilities] with recording stubs for every sub-capability.
 *
 * Tests grab the inner fake they care about, set canned responses, and/or
 * assert call sequences:
 *
 * ```kotlin
 * val os = FakeOsCapabilities()
 * os.clipboard.write("hello")
 * assertThat(os.clipboard.calls).hasSize(1)
 *
 * os.location.nextFix = LocationFix(40.0, -74.0, ..., timestampEpochMs = 0)
 * val fix = os.location.current()  // returns the canned fix
 * ```
 *
 * Defaults are deliberately conservative:
 *   - Reads return null / empty list / false.
 *   - Writes succeed and record the call.
 *   - Long-running operations (record audio, capture image) return null
 *     (tests should set a fake result if they need success).
 *
 * Designed for **JVM unit tests** — no Android imports.
 */
public class FakeOsCapabilities(
    override val notifications: FakeNotifications = FakeNotifications(),
    override val calendar: FakeCalendar = FakeCalendar(),
    override val contacts: FakeContacts = FakeContacts(),
    override val files: FakeFiles = FakeFiles(),
    override val sharing: FakeSharing = FakeSharing(),
    override val intents: FakeIntents = FakeIntents(),
    override val keyVault: FakeKeyVault = FakeKeyVault(),
    override val userContext: FakeUserContext = FakeUserContext(),
    override val permissions: FakePermissions = FakePermissions(),
    override val clipboard: FakeClipboard = FakeClipboard(),
    override val biometrics: FakeBiometrics = FakeBiometrics(),
    override val haptics: FakeHaptics = FakeHaptics(),
    override val vision: FakeVision = FakeVision(),
    override val location: FakeLocation = FakeLocation(),
    override val speech: FakeSpeech = FakeSpeech(),
    override val audio: FakeAudio = FakeAudio(),
    override val camera: FakeCamera = FakeCamera(),
    override val systemInfo: FakeSystemInfo = FakeSystemInfo(),
    override val pdf: FakePdf = FakePdf(),
    override val bluetooth: FakeBluetooth = FakeBluetooth(),
    override val mediaLibrary: FakeMediaLibrary = FakeMediaLibrary(),
    override val apps: FakeApps = FakeApps(),
    override val sensors: FakeSensors = FakeSensors(),
    override val telephony: FakeTelephony = FakeTelephony(),
    override val wifi: FakeWifi = FakeWifi(),
    override val volume: FakeVolume = FakeVolume(),
    override val power: FakePower = FakePower(),
    override val settings: FakeSystemSettings = FakeSystemSettings(),
    override val shortcuts: FakeAppShortcuts = FakeAppShortcuts(),
    override val translation: FakeTranslation = FakeTranslation(),
    override val imageOps: FakeImageOps = FakeImageOps(),
    override val mediaPicker: FakeMediaPicker = FakeMediaPicker(),
) : OsCapabilities

// ----- Per-capability fakes ------------------------------------------------

public class FakeNotifications : Notifications {
    public val shown: MutableList<NotificationSpec> = mutableListOf()
    public val scheduled: MutableList<Pair<NotificationSpec, ScheduleSpec>> = mutableListOf()
    public val cancelled: MutableList<NotificationHandle> = mutableListOf()
    private val idGen = atomic(0L)

    override suspend fun showNow(spec: NotificationSpec): NotificationHandle {
        shown += spec
        return NotificationHandle("notif-${idGen.incrementAndGet()}")
    }
    override suspend fun schedule(spec: NotificationSpec, schedule: ScheduleSpec): NotificationHandle {
        scheduled += spec to schedule
        return NotificationHandle("notif-${idGen.incrementAndGet()}")
    }
    override suspend fun cancel(handle: NotificationHandle): Boolean {
        cancelled += handle
        return true
    }
    override suspend fun listScheduled(filter: ScheduleFilter?): List<ScheduledNotification> = emptyList()
}

public class FakeCalendar : Calendar {
    public var nextRead: List<CalendarEvent> = emptyList()
    public val created: MutableList<CalendarEvent> = mutableListOf()
    public val updated: MutableList<Pair<CalendarEventId, CalendarPatch>> = mutableListOf()
    public val deleted: MutableList<CalendarEventId> = mutableListOf()
    private val idGen = atomic(0L)

    override suspend fun read(filter: CalendarFilter): List<CalendarEvent> = nextRead
    override suspend fun create(event: CalendarEvent): CalendarEventId {
        created += event
        return CalendarEventId("evt-${idGen.incrementAndGet()}")
    }
    override suspend fun update(id: CalendarEventId, patch: CalendarPatch): Boolean {
        updated += id to patch
        return true
    }
    override suspend fun delete(id: CalendarEventId): Boolean {
        deleted += id
        return true
    }
}

public class FakeContacts : Contacts {
    public var nextRead: List<ContactSummary> = emptyList()
    override suspend fun read(filter: ContactFilter): List<ContactSummary> = nextRead
}

public class FakeFiles : Files {
    public val saved: MutableList<FileSaveSpec> = mutableListOf()
    public var nextRead: FileContent = FileContent(text = "", mimeType = "text/plain", sizeBytes = 0)
    public val shared: MutableList<String> = mutableListOf()

    override suspend fun save(spec: FileSaveSpec): FileRef {
        saved += spec
        return FileRef(uri = "fake://files/${saved.size}", sizeBytes = (spec.text?.length ?: 0).toLong())
    }
    override suspend fun read(uri: String, asBase64: Boolean): FileContent = nextRead
    override suspend fun share(uri: String, target: ShareTarget): Boolean {
        shared += uri
        return true
    }
}

public class FakeSharing : Sharing {
    public val shared: MutableList<ShareContent> = mutableListOf()
    override suspend fun share(content: ShareContent, target: ShareTarget): Boolean {
        shared += content
        return true
    }
}

public class FakeIntents : Intents {
    public val launchedApps: MutableList<String> = mutableListOf()
    public val openedUrls: MutableList<String> = mutableListOf()
    public val mapsDirections: MutableList<Triple<String, String?, String>> = mutableListOf()
    public val alarmsSet: MutableList<Triple<Int, Int, String?>> = mutableListOf()
    override suspend fun launchApp(target: String, payload: JsonObject?): Boolean {
        launchedApps += target
        return true
    }
    override suspend fun openUrl(url: String, inApp: Boolean): Boolean {
        openedUrls += url
        return true
    }
    override suspend fun openMapsDirections(to: String, from: String?, mode: String): Boolean {
        mapsDirections += Triple(to, from, mode)
        return true
    }
    override suspend fun openAlarmSet(hour: Int, minute: Int, label: String?): Boolean {
        alarmsSet += Triple(hour, minute, label)
        return true
    }
}

public class FakeKeyVault : KeyVault {
    private val secrets: MutableMap<String, String> = mutableMapOf()
    override suspend fun put(alias: String, secret: String) { secrets[alias] = secret }
    override suspend fun get(alias: String): String? = secrets[alias]
    override suspend fun remove(alias: String): Boolean = secrets.remove(alias) != null
    override suspend fun exists(alias: String): Boolean = alias in secrets
}

public class FakeUserContext : UserContext {
    public var snapshotBuilder: (Set<UserContextField>) -> JsonObject = { _ ->
        buildJsonObject {
            put("timeEpochMs", 0L)
            put("timezone", "UTC")
        }
    }
    override suspend fun snapshot(fields: Set<UserContextField>): JsonObject = snapshotBuilder(fields)
}

public class FakePermissions : Permissions {
    /** Per-permission canned state. Anything absent returns GRANTED. */
    public val states: MutableMap<Permission, PermissionState> = mutableMapOf()
    public val checked: MutableList<Permission> = mutableListOf()
    public val requested: MutableList<Permission> = mutableListOf()

    override suspend fun check(permission: Permission): PermissionState {
        checked += permission
        return states[permission] ?: PermissionState.GRANTED
    }
    override suspend fun request(permission: Permission): PermissionState {
        requested += permission
        return states[permission] ?: PermissionState.GRANTED
    }
}

public class FakeClipboard : Clipboard {
    public var contents: String? = null
    public val writes: MutableList<String> = mutableListOf()
    public var cleared: Int = 0

    override suspend fun read(): String? = contents
    override suspend fun write(text: String) { contents = text; writes += text }
    override suspend fun clear() { contents = null; cleared++ }
}

public class FakeBiometrics : Biometrics {
    public var nextResult: BiometricResult = BiometricResult.Authenticated
    public val prompts: MutableList<String> = mutableListOf()
    override suspend fun authenticate(reason: String): BiometricResult {
        prompts += reason
        return nextResult
    }
}

public class FakeHaptics : Haptics {
    public val played: MutableList<HapticEffect> = mutableListOf()
    override suspend fun perform(effect: HapticEffect) { played += effect }
}

public class FakeVision : Vision {
    public var nextOcr: OcrResult = OcrResult(text = "", blocks = emptyList())
    public var nextBarcodes: List<DecodedBarcode> = emptyList()
    public val ocrCalls: MutableList<String> = mutableListOf()
    public val barcodeCalls: MutableList<String> = mutableListOf()

    override suspend fun ocr(imageUri: String): OcrResult { ocrCalls += imageUri; return nextOcr }
    override suspend fun barcodes(imageUri: String): List<DecodedBarcode> {
        barcodeCalls += imageUri
        return nextBarcodes
    }
}

public class FakeLocation : Location {
    public var nextFix: LocationFix? = null
    public var nextGeocode: List<GeoResult> = emptyList()
    public var nextReverseGeocode: List<GeoResult> = emptyList()

    override suspend fun current(): LocationFix? = nextFix
    override suspend fun geocode(query: String, maxResults: Int): List<GeoResult> = nextGeocode
    override suspend fun reverseGeocode(latitude: Double, longitude: Double, maxResults: Int): List<GeoResult> =
        nextReverseGeocode
}

public class FakeSpeech : Speech {
    public val spoken: MutableList<String> = mutableListOf()
    public var nextSucceeds: Boolean = true
    /**
     * Canned recognition response. Null = "recognition failed / nothing
     * said", matching the real Android behavior when the engine returns
     * no matches.
     */
    public var nextRecognition: SpeechRecognitionResult? = null
    public val recognizeCalls: MutableList<Pair<String?, Long>> = mutableListOf()

    override suspend fun say(text: String, locale: String?): Boolean {
        spoken += text
        return nextSucceeds
    }
    override suspend fun stop() { /* no-op */ }
    override suspend fun recognize(locale: String?, maxDurationMs: Long): SpeechRecognitionResult? {
        recognizeCalls += locale to maxDurationMs
        return nextRecognition
    }
}

public class FakeAudio : Audio {
    /** When non-null, [record] returns this. Default null = "recording failed". */
    public var nextRecording: FileRef? = null
    public val recordCalls: MutableList<Pair<Long, String>> = mutableListOf()
    override suspend fun record(maxDurationMs: Long, namePrefix: String): FileRef? {
        recordCalls += maxDurationMs to namePrefix
        return nextRecording
    }
    override suspend fun stop() { /* no-op */ }
}

public class FakeCamera : Camera {
    public var nextCapture: FileRef? = null
    public val captureCalls: MutableList<String> = mutableListOf()
    override suspend fun captureImage(namePrefix: String): FileRef? {
        captureCalls += namePrefix
        return nextCapture
    }
}

public class FakeSystemInfo : SystemInfo {
    public var nextBattery: BatteryInfo = BatteryInfo(
        percent = null,
        charging = false,
        powerSource = PowerSource.UNKNOWN,
        saverMode = false,
    )
    public var nextNetwork: NetworkInfo = NetworkInfo(
        online = false,
        transport = NetworkTransport.NONE,
        metered = false,
    )
    public var nextDevice: DeviceInfo = DeviceInfo(
        manufacturer = "fake",
        model = "fake",
        osName = "fake",
        osVersion = "0",
        sdkInt = -1,
        locale = "en-US",
        timezone = "UTC",
        storageFreeBytes = 0L,
        storageTotalBytes = 0L,
    )

    public var nextDisplay: DisplayInfo = DisplayInfo(
        darkMode = false,
        widthPx = 1080,
        heightPx = 2400,
        density = 3f,
        refreshRateHz = 60f,
        brightness = null,
        screenOn = true,
    )

    override suspend fun battery(): BatteryInfo = nextBattery
    override suspend fun network(): NetworkInfo = nextNetwork
    override suspend fun device(): DeviceInfo = nextDevice
    override suspend fun display(): DisplayInfo = nextDisplay
}

public class FakePdf : Pdf {
    public var nextExtractText: PdfTextResult = PdfTextResult(
        text = "",
        pageCount = 0,
        extractedPages = emptyList(),
    )
    public var nextRenderPages: PdfRenderResult = PdfRenderResult(
        imageUris = emptyList(),
        pageCount = 0,
    )
    public val extractTextCalls: MutableList<Triple<String, String?, Int>> = mutableListOf()
    public val renderPagesCalls: MutableList<Triple<String, List<Int>?, Float>> = mutableListOf()
    public val createCalls: MutableList<Triple<String, String, String>> = mutableListOf()
    private val idGen = atomic(0L)

    override suspend fun extractText(uri: String, pageRange: String?, maxPages: Int): PdfTextResult {
        extractTextCalls += Triple(uri, pageRange, maxPages)
        return nextExtractText
    }
    override suspend fun renderPages(uri: String, pages: List<Int>?, scale: Float): PdfRenderResult {
        renderPagesCalls += Triple(uri, pages, scale)
        return nextRenderPages
    }
    override suspend fun create(title: String, body: String, fileName: String): FileRef {
        createCalls += Triple(title, body, fileName)
        return FileRef(uri = "fake://pdf/${idGen.incrementAndGet()}", sizeBytes = body.length.toLong())
    }
}

public class FakeBluetooth : Bluetooth {
    public var nextPaired: List<BluetoothDeviceInfo> = emptyList()
    public var settingsOpened: Int = 0
    /** Per-address canned battery level. Anything absent returns null. */
    public val deviceBatteries: MutableMap<String, Int> = mutableMapOf()
    public val batteryQueries: MutableList<String> = mutableListOf()

    override suspend fun listPaired(): List<BluetoothDeviceInfo> = nextPaired
    override suspend fun openSettings(): Boolean { settingsOpened++; return true }
    override suspend fun deviceBattery(address: String): Int? {
        batteryQueries += address
        return deviceBatteries[address]
    }
}

public class FakeMediaLibrary : MediaLibrary {
    /** Items returned by both [listRecent] and [query] (filter is ignored). */
    public var nextItems: List<MediaItem> = emptyList()
    public val queryCalls: MutableList<MediaFilter> = mutableListOf()
    override suspend fun listRecent(kinds: Set<MediaKind>, limit: Int): List<MediaItem> {
        queryCalls += MediaFilter(kinds = kinds, limit = limit)
        return nextItems.take(limit)
    }
    override suspend fun query(filter: MediaFilter): List<MediaItem> {
        queryCalls += filter
        return nextItems.take(filter.limit)
    }
}

public class FakeApps : Apps {
    /** Package names the fake reports as installed. */
    public val installedPackages: MutableSet<String> = mutableSetOf()
    public var nextLaunchable: List<AppInfo> = emptyList()
    public val installedQueries: MutableList<String> = mutableListOf()

    override suspend fun isInstalled(packageName: String): Boolean {
        installedQueries += packageName
        return packageName in installedPackages
    }
    override suspend fun listLaunchable(limit: Int): List<AppInfo> = nextLaunchable.take(limit)
}

public class FakeSensors : Sensors {
    /** Null = sensor unavailable (the realistic default for older devices). */
    public var nextSteps: Int? = null
    public var nextLightLux: Float? = null

    override suspend fun stepsToday(): Int? = nextSteps
    override suspend fun ambientLightLux(): Float? = nextLightLux
}

public class FakeTelephony : Telephony {
    public val dialCalls: MutableList<String> = mutableListOf()
    public val smsCalls: MutableList<Pair<String, String?>> = mutableListOf()
    public var nextInfo: TelephonyInfo = TelephonyInfo()
    public var dialSucceeds: Boolean = true
    public var smsSucceeds: Boolean = true

    override suspend fun dial(phoneNumber: String): Boolean {
        dialCalls += phoneNumber
        return dialSucceeds
    }
    override suspend fun composeSms(phoneNumber: String, body: String?): Boolean {
        smsCalls += phoneNumber to body
        return smsSucceeds
    }
    override suspend fun info(): TelephonyInfo = nextInfo
}

public class FakeWifi : Wifi {
    public var nextInfo: WifiInfo = WifiInfo(enabled = false, connected = false)
    override suspend fun info(): WifiInfo = nextInfo
}

public class FakeVolume : Volume {
    public val streams: MutableMap<VolumeStream, Float> = mutableMapOf()
    public val setCalls: MutableList<Pair<VolumeStream, Float>> = mutableListOf()
    public var setSucceeds: Boolean = true

    override suspend fun get(stream: VolumeStream): Float = streams[stream] ?: 0.5f
    override suspend fun set(stream: VolumeStream, normalized: Float): Boolean {
        setCalls += stream to normalized
        if (setSucceeds) streams[stream] = normalized.coerceIn(0f, 1f)
        return setSucceeds
    }
}

public class FakePower : Power {
    public val keepScreenOnCalls: MutableList<Boolean> = mutableListOf()
    public val brightnessCalls: MutableList<Float> = mutableListOf()
    public var succeeds: Boolean = true

    override suspend fun keepScreenOn(enabled: Boolean): Boolean {
        keepScreenOnCalls += enabled
        return succeeds
    }
    override suspend fun setBrightness(normalized: Float): Boolean {
        brightnessCalls += normalized
        return succeeds
    }
}

public class FakeSystemSettings : SystemSettings {
    public val opened: MutableList<SettingsPanel> = mutableListOf()
    public var succeeds: Boolean = true

    override suspend fun open(panel: SettingsPanel): Boolean {
        opened += panel
        return succeeds
    }
}

public class FakeAppShortcuts : AppShortcuts {
    public val shortcuts: MutableMap<String, ShortcutSpec> = mutableMapOf()
    public var succeeds: Boolean = true

    override suspend fun push(spec: ShortcutSpec): Boolean {
        if (succeeds) shortcuts[spec.id] = spec
        return succeeds
    }
    override suspend fun remove(id: String): Boolean {
        if (succeeds) shortcuts.remove(id)
        return succeeds
    }
    override suspend fun list(): List<ShortcutSpec> = shortcuts.values.toList()
}

public class FakeTranslation : Translation {
    /** Canned translation. Null = "model unavailable" or empty input. */
    public var nextTranslation: String? = null
    public var nextDetectedLanguage: String = "und"
    public var nextSupportedLanguages: List<String> = listOf("en", "es", "fr", "ja", "zh", "de")
    public val translateCalls: MutableList<Triple<String, String, String?>> = mutableListOf()
    public val detectCalls: MutableList<String> = mutableListOf()

    override suspend fun translate(text: String, target: String, source: String?): String? {
        translateCalls += Triple(text, target, source)
        return nextTranslation
    }
    override suspend fun detectLanguage(text: String): String {
        detectCalls += text
        return nextDetectedLanguage
    }
    override suspend fun supportedLanguages(): List<String> = nextSupportedLanguages
}

public class FakeImageOps : ImageOps {
    public var nextResult: FileRef? = null
    public val resizeCalls: MutableList<Triple<String, Int, String>> = mutableListOf()
    public val cropCalls: MutableList<Triple<String, RectPx, String>> = mutableListOf()
    public val rotateCalls: MutableList<Triple<String, Int, String>> = mutableListOf()

    override suspend fun resize(uri: String, maxEdgePx: Int, namePrefix: String): FileRef? {
        resizeCalls += Triple(uri, maxEdgePx, namePrefix)
        return nextResult
    }
    override suspend fun crop(uri: String, rect: RectPx, namePrefix: String): FileRef? {
        cropCalls += Triple(uri, rect, namePrefix)
        return nextResult
    }
    override suspend fun rotate(uri: String, degrees: Int, namePrefix: String): FileRef? {
        rotateCalls += Triple(uri, degrees, namePrefix)
        return nextResult
    }
}

public class FakeMediaPicker : MediaPicker {
    /** URIs returned by the picker. Empty list = user cancelled. */
    public var nextResult: List<String> = emptyList()
    public val pickCalls: MutableList<Pair<MediaPickerKind, Int>> = mutableListOf()

    override suspend fun pick(kind: MediaPickerKind, maxItems: Int): List<String> {
        pickCalls += kind to maxItems
        return nextResult
    }
}

// Re-export common contract types so tests need just one import path.
public typealias FakeOcrBlock = OcrBlock
