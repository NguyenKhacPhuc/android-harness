package dev.weft.osbridge.vision

import dev.weft.contracts.DecodedBarcode
import dev.weft.contracts.OcrResult
import dev.weft.contracts.Vision

/**
 * iOS stub for [Vision]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `Vision.framework` —
 * `VNRecognizeTextRequest` (with `.accurate` recognition level) for OCR,
 * `VNDetectBarcodesRequest` for barcodes. Both run through a
 * `VNImageRequestHandler(cgImage:options:)` initialized from the image
 * URI (load via `UIImage(contentsOfFile:)` then `cgImage`). Map
 * `VNRecognizedTextObservation` / `VNBarcodeObservation` results onto
 * the result types — bounding boxes need conversion from normalized to
 * pixel coordinates.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosVision : Vision {
    override suspend fun ocr(imageUri: String): OcrResult =
        TODO("IosVision.ocr — wrap VNRecognizeTextRequest through a VNImageRequestHandler")

    override suspend fun barcodes(imageUri: String): List<DecodedBarcode> =
        TODO("IosVision.barcodes — wrap VNDetectBarcodesRequest through a VNImageRequestHandler")
}
