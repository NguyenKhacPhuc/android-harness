package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Open the system camera, let the user take a photo, return its URI.
 *
 * **Suspends until the user shoots-or-cancels** — this is one of the few
 * tools whose latency includes a human-in-the-loop step. The agent should
 * expect anywhere from seconds to minutes between calling and getting a
 * response. Pairs naturally with `vision_ocr` / `vision_barcode` on the
 * returned URI: "take a photo of the receipt" → camera_capture →
 * vision_ocr.
 *
 * sideEffecting = true (creates a file + interrupts the user with the
 * camera UI). Not destructive — produces a new file rather than mutating
 * existing state.
 */
public class CameraCaptureTool(ctx: WeftContext) :
    WeftTool<CameraCaptureTool.Args, CameraCaptureTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "camera_capture",
            description = "Open the system camera, wait for the user to take a photo, and return " +
                "the captured image URI. Suspends until the user shoots or cancels — can take " +
                "tens of seconds. Returns hasFile=false if the user cancelled, no camera is " +
                "available, or no foreground activity exists. The returned URI is usable with " +
                "vision_ocr / vision_barcode / external_share.",
            // `context` is a required placeholder String — see
            // SystemInfoTools.kt for the full rationale. Without at least
            // one *required* String param, Anthropic emits "" instead
            // of {} for the tool_use input and the JSON parser crashes.
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're calling this tool, e.g. 'user asked for a photo'. Any short string; ignored by the tool.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "namePrefix",
                    "Filename prefix (alphanumeric / underscore / hyphen only). Default 'photo'.",
                    ToolParameterType.String,
                ),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(
        val context: String = "",
        val namePrefix: String = "photo",
    )

    @Serializable
    public data class Result(
        val hasFile: Boolean,
        val uri: String? = null,
        val sizeBytes: Long? = null,
    )

    override suspend fun executeWeft(args: Args): Result {
        val ref = os.camera.captureImage(args.namePrefix)
            ?: return Result(hasFile = false)
        return Result(hasFile = true, uri = ref.uri, sizeBytes = ref.sizeBytes)
    }
}
