package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.BatteryInfo
import dev.weft.contracts.DeviceInfo
import dev.weft.contracts.DisplayInfo
import dev.weft.contracts.NetworkInfo
import kotlinx.serialization.Serializable

/**
 * Read the battery state. No arguments, no permissions. Useful when the
 * model wants to reason about whether to suggest power-saving actions
 * ("you're at 12% and not charging — do you want me to skip the
 * follow-up download?") or honor user requests like "tell me when the
 * battery is full" (paired with a periodic check).
 */
class BatteryStatusTool(ctx: WeftContext) : WeftTool<BatteryStatusTool.Args, BatteryInfo>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<BatteryInfo>(),
    descriptor = ToolDescriptor(
        name = "battery_status",
        description = "Read the device's battery level (0–100), whether it's " +
            "charging, the power source (USB/AC/wireless/none), and whether " +
            "battery-saver mode is active. No permission required.",
        // We declare a single REQUIRED placeholder String parameter even
        // though the tool doesn't use it. Without this, Anthropic
        // (and possibly other providers) emits an empty STRING `""`
        // for the tool_use input instead of an empty object `{}`,
        // which crashes the JSON parser at the root path. Optional
        // params + Boolean types don't reliably force the model to
        // emit a real value — a required String does.
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're calling this tool, e.g. 'user asked'. Any short string; ignored by the tool.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {
    /**
     * Empty-args placeholder. kotlinx.serialization doesn't generate a
     * working schema for an empty class, and the model sometimes passes
     * a stray field that fails to deserialize. A single optional field
     * with a default gives the serializer something concrete to lock
     * onto without forcing the model to send anything.
     */
    @Serializable
    data class Args(val context: String = "")

    override suspend fun executeWeft(args: Args): BatteryInfo = os.systemInfo.battery()
}

/**
 * Read the network state — online/offline, transport (wifi/cellular/…),
 * and whether the connection is metered. No arguments, no permissions
 * (ACCESS_NETWORK_STATE is a normal install-time permission, already
 * declared in the app manifest).
 *
 * Use cases: "should I download this now? wait for wifi?", "am I
 * online?", "queue this for later if we're on cellular."
 */
class NetworkStatusTool(ctx: WeftContext) : WeftTool<NetworkStatusTool.Args, NetworkInfo>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<NetworkInfo>(),
    descriptor = ToolDescriptor(
        name = "network_status",
        description = "Read the network state: online (true/false), transport " +
            "(WIFI/CELLULAR/ETHERNET/BLUETOOTH/VPN/NONE), and whether the " +
            "connection is metered. No permission required.",
        // We declare a single REQUIRED placeholder String parameter even
        // though the tool doesn't use it. Without this, Anthropic
        // (and possibly other providers) emits an empty STRING `""`
        // for the tool_use input instead of an empty object `{}`,
        // which crashes the JSON parser at the root path. Optional
        // params + Boolean types don't reliably force the model to
        // emit a real value — a required String does.
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're calling this tool, e.g. 'user asked'. Any short string; ignored by the tool.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {
    /**
     * Empty-args placeholder. kotlinx.serialization doesn't generate a
     * working schema for an empty class, and the model sometimes passes
     * a stray field that fails to deserialize. A single optional field
     * with a default gives the serializer something concrete to lock
     * onto without forcing the model to send anything.
     */
    @Serializable
    data class Args(val context: String = "")

    override suspend fun executeWeft(args: Args): NetworkInfo = os.systemInfo.network()
}

/**
 * Read device metadata — manufacturer, model, OS version, locale,
 * timezone, free/total storage. No arguments, no permissions.
 *
 * Use cases: localizing responses, knowing the SDK level for
 * platform-feature suggestions, warning when storage is low.
 */
class DeviceInfoTool(ctx: WeftContext) : WeftTool<DeviceInfoTool.Args, DeviceInfo>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<DeviceInfo>(),
    descriptor = ToolDescriptor(
        name = "device_info",
        description = "Read device metadata: manufacturer, model, OS name + " +
            "version, Android SDK level, IETF locale tag (e.g. 'en-US'), " +
            "IANA timezone (e.g. 'America/Los_Angeles'), and primary " +
            "internal storage (free + total bytes). No permission required.",
        // We declare a single REQUIRED placeholder String parameter even
        // though the tool doesn't use it. Without this, Anthropic
        // (and possibly other providers) emits an empty STRING `""`
        // for the tool_use input instead of an empty object `{}`,
        // which crashes the JSON parser at the root path. Optional
        // params + Boolean types don't reliably force the model to
        // emit a real value — a required String does.
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're calling this tool, e.g. 'user asked'. Any short string; ignored by the tool.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {
    /**
     * Empty-args placeholder. kotlinx.serialization doesn't generate a
     * working schema for an empty class, and the model sometimes passes
     * a stray field that fails to deserialize. A single optional field
     * with a default gives the serializer something concrete to lock
     * onto without forcing the model to send anything.
     */
    @Serializable
    data class Args(val context: String = "")

    override suspend fun executeWeft(args: Args): DeviceInfo = os.systemInfo.device()
}

/**
 * Read display state — dark mode on/off, screen dimensions, density,
 * refresh rate, best-effort brightness, screen-interactive flag. No
 * permission.
 *
 * Use cases: "is the user in dark mode? (skip the bright pastel chart)",
 * "can I show this 4-line layout? screen is short", "user might be
 * driving (screen off) — speak the answer instead of rendering it."
 */
class DisplayInfoTool(ctx: WeftContext) : WeftTool<DisplayInfoTool.Args, DisplayInfo>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<DisplayInfo>(),
    descriptor = ToolDescriptor(
        name = "display_info",
        description = "Read display state: dark-mode flag, width/height in pixels, density, " +
            "refresh rate (Hz), brightness 0..1 (null if unknown), and whether the screen is " +
            "currently on. No permission required.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're calling this tool, e.g. 'user asked'. Any short string; ignored.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {
    @Serializable
    data class Args(val context: String = "")

    override suspend fun executeWeft(args: Args): DisplayInfo = os.systemInfo.display()
}
