package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.Permission
import dev.weft.contracts.WifiInfo
import kotlinx.serialization.Serializable

/**
 * Read the current WiFi connection — SSID, link speed, signal
 * strength, frequency band. Use for: "what wifi am I on?", "is my
 * signal strong?", "am I on 5GHz?".
 *
 * SSID is null without LOCATION permission on Android 9+ (OS
 * censorship). All other fields are always available when WiFi is
 * connected.
 */
public class WifiInfoTool(ctx: WeftContext) : WeftTool<WifiInfoTool.Args, WifiInfo>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<WifiInfo>(),
    descriptor = ToolDescriptor(
        name = "wifi_info",
        description = "Read the active WiFi connection: enabled, connected, SSID, link " +
            "speed Mbps, RSSI dBm, frequency MHz (2400=2.4GHz, 5000=5GHz, 6000=6GHz). " +
            "SSID requires LOCATION permission on Android 9+.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're checking, e.g. 'user asked what wifi they're on'. Ignored.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
    // LOCATION is optional — without it SSID is null but everything
    // else still works. We don't gate the tool on it; the host app's
    // preamble can advise the model to request location when SSID is
    // required and it's null.
    requiredPermissions = emptySet<Permission>(),
) {
    @Serializable
    public data class Args(val context: String = "")

    override suspend fun executeWeft(args: Args): WifiInfo = os.wifi.info()
}
