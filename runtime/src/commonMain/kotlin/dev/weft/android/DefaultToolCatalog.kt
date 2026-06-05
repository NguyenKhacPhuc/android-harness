package dev.weft.android

import dev.weft.contracts.ContextRegistry
import dev.weft.contracts.DataSourceRegistry
import dev.weft.harness.memory.MemoryCompactTool
import dev.weft.harness.memory.MemoryRecallTool
import dev.weft.harness.memory.MemoryStore
import dev.weft.harness.memory.MemoryStoreTool
import dev.weft.tools.AlarmSetTool
import dev.weft.tools.AppInstalledTool
import dev.weft.tools.AppListLaunchableTool
import dev.weft.tools.AudioRecordTool
import dev.weft.tools.BatteryStatusTool
import dev.weft.tools.BiometricAuthenticateTool
import dev.weft.tools.BluetoothDeviceBatteryTool
import dev.weft.tools.BluetoothListPairedTool
import dev.weft.tools.BluetoothOpenSettingsTool
import dev.weft.tools.CalendarCreateTool
import dev.weft.tools.CalendarDeleteTool
import dev.weft.tools.CalendarReadTool
import dev.weft.tools.CalendarUpdateTool
import dev.weft.tools.CameraCaptureTool
import dev.weft.tools.ClipboardReadTool
import dev.weft.tools.ClipboardWriteTool
import dev.weft.tools.ColorConvertTool
import dev.weft.tools.ContactsReadTool
import dev.weft.tools.DataDeleteTool
import dev.weft.tools.DataQueryTool
import dev.weft.tools.DataUpsertTool
import dev.weft.tools.DateComputeTool
import dev.weft.tools.DetectLanguageTool
import dev.weft.tools.DeviceInfoTool
import dev.weft.tools.DisplayInfoTool
import dev.weft.tools.ExternalLaunchAppTool
import dev.weft.tools.ExternalOpenUrlTool
import dev.weft.tools.ExternalShareTool
import dev.weft.tools.FilesReadTool
import dev.weft.tools.FilesSaveTool
import dev.weft.tools.FilesShareTool
import dev.weft.tools.HapticsTool
import dev.weft.tools.HashTool
import dev.weft.tools.ImageCropTool
import dev.weft.tools.ImageResizeTool
import dev.weft.tools.ImageRotateTool
import dev.weft.tools.JsonQueryTool
import dev.weft.tools.LocationCurrentTool
import dev.weft.tools.LocationGeocodeTool
import dev.weft.tools.LocationReverseGeocodeTool
import dev.weft.tools.MapsDirectionsTool
import dev.weft.tools.MathEvalTool
import dev.weft.tools.MediaListRecentTool
import dev.weft.tools.MediaPickAnyTool
import dev.weft.tools.MediaPickImageTool
import dev.weft.tools.MediaPickVideoTool
import dev.weft.tools.MediaQueryTool
import dev.weft.tools.NetworkFetchTool
import dev.weft.tools.NetworkStatusTool
import dev.weft.tools.NotifyShowTool
import dev.weft.tools.PdfCreateTool
import dev.weft.tools.PdfReadTool
import dev.weft.tools.PdfRenderPagesTool
import dev.weft.tools.PhoneDialTool
import dev.weft.tools.PowerKeepScreenOnTool
import dev.weft.tools.PowerSetBrightnessTool
import dev.weft.tools.RandomChoiceTool
import dev.weft.tools.RegexMatchTool
import dev.weft.tools.ScheduleCancelTool
import dev.weft.tools.ScheduleCreateTool
import dev.weft.tools.ScheduleListTool
import dev.weft.tools.SensorAmbientLightTool
import dev.weft.tools.SensorStepsTodayTool
import dev.weft.tools.SettingsOpenTool
import dev.weft.tools.ShortcutListTool
import dev.weft.tools.ShortcutPushTool
import dev.weft.tools.ShortcutRemoveTool
import dev.weft.tools.SmsComposeTool
import dev.weft.tools.SpeechRecognizeTool
import dev.weft.tools.SpeechSayTool
import dev.weft.tools.SystemUserContextTool
import dev.weft.tools.TelephonyInfoTool
import dev.weft.tools.TextTransformTool
import dev.weft.tools.TranslateTextTool
import dev.weft.tools.UiAskTool
import dev.weft.tools.UiDialogTool
import dev.weft.tools.UiNavigateTool
import dev.weft.tools.UiNotifyTool
import dev.weft.tools.UiRenderTool
import dev.weft.tools.UiRequestPermissionTool
import dev.weft.tools.UrlParseTool
import dev.weft.tools.VisionBarcodeTool
import dev.weft.tools.VisionOcrTool
import dev.weft.tools.VolumeGetTool
import dev.weft.tools.VolumeSetTool
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import dev.weft.tools.WifiInfoTool
import io.ktor.client.HttpClient

/**
 * The substrate's built-in tool catalog — every platform-neutral
 * [WeftTool] the SDK ships, in the order the LLM sees them. Extracted
 * from [WeftRuntime] so the composition root stays a wiring shell rather
 * than carrying a ~120-line literal (and its ~90 imports).
 *
 * Ordering matters for prompt caching: the stable substrate prelude
 * comes first so app tools (appended by the caller) don't shift the
 * cached prefix. Every tool here talks through abstract contracts
 * ([dev.weft.contracts.OsCapabilities], [dev.weft.contracts.UiBridge],
 * [dev.weft.contracts.DataSource], [MemoryStore]), so the SDK stays
 * agnostic to the host's UI framework and platform.
 *
 * `find_tool` is intentionally NOT here — it depends on the runtime's
 * [dev.weft.contracts.ToolProvider], which is built *from* this list.
 * [WeftRuntime] appends it when the provider advertises on-demand tools.
 */
internal fun defaultToolCatalog(
    ctx: WeftContext,
    contextRegistry: ContextRegistry,
    dataSources: DataSourceRegistry,
    networkClient: HttpClient,
    memoryStore: MemoryStore,
): List<WeftTool<*, *>> = listOf(
    NotifyShowTool(ctx),
    ScheduleCreateTool(ctx),
    ScheduleListTool(ctx),
    ScheduleCancelTool(ctx),
    UiAskTool(ctx),
    UiDialogTool(ctx),
    UiNavigateTool(ctx),
    UiRequestPermissionTool(ctx),
    UiRenderTool(ctx),
    UiNotifyTool(ctx),
    SystemUserContextTool(ctx, contextRegistry),
    DataQueryTool(ctx, dataSources),
    DataUpsertTool(ctx, dataSources),
    DataDeleteTool(ctx, dataSources),
    ExternalOpenUrlTool(ctx),
    ExternalLaunchAppTool(ctx),
    ExternalShareTool(ctx),
    ContactsReadTool(ctx),
    CalendarReadTool(ctx),
    CalendarCreateTool(ctx),
    CalendarUpdateTool(ctx),
    CalendarDeleteTool(ctx),
    ClipboardReadTool(ctx),
    ClipboardWriteTool(ctx),
    BiometricAuthenticateTool(ctx),
    HapticsTool(ctx),
    VisionOcrTool(ctx),
    VisionBarcodeTool(ctx),
    LocationCurrentTool(ctx),
    LocationGeocodeTool(ctx),
    LocationReverseGeocodeTool(ctx),
    SpeechSayTool(ctx),
    SpeechRecognizeTool(ctx),
    AudioRecordTool(ctx),
    CameraCaptureTool(ctx),
    FilesSaveTool(ctx),
    FilesReadTool(ctx),
    FilesShareTool(ctx),
    NetworkFetchTool(ctx, networkClient),
    MemoryStoreTool(ctx, memoryStore),
    MemoryRecallTool(ctx, memoryStore),
    MemoryCompactTool(ctx, memoryStore),
    // Read-only device-state tools (no permissions, no I/O of note).
    BatteryStatusTool(ctx),
    NetworkStatusTool(ctx),
    DeviceInfoTool(ctx),
    DisplayInfoTool(ctx),
    // Intent-launching tools — hand off to user-installed apps.
    MapsDirectionsTool(ctx),
    AlarmSetTool(ctx),
    // PDF — extract / render / create. PdfBox-Android backs read+create;
    // platform PdfRenderer backs render-pages.
    PdfReadTool(ctx),
    PdfRenderPagesTool(ctx),
    PdfCreateTool(ctx),
    // Bluetooth — narrow read-side surface. List paired, open settings,
    // best-effort device battery. No scan/connect (Android-locked-down).
    BluetoothListPairedTool(ctx),
    BluetoothOpenSettingsTool(ctx),
    BluetoothDeviceBatteryTool(ctx),
    // Gallery — read-only MediaStore queries. Returns content:// URIs
    // the agent can hand to vision_ocr / external_share / files_read.
    // Needs READ_MEDIA_* permissions; Play scrutinizes these. For
    // "user picks the file" flows prefer the picker tools below.
    MediaListRecentTool(ctx),
    MediaQueryTool(ctx),
    // Photo Picker — system-mediated, NO permission. Preferred over
    // the MediaLibrary tools whenever the user is the one choosing.
    MediaPickImageTool(ctx),
    MediaPickVideoTool(ctx),
    MediaPickAnyTool(ctx),
    // Installed-apps discovery — useful for routing decisions
    // (which music app is installed? which maps?).
    AppInstalledTool(ctx),
    AppListLaunchableTool(ctx),
    // Lightweight sensors — step counter, ambient light. Both
    // returnable as "available=false" when the device lacks them.
    SensorStepsTodayTool(ctx),
    SensorAmbientLightTool(ctx),
    // Pure date arithmetic — no I/O. Eliminates LLM date-math errors.
    DateComputeTool(ctx),
    // Telephony — Intent-handoff dial / SMS compose, plus carrier
    // info read. No permission for any of these.
    PhoneDialTool(ctx),
    SmsComposeTool(ctx),
    TelephonyInfoTool(ctx),
    // Wifi state read. SSID needs LOCATION on Android 9+.
    WifiInfoTool(ctx),
    // Volume control — per-stream get/set, normalized 0..1.
    VolumeGetTool(ctx),
    VolumeSetTool(ctx),
    // Power — keep screen on, per-window brightness. Scoped to
    // the foreground Activity; no permission needed.
    PowerKeepScreenOnTool(ctx),
    PowerSetBrightnessTool(ctx),
    // Settings deep-link — one tool, many panels via enum.
    SettingsOpenTool(ctx),
    // App shortcuts — pin / remove / list dynamic launcher shortcuts.
    ShortcutPushTool(ctx),
    ShortcutRemoveTool(ctx),
    ShortcutListTool(ctx),
    // Translation + language ID via ML Kit (~30MB model per pair).
    TranslateTextTool(ctx),
    DetectLanguageTool(ctx),
    // Image transforms — resize / crop / rotate via Bitmap APIs.
    ImageResizeTool(ctx),
    ImageCropTool(ctx),
    ImageRotateTool(ctx),
    // Pure utility tools — no OS, no permissions. Reduce LLM
    // arithmetic / string / regex mistakes by routing through code.
    MathEvalTool(ctx),
    TextTransformTool(ctx),
    HashTool(ctx),
    RegexMatchTool(ctx),
    UrlParseTool(ctx),
    ColorConvertTool(ctx),
    RandomChoiceTool(ctx),
    JsonQueryTool(ctx),
)
