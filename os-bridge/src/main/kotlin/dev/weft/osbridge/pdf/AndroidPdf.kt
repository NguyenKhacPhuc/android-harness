package dev.weft.osbridge.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.weft.contracts.FileRef
import dev.weft.contracts.Pdf
import dev.weft.contracts.PdfRenderResult
import dev.weft.contracts.PdfTextResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Android implementation of [Pdf]. Text extraction + creation go
 * through PDFBox-Android; page rendering uses the platform's built-in
 * [PdfRenderer] (no extra dep needed for that path).
 *
 * PDFBox-Android needs a one-time init via [PDFBoxResourceLoader.init]
 * — we lazy-init on first call so users who never touch PDF don't pay
 * the ~50 ms cold-start cost.
 *
 * All file output lands in `context.cacheDir` (auto-cleaned by Android
 * under storage pressure). Callers that need durable storage should
 * pass the returned URI to `files_save` to copy it somewhere persistent.
 */
class AndroidPdf(private val context: Context) : Pdf {

    private val initialized: Boolean by lazy {
        runCatching { PDFBoxResourceLoader.init(context) }.isSuccess
    }

    override suspend fun extractText(
        uri: String,
        pageRange: String?,
        maxPages: Int,
    ): PdfTextResult = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext PdfTextResult("", 0, emptyList(), "PDFBox init failed")
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext PdfTextResult("", 0, emptyList(), "Invalid URI: $uri")

        runCatching {
            context.contentResolver.openInputStream(parsed)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    val total = doc.numberOfPages
                    val pages = resolvePageRange(pageRange, total, maxPages)
                    val stripper = PDFTextStripper()
                    val builder = StringBuilder()
                    for (page in pages) {
                        stripper.startPage = page
                        stripper.endPage = page
                        val pageText = stripper.getText(doc).trim()
                        if (pageText.isNotEmpty()) {
                            if (builder.isNotEmpty()) builder.append("\n\n")
                            builder.append(pageText)
                        }
                    }
                    PdfTextResult(
                        text = builder.toString(),
                        pageCount = total,
                        extractedPages = pages,
                    )
                }
            } ?: PdfTextResult("", 0, emptyList(), "Couldn't open URI: $uri")
        }.getOrElse {
            PdfTextResult("", 0, emptyList(), "Extraction failed: ${it.message ?: it::class.simpleName}")
        }
    }

    override suspend fun renderPages(
        uri: String,
        pages: List<Int>?,
        scale: Float,
    ): PdfRenderResult = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext PdfRenderResult(emptyList(), 0, "Invalid URI: $uri")

        runCatching {
            // PdfRenderer needs a ParcelFileDescriptor. For content://
            // URIs we open via ContentResolver; for file:// we go direct.
            val pfd: ParcelFileDescriptor = context.contentResolver
                .openFileDescriptor(parsed, "r")
                ?: return@runCatching PdfRenderResult(emptyList(), 0, "Couldn't open URI: $uri")
            pfd.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val total = renderer.pageCount
                    val targetPages = (pages ?: (1..total).toList())
                        .filter { it in 1..total }
                        .distinct()
                    val uris = mutableListOf<String>()
                    for (pageNum in targetPages) {
                        // PdfRenderer is 0-indexed; user-facing API is 1-indexed.
                        renderer.openPage(pageNum - 1).use { page ->
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val outFile = File(context.cacheDir, "pdf-page-${UUID.randomUUID()}.png")
                            FileOutputStream(outFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            bitmap.recycle()
                            uris += Uri.fromFile(outFile).toString()
                        }
                    }
                    PdfRenderResult(imageUris = uris, pageCount = total)
                }
            }
        }.getOrElse {
            PdfRenderResult(emptyList(), 0, "Render failed: ${it.message ?: it::class.simpleName}")
        }
    }

    override suspend fun create(
        title: String,
        body: String,
        fileName: String,
    ): FileRef = withContext(Dispatchers.IO) {
        check(initialized) { "PDFBox init failed; cannot create PDF." }

        PDDocument().use { doc ->
            val font = PDType1Font.HELVETICA
            val fontSize = 11f
            val leading = fontSize * 1.4f
            val margin = 50f

            var page = PDPage(PDRectangle.A4).also { doc.addPage(it) }
            var stream = PDPageContentStream(doc, page)
            val pageWidth = page.mediaBox.width
            val pageHeight = page.mediaBox.height
            val maxWidth = pageWidth - 2 * margin
            var y = pageHeight - margin

            // Title on the first page.
            val titleFont = PDType1Font.HELVETICA_BOLD
            val titleSize = 16f
            stream.beginText()
            stream.setFont(titleFont, titleSize)
            stream.newLineAtOffset(margin, y)
            stream.showText(title.replaceControlChars())
            stream.endText()
            y -= titleSize * 1.6f

            // Body — paragraph-wrap.
            for (paragraph in body.split("\n")) {
                if (paragraph.isBlank()) {
                    y -= leading
                    continue
                }
                val lines = wrapLine(paragraph, font, fontSize, maxWidth)
                for (line in lines) {
                    if (y < margin + leading) {
                        // Out of room → new page.
                        stream.close()
                        page = PDPage(PDRectangle.A4).also { doc.addPage(it) }
                        stream = PDPageContentStream(doc, page)
                        y = pageHeight - margin
                    }
                    stream.beginText()
                    stream.setFont(font, fontSize)
                    stream.newLineAtOffset(margin, y)
                    stream.showText(line.replaceControlChars())
                    stream.endText()
                    y -= leading
                }
            }
            stream.close()

            val outFile = File(context.cacheDir, sanitizeFileName(fileName))
            doc.save(outFile)
            FileRef(uri = Uri.fromFile(outFile).toString(), sizeBytes = outFile.length())
        }
    }

    /** Strip control chars PDFBox can't render — would otherwise throw. */
    private fun String.replaceControlChars(): String =
        this.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")

    /**
     * Word-wrap a single paragraph to fit [maxWidth] using the font's
     * actual glyph widths. Falls back to character-wrap for tokens
     * longer than a full line (very long URLs, etc.).
     */
    private fun wrapLine(
        text: String,
        font: PDType1Font,
        fontSize: Float,
        maxWidth: Float,
    ): List<String> {
        if (text.isBlank()) return listOf(text)
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            val widthPx = font.getStringWidth(candidate) / 1000f * fontSize
            if (widthPx <= maxWidth) {
                current.clear()
                current.append(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current.clear()
                }
                // Long-word fallback — break char by char.
                if (font.getStringWidth(word) / 1000f * fontSize > maxWidth) {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        val candidateChar = chunk.toString() + ch
                        if (font.getStringWidth(candidateChar) / 1000f * fontSize > maxWidth) {
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

    /**
     * 1-indexed page range parser. "1-5,7,10-12" → [1,2,3,4,5,7,10,11,12].
     * Clamped to `[1, total]` and capped at [cap] entries to avoid
     * runaway prompts when the user passes a huge range.
     */
    private fun resolvePageRange(spec: String?, total: Int, cap: Int): List<Int> {
        if (spec.isNullOrBlank()) {
            return (1..total.coerceAtMost(cap)).toList()
        }
        val pages = mutableListOf<Int>()
        for (chunk in spec.split(',')) {
            val trimmed = chunk.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.contains('-')) {
                val (a, b) = trimmed.split('-', limit = 2)
                val start = a.trim().toIntOrNull() ?: continue
                val end = b.trim().toIntOrNull() ?: continue
                for (p in start..end) pages += p
            } else {
                trimmed.toIntOrNull()?.let { pages += it }
            }
        }
        return pages.filter { it in 1..total }.distinct().take(cap)
    }

    private fun sanitizeFileName(raw: String): String {
        val clean = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "document" }
        return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
    }
}
