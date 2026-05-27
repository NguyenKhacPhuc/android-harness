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
public interface WeftHook {

    public suspend fun onUserMessage(ctx: HookContext.UserMessage) {}
    public suspend fun onTurnStart(ctx: HookContext.TurnStart) {}
    public suspend fun onTurnEnd(ctx: HookContext.TurnEnd) {}
    public suspend fun onTurnFailed(ctx: HookContext.TurnFailed) {}

    /**
     * Pre-tool hook. Returning [HookDecision.Deny] aborts the tool
     * before its `execute` runs — the LLM sees the denial as a tool
     * error and chooses how to recover. Returning [HookDecision.Continue]
     * (the default) lets the tool proceed.
     */
    public suspend fun onToolStart(ctx: HookContext.ToolStart): HookDecision = HookDecision.Continue

    public suspend fun onToolEnd(ctx: HookContext.ToolEnd) {}
    public suspend fun onToolFailed(ctx: HookContext.ToolFailed) {}
}

/**
 * Inputs available to a [WeftHook] callback. Each variant carries only
 * the fields relevant for its lifecycle point — keep the surface tight
 * so hooks stay easy to write.
 */
public sealed class HookContext {
    /** Trace id this event belongs to. Shared across all hooks for one turn. */
    public abstract val traceId: String
    /** Conversation id this turn belongs to. */
    public abstract val conversationId: String

    public data class UserMessage(
        override val traceId: String,
        override val conversationId: String,
        public val text: String,
        public val hasAttachments: Boolean,
    ) : HookContext()

    public data class TurnStart(
        override val traceId: String,
        override val conversationId: String,
        public val userText: String,
        public val modelId: String,
    ) : HookContext()

    public data class TurnEnd(
        override val traceId: String,
        override val conversationId: String,
        public val assistantText: String,
        public val modelId: String,
    ) : HookContext()

    public data class TurnFailed(
        override val traceId: String,
        override val conversationId: String,
        public val cause: Throwable,
    ) : HookContext()

    public data class ToolStart(
        override val traceId: String,
        override val conversationId: String,
        public val toolName: String,
        public val argsPreview: String,
        public val risk: ToolRisk,
    ) : HookContext()

    public data class ToolEnd(
        override val traceId: String,
        override val conversationId: String,
        public val toolName: String,
        public val resultPreview: String?,
    ) : HookContext()

    public data class ToolFailed(
        override val traceId: String,
        override val conversationId: String,
        public val toolName: String,
        public val message: String,
    ) : HookContext()
}

/** Pre-tool hook outcome. */
public sealed class HookDecision {
    /** Tool proceeds normally. */
    public data object Continue : HookDecision()

    /**
     * Tool is aborted before [executeWeft][dev.weft.tools.WeftTool.executeWeft]
     * runs. The [reason] is surfaced to the LLM as the tool's error
     * message and to the chat UI as a `ToolEvent.Failed`.
     */
    public data class Deny(public val reason: String) : HookDecision()
}

/** Raised inside `WeftTool.execute` when a pre-tool hook returns [HookDecision.Deny]. */
public class HookDeniedException(
    public val toolName: String,
    public val reason: String,
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
public class HookRegistry(private val hooks: List<WeftHook> = emptyList()) {

    public val isEmpty: Boolean get() = hooks.isEmpty()

    public suspend fun onUserMessage(ctx: HookContext.UserMessage) {
        if (hooks.isEmpty()) return
        fanOut { it.onUserMessage(ctx) }
    }

    public suspend fun onTurnStart(ctx: HookContext.TurnStart) {
        if (hooks.isEmpty()) return
        fanOut { it.onTurnStart(ctx) }
    }

    public suspend fun onTurnEnd(ctx: HookContext.TurnEnd) {
        if (hooks.isEmpty()) return
        fanOut { it.onTurnEnd(ctx) }
    }

    public suspend fun onTurnFailed(ctx: HookContext.TurnFailed) {
        if (hooks.isEmpty()) return
        fanOut { it.onTurnFailed(ctx) }
    }

    /**
     * Run pre-tool hooks in registration order; return the first
     * [HookDecision.Deny] or [HookDecision.Continue] if all hooks pass.
     * Short-circuits so a denying hook can prevent expensive downstream
     * hooks from running.
     */
    public suspend fun onToolStart(ctx: HookContext.ToolStart): HookDecision {
        if (hooks.isEmpty()) return HookDecision.Continue
        for (hook in hooks) {
            val decision = hook.onToolStart(ctx)
            if (decision is HookDecision.Deny) return decision
        }
        return HookDecision.Continue
    }

    public suspend fun onToolEnd(ctx: HookContext.ToolEnd) {
        if (hooks.isEmpty()) return
        fanOut { it.onToolEnd(ctx) }
    }

    public suspend fun onToolFailed(ctx: HookContext.ToolFailed) {
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

    public companion object {
        public val EMPTY: HookRegistry = HookRegistry(emptyList())

        /** Convenience builder for the common 1–3 hooks case. */
        public fun of(vararg hooks: WeftHook): HookRegistry = HookRegistry(hooks.toList())
    }
}
