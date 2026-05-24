package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Open directions in the user's preferred maps app. Side-effecting
 * (launches an external Intent) but not destructive — the user lands
 * inside their maps app where they can confirm or back out without
 * consequence.
 *
 * The agent should call this when the user asks for directions ("how
 * do I get to X?", "directions to the nearest Y"). It should NOT
 * call this for "where is X?" style queries — there's no built-in
 * places-search tool yet.
 */
public class MapsDirectionsTool(ctx: WeftContext) : WeftTool<MapsDirectionsTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "maps_open_directions",
        description = "Open directions in the user's maps app. " +
            "[to] is required — an address, place name, or 'lat,lng'. " +
            "[from] is optional — omit to use the device's current location. " +
            "[mode] is optional — 'driving' (default), 'walking', 'transit', 'bicycling'.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "to",
                "Destination: address, place name, or 'lat,lng' string.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "from",
                "Origin: address or 'lat,lng'. Omit for current location.",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "mode",
                "Travel mode: driving / walking / transit / bicycling.",
                ToolParameterType.String,
            ),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    public data class Args(
        val to: String,
        val from: String? = null,
        val mode: String = "driving",
    )

    override suspend fun executeWeft(args: Args): String {
        val opened = os.intents.openMapsDirections(
            to = args.to,
            from = args.from,
            mode = args.mode,
        )
        return if (opened) {
            "Opened directions to '${args.to}' (${args.mode})."
        } else {
            // No maps app or browser installed to handle the maps URL.
            // Common on emulators / minimal ROMs.
            "No maps app (or browser) is installed on this device to handle the " +
                "directions request. Tell the user this and suggest they install " +
                "Google Maps from the Play Store, or open a browser manually to " +
                "search for '${args.to}'."
        }
    }
}
