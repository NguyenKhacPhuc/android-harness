package dev.weft.osbridge.vision

import dev.weft.contracts.DecodedBarcode
import dev.weft.contracts.OcrBlock
import dev.weft.contracts.OcrResult
import dev.weft.contracts.RectPx
import dev.weft.contracts.Vision
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIImage
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRectangleObservation
import kotlin.math.roundToInt

/**
 * iOS [Vision] backed by Vision.framework. Loads a `CGImage` from the
 * URI (same `file://`/path pattern as IosImageOps), runs the request
 * synchronously through a `VNImageRequestHandler`, and maps the
 * normalized bottom-left-origin bounding boxes onto top-left pixel
 * [RectPx]. Returns empty results when the image can't be loaded or no
 * observations come back; never throws.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosVision : Vision {

    override suspend fun ocr(imageUri: String): OcrResult = withContext(Dispatchers.Default) {
        val cg = loadCGImage(imageUri) ?: return@withContext OcrResult(text = "")
        val w = CGImageGetWidth(cg).toInt()
        val h = CGImageGetHeight(cg).toInt()
        val request = VNRecognizeTextRequest(completionHandler = null)
        val handler = VNImageRequestHandler(cGImage = cg, options = emptyMap<Any?, Any?>())
        handler.performRequests(listOf(request), null)

        @Suppress("UNCHECKED_CAST")
        val observations = (request.results as? List<VNRecognizedTextObservation>).orEmpty()
        val blocks = observations.mapNotNull { obs ->
            val candidate = obs.topCandidates(1u).firstOrNull() as? VNRecognizedText
            val str = candidate?.string ?: return@mapNotNull null
            OcrBlock(text = str, boundsPx = obs.boundsPx(w, h))
        }
        OcrResult(text = blocks.joinToString("\n") { it.text }, blocks = blocks)
    }

    override suspend fun barcodes(imageUri: String): List<DecodedBarcode> =
        withContext(Dispatchers.Default) {
            val cg = loadCGImage(imageUri) ?: return@withContext emptyList()
            val w = CGImageGetWidth(cg).toInt()
            val h = CGImageGetHeight(cg).toInt()
            val request = VNDetectBarcodesRequest(completionHandler = null)
            val handler = VNImageRequestHandler(cGImage = cg, options = emptyMap<Any?, Any?>())
            handler.performRequests(listOf(request), null)

            @Suppress("UNCHECKED_CAST")
            val observations = (request.results as? List<VNBarcodeObservation>).orEmpty()
            observations.mapNotNull { obs ->
                val raw = obs.payloadStringValue ?: return@mapNotNull null
                DecodedBarcode(
                    rawValue = raw,
                    format = obs.symbology ?: "",
                    boundsPx = obs.boundsPx(w, h),
                )
            }
        }

    /**
     * Convert a Vision normalized boundingBox (origin bottom-left, 0..1)
     * to top-left-origin pixel coordinates.
     */
    private fun VNRectangleObservation.boundsPx(w: Int, h: Int): RectPx =
        boundingBox.useContents {
            val minX = origin.x
            val minY = origin.y
            val maxX = origin.x + size.width
            val maxY = origin.y + size.height
            RectPx(
                left = (minX * w).roundToInt(),
                top = ((1.0 - maxY) * h).roundToInt(),
                right = (maxX * w).roundToInt(),
                bottom = ((1.0 - minY) * h).roundToInt(),
            )
        }

    private fun loadCGImage(uri: String): CGImageRef? {
        val data: NSData? = if (uri.startsWith("file://")) {
            NSURL.URLWithString(uri)?.let { NSData.dataWithContentsOfURL(it) }
        } else {
            NSData.dataWithContentsOfFile(uri)
        }
        return data?.let { UIImage.imageWithData(it) }?.CGImage
    }
}
