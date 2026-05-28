package dev.weft.harness.observability

/**
 * Applies regex-based redaction to trace previews before they hit the
 * [TraceStore]. The default rule set masks the patterns most likely to
 * leak through a tool call's args / result preview:
 *
 *   - email addresses
 *   - US-style SSNs
 *   - HTTP `Authorization: Bearer …` tokens
 *   - Anthropic-shape API keys (`sk-…`)
 *   - common credit-card layouts (PAN with optional `-` / ` ` separators)
 *
 * Apps can pass a custom [rules] list to add domain-specific masks
 * (employee IDs, internal URLs, etc.) without losing the defaults; the
 * recommended pattern is `Redactor(DEFAULT_RULES + listOf(myRule))`.
 *
 * The redactor is intentionally **stupid**: it runs every rule on every
 * preview, no language awareness, no JSON parsing. False positives are
 * acceptable in trace previews (it's better to over-mask than to leak);
 * if you need surgical PII handling that's the job of [PiiDetector] in
 * `:harness:memory`, which is applied before the LLM stores anything.
 */
class Redactor(
    val rules: List<Rule> = DEFAULT_RULES,
) {
    data class Rule(
        /** Short identifier — appears in the replacement marker. */
        val name: String,
        val pattern: Regex,
        /**
         * Replacement string. Supports regex back-refs like `$1` if the
         * pattern uses groups. When null, the rule's [name] is used as
         * `[REDACTED:<name>]`.
         */
        val replacement: String? = null,
    )

    /**
     * Apply every rule in order. The result of one rule is fed to the
     * next, so overlapping patterns compose (a Bearer-token line is
     * unrecognizable after the bearer rule fires and won't be re-matched
     * by the api-key rule).
     */
    fun redact(text: String): String {
        if (text.isEmpty() || rules.isEmpty()) return text
        var s = text
        for (rule in rules) {
            val repl = rule.replacement ?: "[REDACTED:${rule.name}]"
            s = rule.pattern.replace(s, repl)
        }
        return s
    }

    /** Convenience: null in → null out. */
    fun redactNullable(text: String?): String? = text?.let(::redact)

    companion object {
        /**
         * Sensible defaults. Apps that want zero redaction can pass an
         * empty [rules] list to the constructor.
         */
        val DEFAULT_RULES: List<Rule> = listOf(
            Rule(
                name = "email",
                pattern = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""),
            ),
            Rule(
                name = "ssn",
                pattern = Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
            ),
            Rule(
                name = "bearer",
                pattern = Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]{8,}"""),
            ),
            Rule(
                name = "api-key",
                // Anthropic-style + common provider prefixes (sk-…, sk-ant-…, sk_live_…).
                pattern = Regex("""\bsk[-_][A-Za-z0-9_\-]{16,}\b"""),
            ),
            Rule(
                name = "credit-card",
                // 13-19 digit PAN with optional separators. Greedy enough to
                // catch typical formats without matching long incidental digit runs
                // (the {3,5} on the last group keeps it bounded).
                pattern = Regex("""\b(?:\d[ \-]?){12,15}\d\b"""),
            ),
        )
    }
}
