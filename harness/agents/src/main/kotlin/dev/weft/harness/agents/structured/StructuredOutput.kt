package dev.weft.harness.agents.structured

import dev.weft.harness.agents.WeftAgent

/**
 * Schema descriptor for structured-output requests on [WeftAgent.sendStructured].
 *
 * v1 implementation is provider-neutral: the schema is embedded in the
 * user prompt as an instruction, the model's reply is parsed via
 * [decoder], and parse failures retry up to `maxRetries` with explicit
 * feedback. When [dev.weft.harness.agents.routing.ModelCapabilities.supportsStructuredOutput]
 * lands a follow-up can switch to the provider's native JSON-schema mode
 * for the same call shape.
 *
 * @param T the Kotlin type the decoder yields. Apps typically use a
 *   `@Serializable` data class plus `Json.decodeFromString` as the
 *   decoder.
 * @property schemaDescription a human-readable JSON-schema-shaped string
 *   that goes into the prompt verbatim. Hand-written JSON Schema works;
 *   `Json.encodeToString(JsonSchema.serializer(), schema)` works too.
 *   Keep it tight — long schemas waste tokens and reduce adherence.
 * @property decoder parses the model's reply into [T]. Throw on any
 *   structural mismatch — the agent retries with the exception's
 *   `message` appended to the next prompt.
 */
public data class StructuredOutputSchema<T>(
    public val schemaDescription: String,
    public val decoder: (String) -> T,
)

/**
 * Outcome of [WeftAgent.sendStructured]. [Success] carries the decoded
 * value; [Failed] carries the raw final reply plus the chain of parse
 * errors so the caller can decide whether to surface to the user or
 * give up.
 */
public sealed class StructuredOutputResult<out T> {
    public data class Success<T>(public val value: T, public val raw: String) : StructuredOutputResult<T>()
    public data class Failed(
        public val rawFinal: String,
        public val errors: List<String>,
    ) : StructuredOutputResult<Nothing>()
}

/**
 * Send a turn that expects a parseable response. Appends a schema
 * instruction to [userText], runs [WeftAgent.send], parses the reply
 * with [schema]'s decoder, and on parse failure retries up to
 * [maxRetries] additional turns with the parse error fed back to the
 * model.
 *
 * Returns [StructuredOutputResult.Success] with the decoded value on
 * first success, or [StructuredOutputResult.Failed] when every attempt
 * fails to parse. Quota / network / breaker errors propagate as
 * exceptions — only PARSE failures are absorbed into the result.
 *
 * Provider-neutral: works against any model regardless of native
 * structured-output support. When the active model's
 * [dev.weft.harness.agents.routing.ModelCapabilities.supportsStructuredOutput]
 * is true a future overload may bypass the retry loop and use the
 * provider's native schema validation.
 */
public suspend fun <T> WeftAgent.sendStructured(
    userText: String,
    schema: StructuredOutputSchema<T>,
    maxRetries: Int = 2,
): StructuredOutputResult<T> {
    require(maxRetries >= 0) { "maxRetries must be ≥ 0" }

    val errors = mutableListOf<String>()
    var prompt = buildInitialPrompt(userText, schema.schemaDescription)
    var lastReply = ""

    repeat(maxRetries + 1) { attempt ->
        val reply = send(prompt)
        lastReply = reply
        val candidate = extractJsonPayload(reply)
        try {
            val decoded = schema.decoder(candidate)
            return StructuredOutputResult.Success(decoded, reply)
        } catch (t: Throwable) {
            errors += "attempt ${attempt + 1}: ${t.message ?: t::class.simpleName.orEmpty()}"
            if (attempt == maxRetries) {
                return StructuredOutputResult.Failed(rawFinal = reply, errors = errors.toList())
            }
            prompt = buildRetryPrompt(t.message ?: "JSON parse failure", schema.schemaDescription)
        }
    }
    // Unreachable: the repeat block always returns on the last attempt.
    return StructuredOutputResult.Failed(rawFinal = lastReply, errors = errors.toList())
}

private fun buildInitialPrompt(userText: String, schema: String): String = buildString {
    append(userText)
    append("\n\n")
    append("Respond with ONLY a JSON object matching this schema. ")
    append("No prose before or after, no markdown fences. ")
    append("If a field is unknown, omit it rather than guessing.\n\n")
    append("Schema:\n")
    append(schema)
}

private fun buildRetryPrompt(parseError: String, schema: String): String = buildString {
    append("Your previous reply could not be parsed as JSON matching the schema.\n")
    append("Parser error: ")
    append(parseError)
    append("\n\nReturn ONLY a corrected JSON object matching this schema. ")
    append("No prose, no markdown fences.\n\n")
    append("Schema:\n")
    append(schema)
}

/**
 * Pull the JSON payload out of a model reply. Strips common Markdown
 * fences (```json … ```), leading "Here is the JSON:" prose, and
 * trailing commentary that some models append despite instructions.
 *
 * Falls back to the raw reply when no fence is detected — the decoder
 * will throw and the retry loop feeds the error back.
 */
private fun extractJsonPayload(reply: String): String {
    val trimmed = reply.trim()
    val fenceStart = trimmed.indexOf("```")
    if (fenceStart == -1) return trimmed
    // Find the matching closing fence after the opener line.
    val afterOpener = trimmed.indexOf('\n', startIndex = fenceStart)
    if (afterOpener == -1) return trimmed
    val fenceEnd = trimmed.indexOf("```", startIndex = afterOpener + 1)
    val inner = if (fenceEnd == -1) trimmed.substring(afterOpener + 1)
    else trimmed.substring(afterOpener + 1, fenceEnd)
    return inner.trim()
}
