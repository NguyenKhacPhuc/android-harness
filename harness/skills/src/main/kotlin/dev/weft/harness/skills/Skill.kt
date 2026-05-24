package dev.weft.harness.skills

/**
 * A skill — user-typed input the app handles directly, bypassing the LLM.
 *
 * Skills are invoked with a leading `/` (e.g. `/note buy milk`). The slash
 * is a UX convention borrowed from CLIs and Slack-style chat clients; the
 * *abstraction* the substrate offers is "skill," not "slash command." Apps
 * can keep, customize, or hide the slash trigger as they see fit — the
 * registry only cares about parsing `name` + `payload` out of the input.
 *
 * Same role as a [dev.weft.tools.WeftTool], but for *app* code instead
 * of *agent* code. Tools are JSON-shaped, LLM-callable, validated. Skills
 * are string-shaped, user-callable, app-validated. Both end up writing to
 * the same backing stores ([dev.weft.contracts.DataSource],
 * `os.notifications`, etc.) — the difference is who initiates the call and
 * how the args arrive.
 *
 * Why this exists as a primitive rather than each app rolling its own:
 *   - **Discoverability.** `[+]` quick-actions menus and the `/help` command
 *     both iterate the registry, so a single skill definition powers both
 *     UX paths.
 *   - **Symmetry.** Developers already know the [WeftTool] shape; this
 *     mirrors it for app-initiated work.
 *   - **Future extensibility.** Adding fuzzy match, "did you mean" suggestions,
 *     subcommand dispatch, etc. happens once in the registry, not once per app.
 */
public data class Skill(
    /** Lowercase, no leading `/`. The canonical form shown in `/help`. */
    val name: String,
    /** Optional shorthands. `/n` for `/note`, `/t` for `/task`, etc. */
    val aliases: List<String> = emptyList(),
    /** One-line description shown in `/help` + quick-action menus. */
    val description: String,
    /** Usage example, e.g. `"/note <text>"`. Surfaced when the user calls `/help`. */
    val usage: String? = null,
    /**
     * The actual work. Receives the payload (everything after the skill
     * name + space) trimmed. Throw or return [SkillResult.Fail] to surface
     * a failure bubble in chat.
     */
    val execute: suspend (payload: String) -> SkillResult,
) {
    init {
        require(name.isNotBlank()) { "Skill name must not be blank" }
        require(!name.startsWith("/")) { "Skill name must not include leading '/'" }
        require(name.none { it.isWhitespace() }) { "Skill name must not contain whitespace: '$name'" }
        aliases.forEach { a ->
            require(a.isNotBlank() && !a.startsWith("/") && a.none { it.isWhitespace() }) {
                "Invalid alias: '$a'"
            }
        }
    }
}

/**
 * Outcome of a [Skill] execution. Translated into chat bubbles by the
 * substrate's `ChatScreen` (and any other UI host).
 */
public sealed class SkillResult {
    /** Skill succeeded. [message] becomes a tool-done bubble. */
    public data class Ok(val message: String) : SkillResult()

    /** Skill failed. [message] becomes a tool-fail bubble. */
    public data class Fail(val message: String) : SkillResult()
}

/**
 * Lookup + dispatch for [Skill]s. Built once at app startup, passed to
 * `ChatScreen`. Aliases and names share one namespace — registering `/n`
 * as an alias of `/note` AND another skill named `/n` is rejected.
 *
 * **No auto-injection.** The registry only contains skills the caller
 * passed in. The substrate's stance: OS-capability tools (notifications,
 * calendar, files, etc.) auto-inject because they're universal; UI
 * affordances like skills are app product decisions, so they opt-in
 * explicitly. If you want a `/help` skill that lists everything, call
 * [withHelp]:
 *
 * ```kotlin
 * SkillRegistry(mySkills).withHelp()
 * ```
 */
public class SkillRegistry(
    public val skills: List<Skill>,
) {
    private val byName: Map<String, Skill> = buildIndex(skills)

    /**
     * Parse `input` and return the matched skill + payload, or null when
     * the input isn't a `/skill` we recognize. Returning null is the signal
     * for `ChatScreen` to fall through to the normal LLM path. Unknown
     * `/foo` inputs also return null (treated as regular chat text) — apps
     * can override by registering a catch-all if they want different
     * behavior.
     */
    public fun resolve(input: String): Match? {
        if (!input.startsWith("/")) return null
        val rest = input.drop(1)
        if (rest.isEmpty()) return null
        val space = rest.indexOf(' ')
        val rawName = (if (space < 0) rest else rest.substring(0, space)).lowercase()
        val payload = if (space < 0) "" else rest.substring(space + 1).trim()
        val skill = byName[rawName] ?: return null
        return Match(skill, payload)
    }

    /** Every registered skill, including the synthesized `/help` if enabled. */
    public fun all(): List<Skill> = skills

    public data class Match(val skill: Skill, val payload: String)

    private companion object {
        fun buildIndex(skills: List<Skill>): Map<String, Skill> {
            val out = mutableMapOf<String, Skill>()
            for (skill in skills) {
                val keys = listOf(skill.name.lowercase()) + skill.aliases.map { it.lowercase() }
                for (key in keys) {
                    require(key !in out) {
                        "Duplicate skill identifier '/$key' (used by " +
                            "'${out[key]?.name}' and '${skill.name}')"
                    }
                    out[key] = skill
                }
            }
            return out
        }
    }
}

/**
 * Opt-in `/help` skill. Returns a NEW [SkillRegistry] with a synthesized
 * `/help` (alias `/?`) appended — it iterates the source registry's skills
 * and prints names, aliases, descriptions, and usage strings.
 *
 * Pulled out as an extension (not auto-injected) so apps that prefer "only
 * what I registered ends up in the chat" can omit it. Apps that want
 * CLI-style discoverability chain `.withHelp()` at the end of their
 * builder.
 */
public fun SkillRegistry.withHelp(): SkillRegistry {
    val existing = this.skills
    val helpSkill = Skill(
        name = "help",
        aliases = listOf("?"),
        description = "List available skills.",
        usage = "/help",
        execute = { _ ->
            val lines = buildList {
                add("Available skills:")
                for (s in existing.sortedBy { it.name }) {
                    val aliasPart = if (s.aliases.isEmpty()) "" else
                        " (also: " + s.aliases.joinToString(", ") { "/$it" } + ")"
                    val usagePart = if (s.usage != null) "  — usage: ${s.usage}" else ""
                    add("  /${s.name}$aliasPart — ${s.description}$usagePart")
                }
                add("  /help (also: /?) — list these skills.")
            }
            SkillResult.Ok(lines.joinToString("\n"))
        },
    )
    return SkillRegistry(existing + helpSkill)
}
