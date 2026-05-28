package dev.weft.contracts

/**
 * Lifecycle interception for the agent loop. Equivalent to Claude Code's
 * `PreToolUse / PostToolUse / Stop / UserPromptSubmit` hooks — the same
 * surface that drives lint/format/notify integrations there.
 *
 * Every callback defaults to a no-op so apps implement only what they
 * need. Hooks run in registration order; pre-tool hooks short-circuit
 * on the first [HookDecision.Deny].
 *
 * Lifecycle:
 *  - [onUserMessage] — before any LLM call, with the raw user input.
 *  - [onTurnStart]   — after quota + trace start, before the LLM call.
 *  - [onToolStart]   — before a tool's `execute` runs. Can deny.
 *  - [onToolEnd]     — after a tool returns successfully.
 *  - [onToolFailed]  — after a tool throws (denial included).
 *  - [onTurnEnd]     — after the assistant reply is committed to history.
 *  - [onTurnFailed]  — after the turn raised (network, quota, etc.).
 *
 * Hooks run on the same coroutine as the agent — keep them fast and
 * non-blocking. Long work should dispatch onto a separate scope.
 */
interface WeftHook {

    suspend fun onUserMessage(ctx: HookContext.UserMessage) {}
    suspend fun onTurnStart(ctx: HookContext.TurnStart) {}
    suspend fun onTurnEnd(ctx: HookContext.TurnEnd) {}
    suspend fun onTurnFailed(ctx: HookContext.TurnFailed) {}

    /**
     * Pre-tool hook. Returning [HookDecision.Deny] aborts the tool
     * before its `execute` runs — the LLM sees the denial as a tool
     * error and chooses how to recover. Returning [HookDecision.Continue]
     * (the default) lets the tool proceed.
     */
    suspend fun onToolStart(ctx: HookContext.ToolStart): HookDecision = HookDecision.Continue

    suspend fun onToolEnd(ctx: HookContext.ToolEnd) {}
    suspend fun onToolFailed(ctx: HookContext.ToolFailed) {}
}

/**
 * Inputs available to a [WeftHook] callback. Each variant carries only
 * the fields relevant for its lifecycle point — keep the surface tight
 * so hooks stay easy to write.
 */
sealed class HookContext {
    /** Trace id this event belongs to. Shared across all hooks for one turn. */
    abstract val traceId: String
    /** Conversation id this turn belongs to. */
    abstract val conversationId: String

    data class UserMessage(
        override val traceId: String,
        override val conversationId: String,
        val text: String,
        val hasAttachments: Boolean,
    ) : HookContext()

    data class TurnStart(
        override val traceId: String,
        override val conversationId: String,
        val userText: String,
        val modelId: String,
    ) : HookContext()

    data class TurnEnd(
        override val traceId: String,
        override val conversationId: String,
        val assistantText: String,
        val modelId: String,
    ) : HookContext()

    data class TurnFailed(
        override val traceId: String,
        override val conversationId: String,
        val cause: Throwable,
    ) : HookContext()

    data class ToolStart(
        override val traceId: String,
        override val conversationId: String,
        val toolName: String,
        val argsPreview: String,
        val risk: ToolRisk,
    ) : HookContext()

    data class ToolEnd(
        override val traceId: String,
        override val conversationId: String,
        val toolName: String,
        val resultPreview: String?,
    ) : HookContext()

    data class ToolFailed(
        override val traceId: String,
        override val conversationId: String,
        val toolName: String,
        val message: String,
    ) : HookContext()
}

/** Pre-tool hook outcome. */
sealed class HookDecision {
    /** Tool proceeds normally. */
    data object Continue : HookDecision()

    /**
     * Tool is aborted before [executeWeft][dev.weft.tools.WeftTool.executeWeft]
     * runs. The [reason] is surfaced to the LLM as the tool's error
     * message and to the chat UI as a `ToolEvent.Failed`.
     */
    data class Deny(val reason: String) : HookDecision()
}

/** Raised inside `WeftTool.execute` when a pre-tool hook returns [HookDecision.Deny]. */
class HookDeniedException(
    val toolName: String,
    val reason: String,
) : RuntimeException("Tool '$toolName' denied by hook: $reason")

/**
 * Aggregates a list of [WeftHook]s into a single dispatcher. Both the
 * agent loop and the tool gates hold a reference and fan out lifecycle
 * events to every registered hook.
 *
 * Dispatch order is registration order. Pre-tool hooks short-circuit on
 * the first [HookDecision.Deny] — later hooks do not run for that tool
 * call. Observation-only callbacks (`onTurnStart`, `onToolEnd`, etc.)
 * always run every registered hook; an exception from one hook does not
 * stop subsequent hooks but is rethrown after the fan-out finishes.
 *
 * Defaults to [EMPTY] so callers that don't care about hooks pay no
 * runtime cost.
 */
class HookRegistry(private val hooks: List<WeftHook> = emptyList()) {

    val isEmpty: Boolean get() = hooks.isEmpty()

    suspend fun onUserMessage(ctx: HookContext.UserMessage) {
        if (hooks.isEmpty()) return
        fanOut { it.onUserMessage(ctx) }
    }

    suspend fun onTurnStart(ctx: HookContext.TurnStart) {
        if (hooks.isEmpty()) return
        fanOut { it.onTurnStart(ctx) }
    }

    suspend fun onTurnEnd(ctx: HookContext.TurnEnd) {
        if (hooks.isEmpty()) return
        fanOut { it.onTurnEnd(ctx) }
    }

    suspend fun onTurnFailed(ctx: HookContext.TurnFailed) {
        if (hooks.isEmpty()) return
        fanOut { it.onTurnFailed(ctx) }
    }

    /**
     * Run pre-tool hooks in registration order; return the first
     * [HookDecision.Deny] or [HookDecision.Continue] if all hooks pass.
     * Short-circuits so a denying hook can prevent expensive downstream
     * hooks from running.
     */
    suspend fun onToolStart(ctx: HookContext.ToolStart): HookDecision {
        if (hooks.isEmpty()) return HookDecision.Continue
        for (hook in hooks) {
            val decision = hook.onToolStart(ctx)
            if (decision is HookDecision.Deny) return decision
        }
        return HookDecision.Continue
    }

    suspend fun onToolEnd(ctx: HookContext.ToolEnd) {
        if (hooks.isEmpty()) return
        fanOut { it.onToolEnd(ctx) }
    }

    suspend fun onToolFailed(ctx: HookContext.ToolFailed) {
        if (hooks.isEmpty()) return
        fanOut { it.onToolFailed(ctx) }
    }

    /**
     * Invoke [block] on every hook in order. Exceptions are captured
     * and the first one is rethrown after every hook has had its turn,
     * so a misbehaving hook can't silently silence its successors.
     */
    private suspend inline fun fanOut(block: (WeftHook) -> Unit) {
        var first: Throwable? = null
        for (hook in hooks) {
            try {
                block(hook)
            } catch (t: Throwable) {
                if (first == null) first = t
            }
        }
        if (first != null) throw first
    }

    companion object {
        val EMPTY: HookRegistry = HookRegistry(emptyList())

        /** Convenience builder for the common 1–3 hooks case. */
        fun of(vararg hooks: WeftHook): HookRegistry = HookRegistry(hooks.toList())
    }
}
