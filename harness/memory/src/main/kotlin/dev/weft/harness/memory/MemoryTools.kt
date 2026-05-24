package dev.weft.harness.memory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.AskKind
import dev.weft.contracts.UserAnswer
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable

/**
 * `memory_store` — the LLM asks the substrate to remember a fact about the
 * user across turns or sessions.
 *
 * Per [ADR-002](../../../../../../../docs/adr/ADR-002-explicit-memory-only.md)
 * the LLM is the only thing that decides what to remember. The substrate
 * adds two safety nets:
 *   - PII scan: if the content looks like it contains an SSN / card / phone /
 *     email, we redact it for display and ask the user "remember this?"
 *     before persisting.
 *   - Every stored memory is visible + deletable in AgentMemoriesScreen.
 */
public class MemoryStoreTool(
    ctx: WeftContext,
    private val store: MemoryStore,
    private val pii: PiiDetector = PiiDetector(),
) : WeftTool<MemoryStoreTool.Args, MemoryStoreTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "memory_store",
        description = "Persist a fact about the user so future turns + future sessions " +
            "can recall it. **Call this proactively** whenever the user reveals a " +
            "durable fact — don't wait to be asked. Triggers include: stated " +
            "preferences ('I prefer X', 'I like Y', 'I always Z'), identity / context " +
            "('my name is …', 'I work at …', 'I live in …', 'I have N kids'), " +
            "recurring patterns ('I usually …', 'every morning I …'), constraints " +
            "('I'm allergic to …', 'I don't eat …'), goals ('I'm trying to …'). " +
            "Skip only ephemeral task state (current question, today's to-do). " +
            "Pass scope='permanent' for facts that should survive app restarts; " +
            "'session' for facts only relevant to the current conversation. The user " +
            "sees and can delete every stored memory in the Memories screen, so " +
            "lean toward storing when in doubt.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "content",
                description = "The fact to remember, in a self-contained sentence (e.g., 'User prefers metric units').",
                type = ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "tags",
                description = "Optional short tags to group related memories (e.g., ['preferences', 'units']).",
                type = ToolParameterType.List(ToolParameterType.String),
            ),
            ToolParameterDescriptor(
                name = "scope",
                description = "'session' (default) or 'permanent'.",
                type = ToolParameterType.String,
            ),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    public data class Args(
        val content: String,
        val tags: List<String> = emptyList(),
        val scope: String = "session",
    )

    @Serializable
    public data class Result(
        val id: String,
        val stored: Boolean,
        val redactedPreview: String? = null,
        val cancelled: Boolean = false,
    )

    override suspend fun executeWeft(args: Args): Result {
        val parsedScope = when (args.scope.lowercase()) {
            "permanent" -> MemoryScope.PERMANENT
            "session", "" -> MemoryScope.SESSION
            else -> error("Invalid scope '${args.scope}'. Use 'session' or 'permanent'.")
        }

        val matches = pii.scan(args.content)
        if (matches.isNotEmpty()) {
            val redacted = pii.redact(args.content, matches)
            val answer = ui.askUser(
                question = "The agent wants to remember:\n\n$redacted\n\nThis looks like it might contain personal info. Store it?",
                kind = AskKind.YES_NO,
            )
            val approved = when (answer) {
                is UserAnswer.YesNo -> answer.value
                else -> false
            }
            if (!approved) {
                return Result(id = "", stored = false, redactedPreview = redacted, cancelled = true)
            }
        }

        val entry = store.store(content = args.content, tags = args.tags, scope = parsedScope)
        return Result(id = entry.id, stored = true)
    }
}

/**
 * `memory_recall` — search previously-stored memories by substring + tags.
 * Returns most-recent matches first.
 */
public class MemoryRecallTool(
    ctx: WeftContext,
    private val store: MemoryStore,
) : WeftTool<MemoryRecallTool.Args, MemoryRecallTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "memory_recall",
        description = "Search stored memories about the user. Pass a query string " +
            "(matched against content + tags) and/or a list of tags to filter by. " +
            "Returns up to 'limit' matches, newest first. Empty query + no tags " +
            "returns the most recent memories.",
        requiredParameters = emptyList(),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "query",
                description = "Free-text substring to search content+tags for. Case-insensitive.",
                type = ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                name = "tags",
                description = "Optional tags to filter by (matches if any tag matches).",
                type = ToolParameterType.List(ToolParameterType.String),
            ),
            ToolParameterDescriptor(
                name = "scope",
                description = "'session', 'permanent', or 'any' (default).",
                type = ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                name = "limit",
                description = "Max matches (1–${InMemoryMemoryStore.MAX_RECALL_LIMIT}). Defaults to 5.",
                type = ToolParameterType.Integer,
            ),
        ),
    ),
) {

    @Serializable
    public data class Args(
        val query: String = "",
        val tags: List<String> = emptyList(),
        val scope: String = "any",
        val limit: Int = 5,
    )

    @Serializable
    public data class Result(val matches: List<Match>) {
        @Serializable
        public data class Match(
            val id: String,
            val content: String,
            val tags: List<String>,
            val scope: String,
            val storedAtEpochMs: Long,
        )
    }

    override suspend fun executeWeft(args: Args): Result {
        val parsedScope = when (args.scope.lowercase()) {
            "session" -> MemoryScope.SESSION
            "permanent" -> MemoryScope.PERMANENT
            "any", "" -> MemoryScope.ANY
            else -> error("Invalid scope '${args.scope}'. Use 'session', 'permanent', or 'any'.")
        }
        val hits = store.recall(
            query = args.query,
            scope = parsedScope,
            tags = args.tags,
            limit = args.limit,
        )
        val now = System.currentTimeMillis()
        return Result(
            matches = hits.map {
                Result.Match(
                    id = it.id,
                    // Inline provenance so the LLM doesn't have to do epoch arithmetic
                    // to know whether a fact is fresh or stale. Format chosen to be
                    // short enough not to derail responses but explicit enough that
                    // "I told you yesterday" / "we agreed last week" lands correctly.
                    content = "(${labelProvenance(now, it.storedAtEpochMs)}) ${it.content}",
                    tags = it.tags,
                    scope = it.scope.name.lowercase(),
                    storedAtEpochMs = it.storedAtEpochMs,
                )
            },
        )
    }

}

/**
 * Shared age-to-label helper so [MemoryRecallTool] and [MemoryCompactTool]
 * format provenance the same way.
 */
internal const val MEM_MS_PER_MIN: Long = 60 * 1000L
internal const val MEM_MS_PER_HOUR: Long = 60 * MEM_MS_PER_MIN
internal const val MEM_MS_PER_DAY: Long = 24 * MEM_MS_PER_HOUR

internal fun labelProvenance(nowMs: Long, storedAtMs: Long): String {
    val ageMs = (nowMs - storedAtMs).coerceAtLeast(0)
    return when {
        ageMs < MEM_MS_PER_MIN -> "stored just now"
        ageMs < MEM_MS_PER_HOUR -> "stored ${ageMs / MEM_MS_PER_MIN}m ago"
        ageMs < MEM_MS_PER_DAY -> "stored ${ageMs / MEM_MS_PER_HOUR}h ago"
        ageMs < 7 * MEM_MS_PER_DAY -> "stored ${ageMs / MEM_MS_PER_DAY}d ago"
        else -> "stored on ${java.time.Instant.ofEpochMilli(storedAtMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()}"
    }
}

/**
 * `memory_compact` — consolidate duplicate / superseded memories.
 *
 * The LLM is the planner. It calls this tool with one or more **compact
 * actions**, each saying "fold this list of memory ids into this single
 * replacement content." The tool:
 *
 *   1. Validates every referenced id exists.
 *   2. Builds a redacted human-readable preview of the planned merges.
 *   3. Asks the user via `ui_ask` for blanket confirmation ("Apply these
 *      N consolidations?").
 *   4. On YES: deletes the source rows + writes the replacement, all
 *      tagged "compacted" so they're visible in the memory viewer.
 *   5. On NO / cancel: returns `cancelled=true`, no writes happen.
 *
 * This is a deliberate batch flow — the alternative (one ask per merge)
 * trains the user to rubber-stamp. One review of the full diff is more
 * honest. The summary the user sees is built from the *current* DB rows
 * via [MemoryRecallTool]-style provenance labels, not LLM-supplied
 * strings, so a confused agent can't quietly rewrite history.
 *
 * Always invoked from a recall-aware context — the LLM should `memory_recall`
 * first, decide what's redundant, then call `memory_compact` with the
 * specific ids. Asking the agent to compact "everything stale" without
 * surfacing concrete candidates is too vague; the tool requires explicit
 * actions for that reason.
 */
public class MemoryCompactTool(
    ctx: WeftContext,
    private val store: MemoryStore,
) : WeftTool<MemoryCompactTool.Args, MemoryCompactTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "memory_compact",
        description = "Consolidate duplicate or superseded memories. Pass a list of " +
            "actions; each names the memory ids to fold ('mergeIds') and the new " +
            "content that replaces them ('replacement'). The user sees the full plan " +
            "and confirms before anything is written. Use this only after calling " +
            "memory_recall to identify concrete redundant ids — do not invoke with " +
            "guesses. Returns the actions actually applied (or empty if the user " +
            "declined).",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "actions",
                description = "Non-empty list of {mergeIds: [string], replacement: string, " +
                    "tags?: [string], scope?: 'session'|'permanent'} objects.",
                // Nested action shape is described in the parameter
                // description above; we declare just `Object` here so Koog's
                // tool registry accepts free-shape JSON for each list element.
                // The Kotlin @Serializable Args.Action class does the
                // actual deserialization.
                type = ToolParameterType.List(
                    ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList()),
                ),
            ),
        ),
    ),
    sideEffecting = true,
) {

    @Serializable
    public data class Args(val actions: List<Action>) {
        @Serializable
        public data class Action(
            val mergeIds: List<String>,
            val replacement: String,
            val tags: List<String> = emptyList(),
            val scope: String = "permanent",
        )
    }

    @Serializable
    public data class Result(
        val applied: List<AppliedAction>,
        val cancelled: Boolean = false,
        val rejectedActions: List<String> = emptyList(),
    ) {
        @Serializable
        public data class AppliedAction(
            val newMemoryId: String,
            val replacedIds: List<String>,
        )
    }

    override suspend fun executeWeft(args: Args): Result {
        require(args.actions.isNotEmpty()) { "memory_compact needs at least one action" }

        // Resolve each action against the live store. An action is *valid*
        // only if every referenced id resolves to an existing memory; any
        // dangling id rejects the whole action (we don't half-apply).
        val livingIds: Map<String, MemoryEntry> = store.memories.value.associateBy { it.id }
        val validActions = mutableListOf<ResolvedAction>()
        val rejected = mutableListOf<String>()
        for ((idx, action) in args.actions.withIndex()) {
            if (action.mergeIds.isEmpty()) {
                rejected += "action[$idx]: empty mergeIds"
                continue
            }
            if (action.replacement.isBlank()) {
                rejected += "action[$idx]: blank replacement"
                continue
            }
            val resolved = action.mergeIds.map { id -> livingIds[id] }
            val missing = action.mergeIds.zip(resolved).filter { it.second == null }.map { it.first }
            if (missing.isNotEmpty()) {
                rejected += "action[$idx]: unknown ids ${missing.joinToString()}"
                continue
            }
            val scope = when (action.scope.lowercase()) {
                "session" -> MemoryScope.SESSION
                "permanent", "" -> MemoryScope.PERMANENT
                else -> {
                    rejected += "action[$idx]: invalid scope '${action.scope}'"
                    continue
                }
            }
            validActions += ResolvedAction(
                sources = resolved.filterNotNull(),
                replacement = action.replacement,
                tags = action.tags.distinct(),
                scope = scope,
            )
        }

        if (validActions.isEmpty()) {
            return Result(applied = emptyList(), rejectedActions = rejected)
        }

        // Build a human-readable confirmation preview from the LIVE memory
        // rows. We do NOT trust LLM-supplied summaries here — using the
        // current store content keeps the user honest about what's about
        // to disappear.
        val now = System.currentTimeMillis()
        val previewLines = mutableListOf<String>()
        previewLines += "The agent wants to consolidate ${validActions.size} memory " +
            (if (validActions.size == 1) "group:" else "groups:")
        previewLines += ""
        for ((i, va) in validActions.withIndex()) {
            previewLines += "${i + 1}. Replace ${va.sources.size} entries with:"
            previewLines += "   \"${va.replacement}\""
            previewLines += "   …folding in:"
            for (src in va.sources) {
                previewLines += "     • (${labelProvenance(now, src.storedAtEpochMs)}) ${src.content}"
            }
            previewLines += ""
        }
        previewLines += "Proceed with all consolidations?"

        val answer = ui.askUser(
            question = previewLines.joinToString("\n"),
            kind = AskKind.YES_NO,
        )
        val approved = when (answer) {
            is UserAnswer.YesNo -> answer.value
            else -> false
        }
        if (!approved) {
            return Result(applied = emptyList(), cancelled = true, rejectedActions = rejected)
        }

        // Apply: write replacement first, then delete sources. If a delete
        // fails mid-loop the replacement still exists — surfaced + visible
        // in the memory viewer, no orphan source rows referenced anywhere.
        val applied = mutableListOf<Result.AppliedAction>()
        for (va in validActions) {
            val entry = store.store(
                content = va.replacement,
                tags = (va.tags + "compacted").distinct(),
                scope = va.scope,
            )
            for (src in va.sources) {
                runCatching { store.delete(src.id) }
            }
            applied += Result.AppliedAction(
                newMemoryId = entry.id,
                replacedIds = va.sources.map { it.id },
            )
        }
        return Result(applied = applied, rejectedActions = rejected)
    }

    private data class ResolvedAction(
        val sources: List<MemoryEntry>,
        val replacement: String,
        val tags: List<String>,
        val scope: MemoryScope,
    )
}
