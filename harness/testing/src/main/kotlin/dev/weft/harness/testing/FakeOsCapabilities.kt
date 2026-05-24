package dev.weft.harness.testing

import dev.weft.contracts.Audio
import dev.weft.contracts.BiometricResult
import dev.weft.contracts.Biometrics
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
import dev.weft.contracts.FileContent
import dev.weft.contracts.FileRef
import dev.weft.contracts.FileSaveSpec
import dev.weft.contracts.Files
import dev.weft.contracts.GeoResult
import dev.weft.contracts.HapticEffect
import dev.weft.contracts.Haptics
import dev.weft.contracts.Intents
import dev.weft.contracts.KeyVault
import dev.weft.contracts.Location
import dev.weft.contracts.LocationFix
import dev.weft.contracts.NotificationHandle
import dev.weft.contracts.NotificationSpec
import dev.weft.contracts.Notifications
import dev.weft.contracts.OcrBlock
import dev.weft.contracts.OcrResult
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.Permission
import dev.weft.contracts.PermissionState
import dev.weft.contracts.Permissions
import dev.weft.contracts.ScheduleFilter
import dev.weft.contracts.ScheduleSpec
import dev.weft.contracts.ScheduledNotification
import dev.weft.contracts.ShareContent
import dev.weft.contracts.ShareTarget
import dev.weft.contracts.Sharing
import dev.weft.contracts.Speech
import dev.weft.contracts.UserContext
import dev.weft.contracts.UserContextField
import dev.weft.contracts.Vision
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

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
) : OsCapabilities

// ----- Per-capability fakes ------------------------------------------------

public class FakeNotifications : Notifications {
    public val shown: MutableList<NotificationSpec> = mutableListOf()
    public val scheduled: MutableList<Pair<NotificationSpec, ScheduleSpec>> = mutableListOf()
    public val cancelled: MutableList<NotificationHandle> = mutableListOf()
    private val idGen = AtomicLong(0)

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
    private val idGen = AtomicLong(0)

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
    override suspend fun launchApp(target: String, payload: JsonObject?): Boolean {
        launchedApps += target
        return true
    }
    override suspend fun openUrl(url: String, inApp: Boolean): Boolean {
        openedUrls += url
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
    override suspend fun say(text: String, locale: String?): Boolean {
        spoken += text
        return nextSucceeds
    }
    override suspend fun stop() { /* no-op */ }
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

// Re-export common contract types so tests need just one import path.
public typealias FakeOcrBlock = OcrBlock
