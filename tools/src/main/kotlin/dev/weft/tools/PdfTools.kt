package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.FileRef
import dev.weft.contracts.PdfRenderResult
import dev.weft.contracts.PdfTextResult
import kotlinx.serialization.Serializable

/**
 * Extract plain text from a PDF. The most common use case: "summarize
 * this PDF" / "what does the contract say about X?". The agent calls
 * this on a URI the user has provided (paste, attachment, share-intent).
 *
 * Returns the extracted text plus the page count and which pages were
 * actually included. Cap at [Args.maxPages] (default 50) to keep the
 * context window from drowning on huge documents — for those, the
 * agent should call again with an explicit [Args.pageRange].
 */
class PdfReadTool(ctx: WeftContext) : WeftTool<PdfReadTool.Args, PdfTextResult>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<PdfTextResult>(),
    descriptor = ToolDescriptor(
        name = "pdf_read",
        description = "Extract plain text from a PDF. Pass the URI " +
            "(content:// or file://) and optionally a 1-indexed page range " +
            "like '1-5,7,10-12'. Returns the extracted text plus the " +
            "total page count and which pages were included.",
        requiredParameters = listOf(
            ToolParameterDescriptor("uri", "Content URI of the PDF.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "pageRange",
                "1-indexed page selector, e.g. '1-5,7,10-12'. Default: all pages up to maxPages.",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "maxPages",
                "Cap on pages extracted when pageRange is omitted (default 50).",
                ToolParameterType.Integer,
            ),
        ),
    ),
) {
    @Serializable
    data class Args(
        val uri: String,
        val pageRange: String? = null,
        val maxPages: Int = 50,
    )

    override suspend fun executeWeft(args: Args): PdfTextResult =
        os.pdf.extractText(uri = args.uri, pageRange = args.pageRange, maxPages = args.maxPages)
}

/**
 * Render specific pages of a PDF as PNG bitmaps. Useful when the source
 * PDF has no text layer (scanned documents) and the agent wants to OCR
 * each page via `vision_ocr`. Returns one file URI per rendered page.
 *
 * Pages are 1-indexed. Omit [Args.pages] to render every page (capped
 * inside the renderer). [Args.scale] = 1.5 is roughly 108 DPI; higher
 * values make OCR more accurate at the cost of memory.
 */
class PdfRenderPagesTool(ctx: WeftContext) : WeftTool<PdfRenderPagesTool.Args, PdfRenderResult>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<PdfRenderResult>(),
    descriptor = ToolDescriptor(
        name = "pdf_render_pages",
        description = "Render specific PDF pages as PNG images. Useful for " +
            "scanned PDFs (no text layer) — pair with vision_ocr. " +
            "Pages are 1-indexed. Returns one file URI per rendered page.",
        requiredParameters = listOf(
            ToolParameterDescriptor("uri", "Content URI of the PDF.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "pages",
                "1-indexed page numbers, JSON array like [1,2,5]. Omit for all pages.",
                ToolParameterType.List(ToolParameterType.Integer),
            ),
            ToolParameterDescriptor(
                "scale",
                "Render scale (1.0 ≈ 72 DPI). Default 1.5. Larger = better OCR, more memory.",
                ToolParameterType.Float,
            ),
        ),
    ),
) {
    @Serializable
    data class Args(
        val uri: String,
        val pages: List<Int>? = null,
        val scale: Float = 1.5f,
    )

    override suspend fun executeWeft(args: Args): PdfRenderResult =
        os.pdf.renderPages(uri = args.uri, pages = args.pages, scale = args.scale)
}

/**
 * Create a new PDF from a text body with a title. The agent calls this
 * for "save this as a PDF" / "export the conversation as a PDF" style
 * requests. Returns a [FileRef] the agent can hand to `files_share` or
 * `files_save` to actually deliver to the user.
 *
 * Layout is intentionally simple — single Helvetica font, paragraph
 * wrapping, auto page breaks. No markdown rendering (yet); the body
 * is treated as plain text with `\n` paragraph separators.
 */
class PdfCreateTool(ctx: WeftContext) : WeftTool<PdfCreateTool.Args, FileRef>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<FileRef>(),
    descriptor = ToolDescriptor(
        name = "pdf_create",
        description = "Create a new PDF from plain text. Pass a title and " +
            "the body text (paragraphs separated by '\\n'). Returns a " +
            "FileRef pointing at the saved PDF in the app's cache dir.",
        requiredParameters = listOf(
            ToolParameterDescriptor("title", "Title shown at the top of page 1.", ToolParameterType.String),
            ToolParameterDescriptor("body", "Body text. Paragraphs separated by \\n.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "fileName",
                "Output filename (default 'document.pdf'). Sanitized for filesystem safety.",
                ToolParameterType.String,
            ),
        ),
    ),
    sideEffecting = true,
) {
    @Serializable
    data class Args(
        val title: String,
        val body: String,
        val fileName: String = "document.pdf",
    )

    override suspend fun executeWeft(args: Args): FileRef =
        os.pdf.create(title = args.title, body = args.body, fileName = args.fileName)
}
