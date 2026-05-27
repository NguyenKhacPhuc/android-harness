package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.RectPx
import kotlinx.serialization.Serializable

/**
 * Resize the longest edge of an image to a target pixel count.
 * Maintains aspect ratio. Writes a new JPEG to the app's cache.
 *
 * Use for: shrinking a too-big photo before OCR, preparing a
 * lightweight share, generating a thumbnail.
 */
public class ImageResizeTool(ctx: WeftContext) :
    WeftTool<ImageResizeTool.Args, ImageResizeTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "image_resize",
            description = "Resize an image so its longest edge is maxEdgePx (aspect ratio " +
                "preserved). Reads any content:// or file:// URI. Writes JPEG to cache, " +
                "returns the new URI. NOT for cropping (use image_crop) or rotating " +
                "(use image_rotate).",
            requiredParameters = listOf(
                ToolParameterDescriptor("uri", "Source image URI.", ToolParameterType.String),
                ToolParameterDescriptor(
                    "maxEdgePx",
                    "Target maximum edge length in pixels (e.g. 1024).",
                    ToolParameterType.Integer,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "namePrefix",
                    "Filename prefix. Default 'resized'.",
                    ToolParameterType.String,
                ),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(
        val uri: String,
        val maxEdgePx: Int,
        val namePrefix: String = "resized",
    )

    @Serializable
    public data class Result(
        val success: Boolean,
        val uri: String? = null,
        val sizeBytes: Long? = null,
    )

    override suspend fun executeWeft(args: Args): Result {
        val ref = os.imageOps.resize(args.uri, args.maxEdgePx.coerceAtLeast(1), args.namePrefix)
            ?: return Result(success = false)
        return Result(success = true, uri = ref.uri, sizeBytes = ref.sizeBytes)
    }
}

/**
 * Crop an image to a pixel rectangle. (0,0) is top-left. Out-of-
 * bounds rectangles return success=false.
 */
public class ImageCropTool(ctx: WeftContext) :
    WeftTool<ImageCropTool.Args, ImageCropTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "image_crop",
            description = "Crop an image to a pixel rectangle (left, top, right, bottom; " +
                "0,0 = top-left). Returns the new URI on success. NOT for resizing " +
                "(image_resize) or rotating (image_rotate).",
            requiredParameters = listOf(
                ToolParameterDescriptor("uri", "Source image URI.", ToolParameterType.String),
                ToolParameterDescriptor("left", "Crop rect left (px).", ToolParameterType.Integer),
                ToolParameterDescriptor("top", "Crop rect top (px).", ToolParameterType.Integer),
                ToolParameterDescriptor("right", "Crop rect right (px).", ToolParameterType.Integer),
                ToolParameterDescriptor("bottom", "Crop rect bottom (px).", ToolParameterType.Integer),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "namePrefix",
                    "Filename prefix. Default 'cropped'.",
                    ToolParameterType.String,
                ),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(
        val uri: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val namePrefix: String = "cropped",
    )

    @Serializable
    public data class Result(
        val success: Boolean,
        val uri: String? = null,
        val sizeBytes: Long? = null,
    )

    override suspend fun executeWeft(args: Args): Result {
        val ref = os.imageOps.crop(
            args.uri,
            RectPx(args.left, args.top, args.right, args.bottom),
            args.namePrefix,
        ) ?: return Result(success = false)
        return Result(success = true, uri = ref.uri, sizeBytes = ref.sizeBytes)
    }
}

/**
 * Rotate an image by a multiple of 90 degrees clockwise.
 * Other values snap to the nearest 90.
 */
public class ImageRotateTool(ctx: WeftContext) :
    WeftTool<ImageRotateTool.Args, ImageRotateTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "image_rotate",
            description = "Rotate an image by 90/180/270 degrees clockwise. Other values " +
                "snap to the nearest multiple of 90. NOT for arbitrary-angle rotation.",
            requiredParameters = listOf(
                ToolParameterDescriptor("uri", "Source image URI.", ToolParameterType.String),
                ToolParameterDescriptor(
                    "degrees",
                    "Rotation 90/180/270 (snapped to nearest 90).",
                    ToolParameterType.Integer,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "namePrefix",
                    "Filename prefix. Default 'rotated'.",
                    ToolParameterType.String,
                ),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    public data class Args(
        val uri: String,
        val degrees: Int,
        val namePrefix: String = "rotated",
    )

    @Serializable
    public data class Result(
        val success: Boolean,
        val uri: String? = null,
        val sizeBytes: Long? = null,
    )

    override suspend fun executeWeft(args: Args): Result {
        val ref = os.imageOps.rotate(args.uri, args.degrees, args.namePrefix)
            ?: return Result(success = false)
        return Result(success = true, uri = ref.uri, sizeBytes = ref.sizeBytes)
    }
}
