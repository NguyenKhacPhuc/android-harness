package dev.weft.harness.memory

/**
 * Detects common PII patterns in text the LLM wants to persist.
 *
 * Per [ADR-002](../../../../../../../docs/adr/ADR-002-explicit-memory-only.md):
 * memory storage is explicit (LLM-initiated). But the LLM is fallible — it
 * might try to store a phone number, SSN-like sequence, or card number it
 * inferred from chat. We surface those matches so the substrate can ask the
 * user "the agent wants to remember: '…[REDACTED-CC]…' — confirm?" before
 * persisting.
 *
 * Patterns are deliberately conservative (low recall, low false-positive)
 * rather than exhaustive. False negatives are acceptable — this is a
 * convenience gate, not a privacy guarantee.
 */
public class PiiDetector(
    private val patterns: List<PiiPattern> = DEFAULT_PATTERNS,
) {

    public fun scan(text: String): List<PiiMatch> {
        if (text.isBlank()) return emptyList()
        return patterns.flatMap { pattern ->
            pattern.regex.findAll(text).map { match ->
                PiiMatch(
                    kind = pattern.kind,
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    raw = match.value,
                )
            }
        }
    }

    /** Replace every match with `[REDACTED-<kind>]` for safe display in a confirmation prompt. */
    public fun redact(text: String, matches: List<PiiMatch> = scan(text)): String {
        if (matches.isEmpty()) return text
        val sorted = matches.sortedByDescending { it.start }
        val sb = StringBuilder(text)
        for (m in sorted) {
            sb.replace(m.start, m.endExclusive, "[REDACTED-${m.kind.name}]")
        }
        return sb.toString()
    }

    public companion object {
        public val DEFAULT_PATTERNS: List<PiiPattern> = listOf(
            // US SSN: 3-2-4 digits, common separators
            PiiPattern(
                kind = PiiKind.SSN,
                regex = Regex("""\b\d{3}[-\s]\d{2}[-\s]\d{4}\b"""),
            ),
            // 13–19 digit sequences with optional spaces/dashes — covers Visa/MC/Amex/Discover
            PiiPattern(
                kind = PiiKind.CREDIT_CARD,
                regex = Regex("""\b(?:\d[ -]*?){13,19}\b"""),
            ),
            // E.164-ish phones: +country code + 7-15 digits, or US-style (xxx) xxx-xxxx
            PiiPattern(
                kind = PiiKind.PHONE,
                regex = Regex("""(\+\d{1,3}[\s\-]?)?\(?\d{3}\)?[\s\-]?\d{3}[\s\-]?\d{4}\b"""),
            ),
            // Emails
            PiiPattern(
                kind = PiiKind.EMAIL,
                regex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
            ),
        )
    }
}

public data class PiiPattern(val kind: PiiKind, val regex: Regex)

public data class PiiMatch(
    val kind: PiiKind,
    val start: Int,
    val endExclusive: Int,
    val raw: String,
)

public enum class PiiKind {
    SSN,
    CREDIT_CARD,
    PHONE,
    EMAIL,
}
