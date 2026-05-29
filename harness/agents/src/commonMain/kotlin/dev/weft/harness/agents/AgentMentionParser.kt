package dev.weft.harness.agents

/**
 * Parser for the `@<agentName> ...` mention syntax that lets users
 * address a specific agent directly from the chat input.
 *
 * The substrate exposes the primitive; host chat surfaces decide what
 * to do with the parsed pair. The default Undercurrent integration
 * pipes [Mention.agentName] into `runtime.buildAgent(agentName, ...)`
 * and treats the rest of the text as the user message body.
 *
 * Grammar:
 *   - `@name body` → ("name", "body")
 *   - `@name` (no body) → ("name", "")
 *   - `  @name body  ` → ("name", "body") (leading/trailing whitespace stripped)
 *   - `body without mention` → (null, "body without mention")
 *   - `@@name body` → (null, "@@name body") (escaped — literal `@`)
 *   - `@name-with-dashes body` → ("name-with-dashes", "body")
 *   - `@name@something body` → (null, "@name@something body") (no inner `@`)
 *
 * Name shape matches [AgentDeclaration.name] validation: lowercase
 * letters, digits, hyphen, underscore. Anything else fails to match
 * and the whole input is treated as body.
 */
object AgentMentionParser {

    // `(?s)` inline flag enables DOTALL/DOT_MATCHES_ALL — needed so the
    // body `(.*)` can span multiple lines. `RegexOption.DOT_MATCHES_ALL`
    // is JVM-only; the inline flag is portable to K/N.
    private val MENTION_REGEX = Regex("(?s)^@([a-z0-9_-]+)(?:\\s+(.*))?$")

    /**
     * Parse [input] into an optional agent name and the body text. The
     * agent name is NOT validated against any registry — the caller
     * decides what to do with an unknown name (typical: fall back to
     * the default agent and prepend an error to the chat).
     */
    fun parse(input: String): Mention {
        val trimmed = input.trim()
        // Escape sequence: `@@` at the start = literal text.
        if (trimmed.startsWith("@@")) {
            return Mention(agentName = null, body = trimmed)
        }
        val match = MENTION_REGEX.matchEntire(trimmed)
            ?: return Mention(agentName = null, body = trimmed)
        val name = match.groupValues[1]
        val body = match.groupValues.getOrNull(2).orEmpty().trim()
        return Mention(agentName = name, body = body)
    }
}

/**
 * Result of parsing a chat input for an agent mention. When
 * [agentName] is null, the input had no recognizable mention and
 * [body] equals the trimmed input.
 */
data class Mention(
    val agentName: String?,
    val body: String,
)
