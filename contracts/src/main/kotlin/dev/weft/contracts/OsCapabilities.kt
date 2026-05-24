package dev.weft.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The umbrella of platform-touching capabilities scripts can invoke.
 *
 * Lives in :contracts (Layer 1) so :scripts:core, :core, and ScriptContext
 * can reference these interfaces without depending on :os-bridge (Layer 2).
 * Concrete platform implementations of each sub-interface live in :os-bridge
 * (Android) and, in a later phase, an iOS source set.
 *
 * The substrate's policy: scripts NEVER call platform APIs directly; they go
 * through this interface, which is mockable in tests and swappable per platform.
 */
interface OsCapabilities {
    val notifications: Notifications
    val calendar: Calendar
    val contacts: Contacts
    val files: Files
    val sharing: Sharing
    val intents: Intents
    val keyVault: KeyVault
    val userContext: UserContext
    val permissions: Permissions
    val clipboard: Clipboard
    val biometrics: Biometrics
    val haptics: Haptics
    val vision: Vision
    val location: Location
    val speech: Speech
    val audio: Audio
    val camera: Camera
    val systemInfo: SystemInfo
    val pdf: Pdf
    val bluetooth: Bluetooth
}

/** Permission grant/check. Backed by Android runtime permissions or iOS authorization APIs. */
interface Permissions {
    /** Current state for a permission. Does NOT prompt. */
    suspend fun check(permission: Permission): PermissionState

    /** Show the system permission prompt. Returns the resulting state. */
    suspend fun request(permission: Permission): PermissionState
}

@Serializable
enum class PermissionState {
    GRANTED,
    DENIED,
    DENIED_FOREVER,
    NOT_DETERMINED,
}

interface Notifications {
    /** Show a notification now (or as immediate as the platform allows). */
    suspend fun showNow(spec: NotificationSpec): NotificationHandle

    /** Schedule a notification for later. Returns a handle for cancellation. */
    suspend fun schedule(spec: NotificationSpec, schedule: ScheduleSpec): NotificationHandle

    suspend fun cancel(handle: NotificationHandle): Boolean

    /** List active scheduled notifications matching the given filter. */
    suspend fun listScheduled(filter: ScheduleFilter? = null): List<ScheduledNotification>
}

@Serializable
data class NotificationSpec(
    val title: String,
    val body: String? = null,
    /** Optional tap action — a tool to invoke, with params. */
    val tapAction: TapAction? = null,
    val channelId: String? = null,
)

@Serializable
data class TapAction(val tool: String, val params: JsonObject = JsonObject(emptyMap()))

@Serializable
data class NotificationHandle(val id: String)

@Serializable
data class ScheduleSpec(
    /** ISO-8601 instant for one-shot, or RRULE-like spec for recurring. */
    val expr: String,
    val timezone: String? = null,
)

@Serializable
data class ScheduleFilter(
    val kind: String? = null,
    val beforeIso: String? = null,
    val afterIso: String? = null,
)

@Serializable
data class ScheduledNotification(
    val handle: NotificationHandle,
    val spec: NotificationSpec,
    val nextRunIso: String,
)

interface Calendar {
    suspend fun read(filter: CalendarFilter): List<CalendarEvent>
    suspend fun create(event: CalendarEvent): CalendarEventId

    /**
     * Update an existing event identified by [id]. Only non-null fields on
     * [patch] are applied — pass `CalendarPatch(title = "...")` to rename
     * an event without touching its times. Returns true if the row was
     * updated, false if the id didn't match anything.
     */
    suspend fun update(id: CalendarEventId, patch: CalendarPatch): Boolean

    /** Delete an event by id. Returns true on success, false if not found. */
    suspend fun delete(id: CalendarEventId): Boolean
}

/**
 * Partial-update payload for [Calendar.update]. Null fields are not touched
 * on the underlying row; non-null fields overwrite. Use an empty string
 * (`""`) to clear a free-text field like [location] or [notes].
 */
@Serializable
data class CalendarPatch(
    val title: String? = null,
    val startIso: String? = null,
    val endIso: String? = null,
    val location: String? = null,
    val notes: String? = null,
)

@Serializable
data class CalendarFilter(
    val startIso: String,
    val endIso: String,
    val calendarIds: List<String>? = null,
    val search: String? = null,
)

@Serializable
data class CalendarEvent(
    val id: CalendarEventId? = null,
    val title: String,
    val startIso: String,
    val endIso: String,
    val location: String? = null,
    val notes: String? = null,
    val calendarId: String? = null,
)

@Serializable
data class CalendarEventId(val value: String)

interface Contacts {
    suspend fun read(filter: ContactFilter): List<ContactSummary>
}

@Serializable
data class ContactFilter(
    val nameContains: String? = null,
    val hasEmail: Boolean? = null,
    val hasPhone: Boolean? = null,
    val limit: Int = LIMIT_DEFAULT,
) {
    companion object { const val LIMIT_DEFAULT = 50 }
}

@Serializable
data class ContactSummary(
    val id: String,
    val displayName: String,
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
)

interface Files {
    suspend fun save(spec: FileSaveSpec): FileRef
    suspend fun read(uri: String, asBase64: Boolean = false): FileContent
    suspend fun share(uri: String, target: ShareTarget = ShareTarget.SystemSheet): Boolean
}

@Serializable
data class FileSaveSpec(
    /** Either text or base64-encoded bytes. */
    val contentBase64: String? = null,
    val text: String? = null,
    val mimeType: String,
    val name: String? = null,
    val directory: String? = null,
)

@Serializable
data class FileRef(val uri: String, val sizeBytes: Long)

@Serializable
data class FileContent(val text: String? = null, val base64: String? = null, val mimeType: String, val sizeBytes: Long)

@Serializable
sealed class ShareTarget {
    @Serializable object SystemSheet : ShareTarget()
    @Serializable data class SpecificApp(val appId: String) : ShareTarget()
}

interface Sharing {
    suspend fun share(content: ShareContent, target: ShareTarget = ShareTarget.SystemSheet): Boolean
}

@Serializable
data class ShareContent(
    val text: String? = null,
    val url: String? = null,
    val fileUri: String? = null,
)

interface Intents {
    /** Launch another installed app with an optional payload. */
    suspend fun launchApp(target: String, payload: JsonObject? = null): Boolean

    /** Open a URL (in the system browser, or in-app webview). */
    suspend fun openUrl(url: String, inApp: Boolean = false): Boolean

    /**
     * Open the user's preferred maps app with directions to [to]. [from]
     * defaults to "current location" (i.e. no origin parameter — the
     * maps app uses GPS). [mode] is "driving" (default) / "walking" /
     * "transit" / "bicycling". Returns false if no maps handler is
     * installed.
     */
    suspend fun openMapsDirections(
        to: String,
        from: String? = null,
        mode: String = "driving",
    ): Boolean

    /**
     * Hand off to the user's clock app to create a new alarm. The user
     * confirms inside that app — we return success based on whether the
     * intent launched cleanly, NOT whether the alarm was actually set.
     * [label] is optional; if null, the clock app uses its own default.
     */
    suspend fun openAlarmSet(hour: Int, minute: Int, label: String? = null): Boolean
}

interface KeyVault {
    /** Store a secret under the given alias. Backed by Android Keystore / iOS Keychain. */
    suspend fun put(alias: String, secret: String)
    suspend fun get(alias: String): String?
    suspend fun remove(alias: String): Boolean
    suspend fun exists(alias: String): Boolean
}

interface UserContext {
    suspend fun snapshot(fields: Set<UserContextField>): JsonObject

    @Serializable
    enum class Field { LOCATION, TIME, TIMEZONE, LOCALE, BATTERY, NETWORK, DEVICE_CLASS }
}

@Serializable
enum class UserContextField {
    LOCATION,
    TIME,
    TIMEZONE,
    LOCALE,
    BATTERY,
    NETWORK,
    DEVICE_CLASS,
}

/** Convenience used by some scripts to express "any of these fields". */
typealias UserContextSnapshot = JsonElement

/**
 * System clipboard read/write. Backed by [android.content.ClipboardManager]
 * on Android. Reads on API 29+ trigger a system "Pasted from <app>" toast —
 * apps should treat clipboard reads as user-observable, not silent.
 *
 * Non-text clip data (images, URIs) is collapsed to its coerced-to-text
 * representation. Callers that need rich clipboard contents should access
 * the platform API directly.
 */
interface Clipboard {
    /** Current primary clip text, or null if the clipboard is empty / non-text. */
    suspend fun read(): String?

    /** Replace the primary clip with [text]. */
    suspend fun write(text: String)

    /** Empty the primary clip. No-op if already empty. */
    suspend fun clear()
}

/**
 * Biometric authentication — fingerprint, face, or device credential.
 * Backed by [androidx.biometric.BiometricPrompt] on Android.
 *
 * The implementation needs a foreground `FragmentActivity` to attach the
 * prompt UI to. The Android impl tracks it automatically via
 * `Application.registerActivityLifecycleCallbacks`; apps don't have to
 * pass an Activity through.
 *
 * Typical use: gate destructive skills (`/wipe-memories`), confirm
 * payments, unlock a secrets vault before reading from [KeyVault].
 */
interface Biometrics {
    /**
     * Show the biometric prompt with [reason] as the subtitle. Suspends
     * until the user authenticates, cancels, or the prompt fails.
     */
    suspend fun authenticate(reason: String): BiometricResult
}

@Serializable
sealed class BiometricResult {
    /** User completed biometric auth successfully. */
    @Serializable
    object Authenticated : BiometricResult()

    /** User dismissed the prompt without authenticating. */
    @Serializable
    object UserCancelled : BiometricResult()

    /**
     * Device has no biometrics enrolled or no compatible hardware. The
     * agent should fall back to a non-biometric flow (PIN, password,
     * just-confirm dialog).
     */
    @Serializable
    object NotAvailable : BiometricResult()

    /** Too many failed attempts — the sensor is temporarily locked. */
    @Serializable
    object LockedOut : BiometricResult()

    /** Catch-all failure. [message] is platform-supplied (sometimes empty). */
    @Serializable
    data class Failed(val message: String) : BiometricResult()
}

/**
 * Haptic feedback — short, pre-defined vibration effects. Backed by
 * [android.os.Vibrator] (with [android.os.VibrationEffect.Composition] on
 * API 30+ for richer effects, falling back to simple patterns earlier).
 *
 * Independent of [Notifications]: notifications can vibrate, but apps
 * also want a single tactile "tap registered" cue without firing a
 * notification. Tools and rendered components both call into this.
 */
interface Haptics {
    /** Play the given effect. No-op if the device has no vibrator. */
    suspend fun perform(effect: HapticEffect)
}

@Serializable
enum class HapticEffect {
    /** Tiny click — UI confirmation. */
    TICK,

    /** Standard click — button press. */
    CLICK,

    /** Heavier click — important confirmation. */
    HEAVY_CLICK,

    /** Two quick taps — notification-like. */
    DOUBLE_CLICK,

    /** Two ascending taps — positive outcome. */
    SUCCESS,

    /** Long single buzz — soft warning. */
    WARNING,

    /** Long, sharper buzz — error / destructive action. */
    ERROR,
}

/**
 * On-device vision — OCR and barcode/QR decoding. Backed by Google's ML
 * Kit on Android (text recognition + barcode scanning, both free + offline).
 *
 * Operates on file URIs already saved through [Files] or returned from
 * the system camera. The capability deliberately doesn't open the camera
 * itself — that's a separate concern handled by a future Camera capability.
 */
interface Vision {
    /**
     * Extract text from the image at [imageUri]. Returns the recognized
     * text plus per-block bounding boxes so the agent can describe layout
     * ("the top of the receipt says…").
     */
    suspend fun ocr(imageUri: String): OcrResult

    /**
     * Decode any barcodes (QR, EAN, UPC, Code 128, etc.) in the image at
     * [imageUri]. Returns an empty list when nothing is detected.
     */
    suspend fun barcodes(imageUri: String): List<DecodedBarcode>
}

@Serializable
data class OcrResult(
    /** Concatenation of all recognized text blocks, in reading order. */
    val text: String,
    /** Per-block structured output for callers that need bounds. */
    val blocks: List<OcrBlock> = emptyList(),
)

@Serializable
data class OcrBlock(
    val text: String,
    /** Tight bounding box around the block, image-pixel coordinates. */
    val boundsPx: RectPx? = null,
)

@Serializable
data class RectPx(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class DecodedBarcode(
    /** The decoded payload (URL, plain text, vCard, EAN string, …). */
    val rawValue: String,
    /** Format token, e.g. "QR_CODE", "EAN_13", "CODE_128". */
    val format: String,
    val boundsPx: RectPx? = null,
)

/**
 * Location capability — one-shot fixes + geocoding in both directions.
 * Backed by FusedLocationProvider + the platform Geocoder on Android.
 *
 * Requires [Permission.LOCATION] before [current] will return non-null.
 * Geocoding methods don't need location permission but do need network on
 * older devices (Android 13+ has an on-device Geocoder).
 */
interface Location {
    /**
     * Best-available location fix, or null when permission isn't granted,
     * location services are off, or no fix is available within a short
     * timeout. Implementations should not block longer than ~5 seconds.
     */
    suspend fun current(): LocationFix?

    /**
     * Forward geocoding: "1600 Amphitheatre Parkway" → list of candidate
     * addresses with lat/lng. Empty when no matches or network unavailable.
     */
    suspend fun geocode(query: String, maxResults: Int = 5): List<GeoResult>

    /** Reverse geocoding: (lat, lng) → list of candidate human-readable addresses. */
    suspend fun reverseGeocode(latitude: Double, longitude: Double, maxResults: Int = 1): List<GeoResult>
}

@Serializable
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val timestampEpochMs: Long,
)

@Serializable
data class GeoResult(
    val latitude: Double,
    val longitude: Double,
    /** Full address line ("1600 Amphitheatre Parkway, Mountain View, CA 94043, USA"). */
    val addressLine: String? = null,
    val locality: String? = null,
    val region: String? = null,
    val country: String? = null,
    val postalCode: String? = null,
)

/**
 * Text-to-speech. Backed by [android.speech.tts.TextToSpeech] on Android.
 *
 * Use for read-aloud, accessibility, and hands-free flows. Engine init is
 * async — the first call may suspend until the platform TTS service is
 * ready (typically <500ms). Subsequent calls are fast.
 */
interface Speech {
    /**
     * Speak [text] through the device's default audio output. Suspends
     * until utterance completes (or the engine reports failure). Returns
     * true on success, false if TTS isn't available / failed to speak.
     */
    suspend fun say(text: String, locale: String? = null): Boolean

    /** Interrupt any in-flight utterance and clear the queue. */
    suspend fun stop()
}

/**
 * Audio capture — fixed-duration recording to a file. Backed by
 * [android.media.MediaRecorder] on Android.
 *
 * Requires [Permission.MICROPHONE]. Encodes to AAC inside an MP4 container
 * by default — broadly supported, small files, decodable by every modern
 * STT service.
 *
 * Output files live in the app's private cache directory and are exposed
 * via the substrate's FileProvider so callers can share them through
 * [Sharing.share]. Apps that want long-term storage should copy the file
 * out of cache before the OS evicts it.
 */
interface Audio {
    /**
     * Record audio for up to [maxDurationMs] milliseconds (or until [stop]
     * is called). Returns the file ref on success, null when permission
     * is missing or recording failed. The file is named with [namePrefix]
     * + a timestamp to keep concurrent recordings from clobbering each other.
     */
    suspend fun record(maxDurationMs: Long, namePrefix: String = "recording"): FileRef?

    /** Stop the in-flight recording early. No-op if nothing is recording. */
    suspend fun stop()
}

/**
 * Camera capture — launches the system camera UI, waits for the user to
 * snap a photo, returns the URI of the resulting image. Backed by
 * `ACTION_IMAGE_CAPTURE` + an `ActivityResultRegistry`-registered launcher
 * on Android.
 *
 * Deliberately does NOT require `CAMERA` runtime permission — the user
 * grants the system camera app permission to capture, and the substrate
 * receives the resulting file via FileProvider. Apps that want to use
 * CameraX / Camera2 directly (instead of delegating to the system camera)
 * can implement this interface against those APIs and request CAMERA
 * themselves.
 *
 * Files are saved to the app's private cache directory and exposed via
 * the substrate's FileProvider. Callers should copy them out before the
 * OS evicts cache.
 *
 * **Suspends across user UI.** Each call yields to the camera app and
 * resumes when the user shoots-or-cancels. The agent loop should expect
 * up to tens of seconds of latency — this is one of the few tools where
 * "wall-clock time" includes a human-in-the-loop step.
 */
interface Camera {
    /**
     * Open the system camera. Returns the URI of the captured image on
     * success, or null if the user cancelled, the device has no camera,
     * or no foreground activity is available.
     */
    suspend fun captureImage(namePrefix: String = "photo"): FileRef?
}

/**
 * Read-only device-state readers. Cheap to call (no I/O, no network),
 * no runtime permissions. Used by the `battery_status`, `network_status`,
 * and `device_info` tools so the agent can reason about its environment
 * without making the user opt into anything.
 *
 * Implementations should be defensive — return `null`/`UNKNOWN`/empty
 * shapes when a value can't be read, never throw. The agent will
 * receive whatever data is available and skip the rest.
 */
interface SystemInfo {
    suspend fun battery(): BatteryInfo
    suspend fun network(): NetworkInfo
    suspend fun device(): DeviceInfo
}

@Serializable
data class BatteryInfo(
    /** 0–100, or null when the platform can't report it. */
    val percent: Int?,
    /** Currently drawing power from the battery (true = on battery). */
    val charging: Boolean,
    val powerSource: PowerSource,
    /** Whether OS battery-saver mode is active. */
    val saverMode: Boolean,
)

@Serializable
enum class PowerSource { NONE, USB, AC, WIRELESS, DOCK, UNKNOWN }

@Serializable
data class NetworkInfo(
    /** Any usable transport currently attached. */
    val online: Boolean,
    val transport: NetworkTransport,
    /** True for cellular or otherwise metered transports. */
    val metered: Boolean,
)

@Serializable
enum class NetworkTransport { WIFI, CELLULAR, ETHERNET, BLUETOOTH, VPN, NONE }

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    /** "Android", "iOS", etc. */
    val osName: String,
    /** Release version, e.g. "14". */
    val osVersion: String,
    /** API level on Android; absent / -1 elsewhere. */
    val sdkInt: Int,
    /** IETF BCP-47, e.g. "en-US". */
    val locale: String,
    /** IANA, e.g. "America/Los_Angeles". */
    val timezone: String,
    /** Bytes free on the primary internal storage. */
    val storageFreeBytes: Long,
    /** Total bytes of the primary internal storage. */
    val storageTotalBytes: Long,
)

/**
 * PDF read / render / create. Backed by PdfBox-Android (text extraction
 * + creation) and the platform's built-in PdfRenderer (page → bitmap).
 *
 * URIs are content://, file://, or any scheme [Files.read] can resolve.
 * Returns null / empty shapes on failure rather than throwing —
 * tools wrap the call and surface human-readable error messages to
 * the model.
 */
interface Pdf {
    /**
     * Extract plain text from a PDF. Honors [pageRange] when set
     * (1-indexed, e.g. "1-3,5,8-10"); otherwise extracts up to
     * [maxPages] pages from the start of the document.
     */
    suspend fun extractText(
        uri: String,
        pageRange: String? = null,
        maxPages: Int = 50,
    ): PdfTextResult

    /**
     * Render specific pages of a PDF as PNG bitmaps and save them
     * into the app's cache directory. [pages] is 1-indexed; null means
     * "all pages". [scale] = 1.0 is roughly 72 DPI.
     */
    suspend fun renderPages(
        uri: String,
        pages: List<Int>? = null,
        scale: Float = 1.5f,
    ): PdfRenderResult

    /**
     * Create a new PDF from a text body. Lays out plain text with
     * automatic paragraph wrapping + page breaks. Returns a [FileRef]
     * pointing at the saved file.
     */
    suspend fun create(
        title: String,
        body: String,
        fileName: String = "document.pdf",
    ): FileRef
}

@Serializable
data class PdfTextResult(
    /** Concatenated text from the extracted pages, separated by `\n\n`. */
    val text: String,
    /** Total page count of the source PDF (regardless of [extractedPages]). */
    val pageCount: Int,
    /** The 1-indexed pages actually included in [text]. */
    val extractedPages: List<Int>,
    /** Set to a human-readable string when extraction failed; otherwise null. */
    val error: String? = null,
)

@Serializable
data class PdfRenderResult(
    /** URIs of the rendered PNG files in the cache dir. Same length as the requested pages. */
    val imageUris: List<String>,
    val pageCount: Int,
    val error: String? = null,
)

/**
 * Limited Bluetooth surface. Android 12+ locks down most BT operations
 * behind runtime permissions and system-app gates; this interface only
 * exposes the read-only / settings-handoff bits that are realistically
 * usable from a non-system app.
 *
 * Notably absent: scanning, connecting, pairing, file transfer, toggle.
 * Use [openSettings] to hand the user off to the Bluetooth panel for
 * anything not covered here.
 */
interface Bluetooth {
    /**
     * List the user's currently-paired Bluetooth devices. Requires
     * [Permission.BLUETOOTH_CONNECT] on Android 12+. Returns empty
     * when Bluetooth is off or no devices are paired.
     */
    suspend fun listPaired(): List<BluetoothDeviceInfo>

    /**
     * Open the system Bluetooth settings panel. Returns false if no
     * settings activity exists (extremely rare).
     */
    suspend fun openSettings(): Boolean

    /**
     * Best-effort battery level for a paired device. Returns null when
     * the device doesn't expose battery, when permission is denied, or
     * when the device isn't currently connected. Implementation reads
     * `BluetoothDevice.METADATA_MAIN_BATTERY` (API 29+) which works for
     * most modern headphones / speakers but not all.
     */
    suspend fun deviceBattery(address: String): Int?
}

@Serializable
data class BluetoothDeviceInfo(
    /** MAC address — stable id; pass to [Bluetooth.deviceBattery]. */
    val address: String,
    val name: String?,
    val type: BluetoothDeviceType,
    /** Bluetooth Class of Device, useful for icons (heuristic, not authoritative). */
    val deviceClass: String? = null,
    /** True if currently connected at the moment of the listPaired call. */
    val connected: Boolean = false,
)

@Serializable
enum class BluetoothDeviceType { CLASSIC, LE, DUAL, UNKNOWN }
