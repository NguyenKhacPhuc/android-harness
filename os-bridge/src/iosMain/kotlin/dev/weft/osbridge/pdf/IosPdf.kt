package dev.weft.osbridge.pdf

import dev.weft.contracts.FileRef
import dev.weft.contracts.Pdf
import dev.weft.contracts.PdfRenderResult
import dev.weft.contracts.PdfTextResult

/**
 * iOS stub for [Pdf]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `PDFKit.PDFDocument` —
 * `PDFDocument(url:)` then iterate `page(at:)` and concatenate
 * `page.string` for extractText, `page.thumbnail(of:for:)` or render
 * via `UIGraphicsImageRenderer` driven from `page.bounds(for:)` for
 * renderPages, and `UIGraphicsPDFRenderer(bounds:format:)` plus
 * `NSAttributedString.draw(in:)` for create.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosPdf : Pdf {
    override suspend fun extractText(uri: String, pageRange: String?, maxPages: Int): PdfTextResult =
        TODO("IosPdf.extractText — wrap PDFDocument(url:) iterating page(at:).string")

    override suspend fun renderPages(uri: String, pages: List<Int>?, scale: Float): PdfRenderResult =
        TODO("IosPdf.renderPages — wrap PDFPage.thumbnail(of:for:) or UIGraphicsImageRenderer over page.bounds(for:)")

    override suspend fun create(title: String, body: String, fileName: String): FileRef =
        TODO("IosPdf.create — wrap UIGraphicsPDFRenderer(bounds:format:) drawing NSAttributedString")
}
