package dev.weft.harness.behavior

/**
 * Reduces conversation history before it's sent to the LLM, according to a
 * [BehaviorConfig.compactionStrategy]. Applies once per turn inside
 * WeftAgent.send().
 */
public class Compactor(private val config: BehaviorConfig) {

    /**
     * Trim or summarize [history] for the next turn. Returns the
     * possibly-shorter list — same shape, fewer entries, possibly with a
     * leading [Turn.System] recap message.
     */
    public fun compact(history: List<Turn>): List<Turn> {
        if (history.size <= config.compactionTriggerTurns) return history
        return when (val strategy = config.compactionStrategy) {
            CompactionStrategy.None -> history
            CompactionStrategy.DropOldest -> dropOldest(history)
            is CompactionStrategy.SlidingWindow -> slidingWindow(history, strategy.keepLastN)
            // Summarize is not implemented in v1; fall back to sliding-window with N=20.
            is CompactionStrategy.Summarize -> slidingWindow(history, keepLastN = 20)
        }
    }

    /**
     * Estimate input tokens for the given history. Rough heuristic — 4 chars
     * per token. Good enough to decide whether to compact; a real tokenizer
     * lands when we wire `harness-cost`.
     */
    public fun estimateInputTokens(history: List<Turn>): Int =
        history.sumOf { it.text.length / CHARS_PER_TOKEN_APPROX }

    private fun dropOldest(history: List<Turn>): List<Turn> {
        // Drop oldest user/assistant pairs until under the trigger.
        var trimmed = history
        while (trimmed.size > config.compactionTriggerTurns && trimmed.size >= 2) {
            trimmed = trimmed.drop(2)
        }
        return prependRecap(trimmed, droppedCount = history.size - trimmed.size)
    }

    private fun slidingWindow(history: List<Turn>, keepLastN: Int): List<Turn> {
        if (history.size <= keepLastN) return history
        val kept = history.takeLast(keepLastN)
        return prependRecap(kept, droppedCount = history.size - kept.size)
    }

    /**
     * Prepend a one-line system note acknowledging the truncation so the LLM
     * knows older turns existed (and won't be confused if the user references
     * them).
     */
    private fun prependRecap(kept: List<Turn>, droppedCount: Int): List<Turn> {
        if (droppedCount <= 0) return kept
        val note = Turn.System(
            "[Earlier messages omitted from this turn for context-length reasons. " +
                "$droppedCount turns dropped.]",
        )
        return listOf(note) + kept
    }

    public companion object {
        private const val CHARS_PER_TOKEN_APPROX = 4
    }
}

/**
 * Weft's conversation-history shape, decoupled from any provider's
 * Message type. WeftAgent maintains a list of these; the Compactor
 * operates on them; the WeftAgent translates them to Koog Messages
 * when building the next Prompt.
 */
public sealed class Turn {
    public abstract val text: String

    public data class User(override val text: String) : Turn()
    public data class Assistant(override val text: String) : Turn()
    public data class System(override val text: String) : Turn()
}
