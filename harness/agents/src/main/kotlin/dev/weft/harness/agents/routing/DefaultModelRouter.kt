package dev.weft.harness.agents.routing

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart

/**
 * Default rules-based [ModelRouter]. Uses only deterministic signals
 * — no extra LLM calls, no extra latency. Misses ambiguous cases but
 * catches the obvious wins:
 *
 *   - **Images / video** → vision-capable model (regardless of text size)
 *   - **Coding indicators** (code fences, common language keywords near
 *     the start of the message) → heavy model
 *   - **Short, simple, no attachments, fresh chat** → cheap model
 *   - **Everything else** → standard model
 *
 * Thresholds are conservative — when a rule isn't confidently satisfied
 * the router defaults to `pool.standard` rather than risking a quality
 * regression on something that should have been Sonnet. Tune via the
 * [shortTextThreshold] / [freshChatTurnsThreshold] knobs if your
 * workload has different shape.
 */
public class DefaultModelRouter(
    /**
     * User messages shorter than this (in characters) are eligible for
     * [ModelPool.cheap]. 200 chars ≈ 1-2 short sentences; longer than
     * that and the model is probably doing real work that benefits from
     * standard-tier reasoning.
     */
    private val shortTextThreshold: Int = 200,
    /**
     * "Fresh chat" cutoff for the cheap-model rule. The first few turns
     * of a conversation are often classification / chitchat / "what can
     * you do" — cheap is fine. Once the user is several turns in, they
     * likely care about quality.
     */
    private val freshChatTurnsThreshold: Int = 3,
) : ModelRouter {

    override suspend fun route(context: RoutingContext): LLModel = when {
        // Rule 0: explicit per-call tier override. Caller asked for a
        // specific slot — honor it. Visions still wins over a `Cheap`
        // hint when there's a visual attachment, because the cheap model
        // typically can't see images at all; better a wasted hint than
        // a runtime error.
        context.tierHint != null -> when (context.tierHint) {
            ModelTier.Cheap -> if (context.hasVisualAttachment()) context.pool.vision else context.pool.cheap
            ModelTier.Standard -> context.pool.standard
            ModelTier.Vision -> context.pool.vision
            ModelTier.Heavy -> context.pool.heavy
        }

        // Rule 1: any image / video forces a vision-capable model.
        // Audio + files (PDFs) can sometimes work with cheap models too,
        // but vision is the modality with the hardest hard-requirement,
        // so it's the conservative choice.
        context.hasVisualAttachment() -> context.pool.vision

        // Rule 2: coding-shaped requests want the heavy model.
        // Detection is intentionally simple — fenced code blocks or a
        // direct mention of code/program/function/script in the first
        // 100 chars. False negatives are fine (they fall through to
        // standard); false positives are slightly wasteful but not
        // wrong.
        context.looksLikeCoding() -> context.pool.heavy

        // Rule 3: short message in a fresh conversation — cheap is fine.
        // Once a thread has accumulated context the user is usually
        // doing something meatier, so we widen the bar after a few turns.
        context.userText.length < shortTextThreshold &&
            context.historyTurns < freshChatTurnsThreshold -> context.pool.cheap

        // Default: standard model.
        else -> context.pool.standard
    }

    private fun RoutingContext.hasVisualAttachment(): Boolean = attachments.any {
        val src = it.source
        src is AttachmentSource.Image || src is AttachmentSource.Video
    }

    private fun RoutingContext.looksLikeCoding(): Boolean {
        if (userText.contains("```")) return true
        val head = userText.take(CODING_HEAD_WINDOW).lowercase()
        return CODING_KEYWORDS.any { head.contains(it) }
    }

    private companion object {
        /** Window from the start of the message we scan for coding hints. */
        const val CODING_HEAD_WINDOW = 100

        /**
         * Keyword markers that strongly suggest a coding task. Kept
         * deliberately small to limit false positives — "write the
         * function" still catches via "function," but "great function
         * of love" doesn't trigger (head check + lowercase, but the
         * leading context drops false hits in practice).
         */
        val CODING_KEYWORDS: List<String> = listOf(
            "write a function",
            "write the function",
            "implement",
            "refactor",
            "fix this bug",
            "debug",
            "compile",
            "stack trace",
            "kotlin",
            "swift",
            "typescript",
            "python",
        )
    }
}
