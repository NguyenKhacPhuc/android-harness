package dev.weft.osbridge.pdf

import dev.weft.contracts.FileRef
import dev.weft.contracts.Pdf
import dev.weft.contracts.PdfRenderResult
import dev.weft.contracts.PdfTextResult
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSAttributedString
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.NSFontAttributeName
import platform.UIKit.UIFont
import platform.UIKit.sizeWithAttributes
import platform.UIKit.UIGraphicsPDFRenderer
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.drawInRect

/**
 * iOS [Pdf] backed by PDFKit. extractText reads `PDFPage.string`;
 * renderPages draws each page into a `UIGraphicsImageRenderer` at the
 * requested scale and writes PNGs into the Caches directory; create lays
 * a wrapped text body onto US-Letter pages via `UIGraphicsPDFRenderer`.
 *
 * Page rendering uses PDFPage's own `drawWithBox:` so it matches PDFKit's
 * geometry. All readers return empty/error shapes on failure rather than
 * throwing.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public open class IosPdf : Pdf {

    override suspend fun extractText(
        uri: String,
        pageRange: String?,
        maxPages: Int,
    ): PdfTextResult = withContext(Dispatchers.Default) {
        val document = openDocument(uri)
            ?: return@withContext PdfTextResult("", 0, emptyList(), "could not open PDF")
        val total = document.pageCount.toInt()
        val pages = resolvePageRange(pageRange, total, maxPages)
        val builder = StringBuilder()
        for (page in pages) {
            // PDFKit is 0-indexed; the API is 1-indexed.
            val text = document.pageAtIndex((page - 1).toULong())?.string()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                if (builder.isNotEmpty()) builder.append("\n\n")
                builder.append(text)
            }
        }
        PdfTextResult(text = builder.toString(), pageCount = total, extractedPages = pages)
    }

    override suspend fun renderPages(
        uri: String,
        pages: List<Int>?,
        scale: Float,
    ): PdfRenderResult = withContext(Dispatchers.Default) {
        val document = openDocument(uri)
            ?: return@withContext PdfRenderResult(emptyList(), 0, "could not open PDF")
        val total = document.pageCount.toInt()
        val targets = (pages ?: (1..total).toList())
            .filter { it in 1..total }
            .distinct()
        val dir = cachesSubdir("pdf-render") ?: return@withContext PdfRenderResult(
            emptyList(),
            total,
            "could not open caches directory",
        )
        val uris = mutableListOf<String>()
        for (pageNum in targets) {
            val page = document.pageAtIndex((pageNum - 1).toULong()) ?: continue
            val box = kPDFDisplayBoxMediaBox
            val bounds = page.boundsForBox(box)
            val (boxW, boxH) = bounds.useContents { size.width to size.height }
            val w = (boxW * scale).coerceAtLeast(1.0)
            val h = (boxH * scale).coerceAtLeast(1.0)
            // PDFPage renders itself to a UIImage at the requested point
            // size; the renderer-scale is 1 so a point equals a pixel.
            val image = page.thumbnailOfSize(CGSizeMake(w, h), forBox = box)
            val data = UIImagePNGRepresentation(image) ?: continue
            val path = "$dir/page-$pageNum-${NSUUID().UUIDString}.png"
            if (!data.writeToFile(path, atomically = true)) continue
            uris += NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
        }
        PdfRenderResult(imageUris = uris, pageCount = total)
    }

    override suspend fun create(
        title: String,
        body: String,
        fileName: String,
    ): FileRef = withContext(Dispatchers.Default) {
        val pageRect = CGRectMake(0.0, 0.0, LETTER_W, LETTER_H)
        val renderer = UIGraphicsPDFRenderer(bounds = pageRect)
        val margin = MARGIN
        val contentW = LETTER_W - margin * 2
        val contentH = LETTER_H - margin * 2

        val titleFont = UIFont.boldSystemFontOfSize(TITLE_SIZE)
        val bodyFont = UIFont.systemFontOfSize(BODY_SIZE)

        val data = renderer.PDFDataWithActions { context ->
            // First page begins with the title, then the body flows.
            context?.beginPage()
            var y = margin
            val titleAttr = attributed(title, titleFont)
            val titleH = TITLE_SIZE * TITLE_LINE_FACTOR
            titleAttr.drawInRect(CGRectMake(margin, y, contentW, titleH))
            y += titleH + BODY_SIZE

            // Paginate the body line-by-line on wrapped paragraphs.
            val lineH = BODY_SIZE * BODY_LINE_FACTOR
            for (paragraph in body.split("\n")) {
                if (paragraph.isBlank()) {
                    y += lineH
                    continue
                }
                for (line in wrap(paragraph, bodyFont, contentW)) {
                    if (y + lineH > margin + contentH) {
                        context?.beginPage()
                        y = margin
                    }
                    attributed(line, bodyFont).drawInRect(CGRectMake(margin, y, contentW, lineH))
                    y += lineH
                }
            }
        }

        val path = "${cachesSubdir("pdf-create") ?: caches()}/${sanitizeFileName(fileName)}"
        data.writeToFile(path, atomically = true)
        val outUri = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
        FileRef(uri = outUri, sizeBytes = data.length.toLong())
    }

    private fun attributed(text: String, font: UIFont): NSAttributedString =
        NSAttributedString.create(
            string = text,
            attributes = mapOf<Any?, Any?>(NSFontAttributeName to font),
        )

    /**
     * Word-wrap a paragraph to fit [maxWidth] using each candidate's
     * measured width. Falls back to char-wrapping tokens longer than a
     * full line.
     */
    private fun wrap(text: String, font: UIFont, maxWidth: Double): List<String> {
        if (text.isBlank()) return listOf(text)
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate, font) <= maxWidth) {
                current.clear()
                current.append(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current.clear()
                }
                if (measure(word, font) > maxWidth) {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        if (measure(chunk.toString() + ch, font) > maxWidth) {
                            lines += chunk.toString()
                            chunk = StringBuilder().append(ch)
                        } else {
                            chunk.append(ch)
                        }
                    }
                    if (chunk.isNotEmpty()) current.append(chunk)
                } else {
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun measure(text: String, font: UIFont): Double =
        (text as NSString).sizeWithAttributes(mapOf<Any?, Any?>(NSFontAttributeName to font)).useContents { width }

    private fun openDocument(uri: String): PDFDocument? {
        val url = if (uri.startsWith("file://")) NSURL.URLWithString(uri) else NSURL.fileURLWithPath(uri)
        return url?.let { PDFDocument(uRL = it) }
    }

    /**
     * 1-indexed page range parser: "1-3,5,8-10" → sorted unique pages,
     * clamped to `[1, total]` and capped. Null/blank → first [cap] pages.
     */
    private fun resolvePageRange(spec: String?, total: Int, cap: Int): List<Int> {
        if (spec.isNullOrBlank()) return (1..total.coerceAtMost(cap)).toList()
        val pages = mutableSetOf<Int>()
        for (chunk in spec.split(',')) {
            val trimmed = chunk.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.contains('-')) {
                val parts = trimmed.split('-', limit = 2)
                val start = parts[0].trim().toIntOrNull() ?: continue
                val end = parts[1].trim().toIntOrNull() ?: continue
                for (p in start..end) pages += p
            } else {
                trimmed.toIntOrNull()?.let { pages += it }
            }
        }
        return pages.filter { it in 1..total }.take(cap)
    }

    private fun caches(): String =
        NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: NSHomeFallback

    private fun cachesSubdir(name: String): String? {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return null
        val dir = "$caches/$name"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun sanitizeFileName(raw: String): String {
        val clean = raw.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            .ifBlank { "document.pdf" }
        return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
    }

    private companion object {
        const val LETTER_W = 612.0
        const val LETTER_H = 792.0
        const val MARGIN = 50.0
        const val TITLE_SIZE = 16.0
        const val BODY_SIZE = 11.0
        const val TITLE_LINE_FACTOR = 1.6
        const val BODY_LINE_FACTOR = 1.4
        const val NSHomeFallback = "/tmp"
    }
}
