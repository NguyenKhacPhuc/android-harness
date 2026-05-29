package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.DecodedBarcode
import dev.weft.contracts.OcrResult
import kotlinx.serialization.Serializable

/**
 * Run on-device OCR over an image. Returns the concatenated text plus a
 * count of recognized blocks (the LLM rarely needs per-block bounds, so
 * we keep the response compact). Operates on a file URI — the agent
 * typically arranges for an image to land via files_save or the system
 * camera first.
 *
 * Latency: ~100-500ms for typical receipts on a modern phone. First call
 * may include a one-time model download from Play Services.
 */
class VisionOcrTool(ctx: WeftContext) : WeftTool<VisionOcrTool.Args, VisionOcrTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "vision_ocr",
        description = "Run on-device OCR (text recognition) over an image. Returns the recognized " +
            "text. Use for receipts, business cards, IDs, signs, screenshots. Image must already " +
            "exist on the device as a content:// or file:// URI — typically saved via files_save " +
            "or returned from the system camera.",
        requiredParameters = listOf(
            ToolParameterDescriptor("imageUri", "URI of the image file to OCR.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
) {

    @Serializable
    data class Args(val imageUri: String)

    @Serializable
    data class Result(
        val text: String,
        val blockCount: Int,
    )

    override suspend fun executeWeft(args: Args): Result {
        val r: OcrResult = os.vision.ocr(args.imageUri)
        return Result(text = r.text, blockCount = r.blocks.size)
    }
}

/**
 * Decode barcodes / QR codes from an image. Returns each barcode's
 * payload and format. Empty list when nothing recognizable is found.
 */
class VisionBarcodeTool(ctx: WeftContext) :
    WeftTool<VisionBarcodeTool.Args, VisionBarcodeTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "vision_barcode",
            description = "Decode barcodes and QR codes from an image. Supports QR, EAN-8/13, " +
                "UPC-A/E, Code 128/39/93, PDF417, Aztec, Data Matrix. Returns each barcode's " +
                "decoded value and format token. Empty list when nothing is found.",
            requiredParameters = listOf(
                ToolParameterDescriptor("imageUri", "URI of the image file to scan.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val imageUri: String)

    @Serializable
    data class Item(val value: String, val format: String)

    @Serializable
    data class Result(val items: List<Item>)

    override suspend fun executeWeft(args: Args): Result {
        val decoded: List<DecodedBarcode> = os.vision.barcodes(args.imageUri)
        return Result(items = decoded.map { Item(value = it.rawValue, format = it.format) })
    }
}
