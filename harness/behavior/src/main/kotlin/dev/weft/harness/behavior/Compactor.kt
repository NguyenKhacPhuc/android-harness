package dev.weft.harness.behavior

/**
 * Reduces conversation history before it's sent to the LLM, according to a
 * [BehaviorConfig.compactionStrategy]. Applies once per turn inside
 * WeftAgent.send().
 */
class Compactor(
    private val config: BehaviorConfig,
    /**
     * Token estimator used by [estimateInputTokens]. Defaults to the
     * 4-chars/token heuristic — accurate to within ~10% for English on
     * Claude and GPT. Swap in a provider-native tokenizer when the
     * compaction trigger needs to track real usage.
     */
    private val tokenCounter: TokenCounter = HeuristicTokenCounter(),
) {

    /**
     * Trim or summarize [history] for the next turn. Returns the
     * possibly-shorter list — same shape, fewer entries, possibly with a
     * leading [Turn.System] recap message.
     */
    fun compact(history: List<Turn>): List<Turn> {
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
     * Estimate input tokens for the given history via the injected
     * [TokenCounter]. Default counter is the 4-chars/token heuristic;
     * apps that bill per-token should inject a real tokenizer.
     */
    fun estimateInputTokens(history: List<Turn>): Int =
        history.sumOf { tokenCounter.estimate(it.text) }

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

}

/**
 * Weft's conversation-history shape, decoupled from any provider's
 * Message type. WeftAgent maintains a list of these; the Compactor
 * operates on them; the WeftAgent translates them to Koog Messages
 * when building the next Prompt.
 */
sealed class Turn {
    abstract val text: String

    data class User(override val text: String) : Turn()
    data class Assistant(override val text: String) : Turn()
    data class System(override val text: String) : Turn()
}
