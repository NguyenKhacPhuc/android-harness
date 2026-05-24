package dev.weft.harness.prompt.multimodal

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart

/**
 * One turn's worth of user input — text plus any image / audio / video /
 * file attachments. The agent's strategy unpacks this into a single user
 * [ai.koog.prompt.message.Message.User] message with mixed parts, which
 * is how Anthropic and OpenAI expect multimodal content (one message,
 * many `content` blocks).
 *
 * For text-only turns, use [text] or pass `attachments = emptyList()` —
 * the strategy collapses that down to the same shape as a string-only
 * send. Existing callers don't need to change anything.
 *
 * The [attachments] list is `MessagePart.Attachment` (Koog's type)
 * directly. The substrate's [Attachments] object provides ergonomic
 * factories for the common cases (image bytes, image URL, PDF file) so
 * callers don't need to assemble [AttachmentSource] / [AttachmentContent]
 * by hand. Power users can construct attachments directly when they
 * need a less-common shape (audio base64, custom MIME types, etc.).
 *
 * **Persistence (v1):** attachments are NOT persisted across app
 * restarts — only the text portion of each turn is written to the
 * `ConversationStore`. Resuming a conversation will replay the text but
 * not the attached media. A future schema bump will add a side table
 * for blob refs.
 */
public data class WeftUserInput(
    public val text: String,
    public val attachments: List<MessagePart.Attachment> = emptyList(),
) {
    public companion object {
        /** Convenience for the common text-only case. */
        public fun text(text: String): WeftUserInput = WeftUserInput(text, emptyList())
    }
}

/**
 * Ergonomic factories for the common attachment shapes. Each returns a
 * ready-to-add [MessagePart.Attachment] so callers can do:
 *
 * ```kotlin
 * agent.send(
 *     userText = "Describe this picture.",
 *     attachments = listOf(Attachments.imageBytes(bytes, format = "jpg")),
 * )
 * ```
 *
 * Format strings match Anthropic's `media_type` conventions ("jpg",
 * "png", "webp", "gif" for images; "mp3", "wav" for audio; "mp4" for
 * video; "pdf" / "txt" for documents). The MIME type defaults derive
 * from the format — override for non-standard cases.
 */
public object Attachments {
    /** Image from in-memory bytes. */
    public fun imageBytes(
        bytes: ByteArray,
        format: String,
        mimeType: String = "image/$format",
        fileName: String? = null,
    ): MessagePart.Attachment = MessagePart.Attachment(
        AttachmentSource.Image(
            content = AttachmentContent.Binary.Bytes(bytes),
            format = format,
            mimeType = mimeType,
            fileName = fileName,
        ),
    )

    /** Image referenced by URL (the provider fetches it). Mostly useful
     *  for assets already hosted somewhere reachable; on Android the
     *  bytes path is usually simpler. */
    public fun imageUrl(
        url: String,
        format: String,
        mimeType: String = "image/$format",
        fileName: String? = null,
    ): MessagePart.Attachment = MessagePart.Attachment(
        AttachmentSource.Image(
            content = AttachmentContent.URL(url),
            format = format,
            mimeType = mimeType,
            fileName = fileName,
        ),
    )

    /** PDF or other binary document from in-memory bytes. */
    public fun fileBytes(
        bytes: ByteArray,
        format: String,
        mimeType: String,
        fileName: String? = null,
    ): MessagePart.Attachment = MessagePart.Attachment(
        AttachmentSource.File(
            content = AttachmentContent.Binary.Bytes(bytes),
            format = format,
            mimeType = mimeType,
            fileName = fileName,
        ),
    )

    /** Audio attachment from in-memory bytes. */
    public fun audioBytes(
        bytes: ByteArray,
        format: String,
        mimeType: String = "audio/$format",
        fileName: String? = null,
    ): MessagePart.Attachment = MessagePart.Attachment(
        AttachmentSource.Audio(
            content = AttachmentContent.Binary.Bytes(bytes),
            format = format,
            mimeType = mimeType,
            fileName = fileName,
        ),
    )
}
