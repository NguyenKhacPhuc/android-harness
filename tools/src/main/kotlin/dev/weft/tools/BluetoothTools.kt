package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.BluetoothDeviceInfo
import dev.weft.contracts.Permission
import kotlinx.serialization.Serializable

/**
 * List paired Bluetooth devices. Returns name + MAC + type + a
 * best-effort connected flag. Requires BLUETOOTH_CONNECT runtime
 * permission on Android 12+; the substrate's permission gate will
 * refuse and hint the LLM to call `ui_request_permission` first.
 *
 * Returns empty when Bluetooth is off or nothing is paired. Does NOT
 * scan for nearby unpaired devices — that's a separate, heavier API.
 */
class BluetoothListPairedTool(ctx: WeftContext) : WeftTool<BluetoothListPairedTool.Args, BluetoothListPairedTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "bluetooth_list_paired",
        description = "List the user's paired Bluetooth devices (name, MAC " +
            "address, type, connection state). Does NOT scan for nearby " +
            "devices. Returns empty when Bluetooth is off.",
        // Required placeholder String — see SystemInfoTools.kt for the
        // full rationale. Anthropic emits "" for empty/Boolean-only
        // schemas; a required String forces a real value.
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're calling this tool, e.g. 'user asked'. Any short string; ignored by the tool.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
    requiredPermissions = setOf(Permission.BLUETOOTH_CONNECT),
) {
    @Serializable
    data class Args(val context: String = "")

    @Serializable
    data class Result(val devices: List<BluetoothDeviceInfo>)

    override suspend fun executeWeft(args: Args): Result = Result(os.bluetooth.listPaired())
}

/**
 * Open the system Bluetooth settings panel. Use when the user asks
 * the agent to "turn on Bluetooth" / "connect to my headphones" /
 * anything else that needs the user's hands — Android 12+ blocks
 * apps from toggling Bluetooth programmatically, so we hand off.
 */
class BluetoothOpenSettingsTool(ctx: WeftContext) : WeftTool<BluetoothOpenSettingsTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "bluetooth_open_settings",
        description = "Open the system Bluetooth settings panel so the user " +
            "can toggle Bluetooth, pair a device, or connect to one. " +
            "Use this when the user asks to enable BT / connect a device — " +
            "apps can't do those programmatically on Android 12+.",
        // Required placeholder String — see SystemInfoTools.kt for the
        // full rationale. Anthropic emits "" for empty/Boolean-only
        // schemas; a required String forces a real value.
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're calling this tool, e.g. 'user asked'. Any short string; ignored by the tool.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
    sideEffecting = true,
) {
    @Serializable
    data class Args(val context: String = "")

    override suspend fun executeWeft(args: Args): String {
        val ok = os.bluetooth.openSettings()
        return if (ok) "Opened Bluetooth settings."
        else "Couldn't open Bluetooth settings — no handler available."
    }
}

/**
 * Best-effort battery level for a paired Bluetooth device. Returns
 * null when the device doesn't expose battery (not all do — AirPods,
 * some older headsets), when permission is denied, or when the
 * device isn't currently connected.
 *
 * The agent should call `bluetooth_list_paired` first to discover the
 * MAC address, then pass it here.
 */
class BluetoothDeviceBatteryTool(ctx: WeftContext) : WeftTool<BluetoothDeviceBatteryTool.Args, BluetoothDeviceBatteryTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "bluetooth_device_battery",
        description = "Read the battery level (0–100) of a paired Bluetooth " +
            "device by MAC address. Returns null when the device doesn't " +
            "expose battery, isn't connected, or runs Android < 10.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "address",
                "MAC address of the paired device (from bluetooth_list_paired).",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
    requiredPermissions = setOf(Permission.BLUETOOTH_CONNECT),
) {
    @Serializable
    data class Args(val address: String)

    @Serializable
    data class Result(val percent: Int?, val address: String)

    override suspend fun executeWeft(args: Args): Result =
        Result(percent = os.bluetooth.deviceBattery(args.address), address = args.address)
}
