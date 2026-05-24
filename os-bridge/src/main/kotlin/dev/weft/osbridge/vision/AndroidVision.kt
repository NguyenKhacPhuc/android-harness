package dev.weft.osbridge.vision

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.weft.contracts.DecodedBarcode
import dev.weft.contracts.OcrBlock
import dev.weft.contracts.OcrResult
import dev.weft.contracts.RectPx
import dev.weft.contracts.Vision
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android implementation of [Vision], backed by ML Kit's on-device text
 * recognition and barcode scanning. Both libraries download model files
 * on first use (via Play Services) and then run fully offline.
 *
 * Input is a URI — the caller is responsible for having already saved the
 * image via [dev.weft.contracts.Files.save] or otherwise produced a URI
 * the system can resolve (camera roll, content provider, file://).
 *
 * Threading: ML Kit's `Task<T>` API hops to its own background pool
 * internally, so we just wrap each await in a [suspendCancellableCoroutine].
 */
public class AndroidVision(context: Context) : Vision {

    private val appContext: Context = context.applicationContext

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeScanner = BarcodeScanning.getClient()

    override suspend fun ocr(imageUri: String): OcrResult {
        val image = InputImage.fromFilePath(appContext, Uri.parse(imageUri))
        val text = suspendCancellableCoroutine { cont ->
            textRecognizer.process(image)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
        val blocks = text.textBlocks.map { block ->
            OcrBlock(
                text = block.text,
                boundsPx = block.boundingBox?.toRectPx(),
            )
        }
        return OcrResult(text = text.text, blocks = blocks)
    }

    override suspend fun barcodes(imageUri: String): List<DecodedBarcode> {
        val image = InputImage.fromFilePath(appContext, Uri.parse(imageUri))
        val barcodes: List<Barcode> = suspendCancellableCoroutine { cont ->
            barcodeScanner.process(image)
                .addOnSuccessListener { list -> if (cont.isActive) cont.resume(list) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
        return barcodes.mapNotNull { bc ->
            val raw = bc.rawValue ?: return@mapNotNull null
            DecodedBarcode(
                rawValue = raw,
                format = bc.format.toFormatToken(),
                boundsPx = bc.boundingBox?.toRectPx(),
            )
        }
    }
}

private fun Rect.toRectPx(): RectPx = RectPx(left = left, top = top, right = right, bottom = bottom)

/** Map ML Kit's int constants to a readable token the LLM can reference. */
private fun Int.toFormatToken(): String = when (this) {
    Barcode.FORMAT_QR_CODE -> "QR_CODE"
    Barcode.FORMAT_AZTEC -> "AZTEC"
    Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_CODE_128 -> "CODE_128"
    Barcode.FORMAT_CODE_39 -> "CODE_39"
    Barcode.FORMAT_CODE_93 -> "CODE_93"
    Barcode.FORMAT_CODABAR -> "CODABAR"
    Barcode.FORMAT_EAN_13 -> "EAN_13"
    Barcode.FORMAT_EAN_8 -> "EAN_8"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_UPC_A -> "UPC_A"
    Barcode.FORMAT_UPC_E -> "UPC_E"
    else -> "UNKNOWN"
}
