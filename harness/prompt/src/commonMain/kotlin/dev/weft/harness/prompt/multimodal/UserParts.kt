package dev.weft.harness.prompt.multimodal

import ai.koog.prompt.message.MessagePart

/**
 * Build the list of [MessagePart.RequestPart]s that make up a single
 * user message, given [input]. Text first (matches Anthropic's
 * convention of "context, then media"), attachments after. Blank text
 * is dropped — an empty user message with only attachments is valid on
 * Anthropic and OpenAI vision APIs, and Koog passes through cleanly.
 *
 * Used by the agent module's strategy nodes (both non-streaming
 * `weftSingleRunStrategy` and `streamingSingleRunStrategy`) so the user
 * message has the same shape across both paths.
 */
fun buildUserParts(input: WeftUserInput): List<MessagePart.RequestPart> = buildList {
    if (input.text.isNotBlank()) add(MessagePart.Text(input.text))
    addAll(input.attachments)
}
