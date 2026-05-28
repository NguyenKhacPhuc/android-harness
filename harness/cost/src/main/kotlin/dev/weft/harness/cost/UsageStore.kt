package dev.weft.harness.cost

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId

/**
 * Aggregates token usage + dollar cost across turns. Per-conversation,
 * per-day, per-month, all-time. Weft exposes the current totals to
 * the UI via [totals].
 *
 * Two implementations ship:
 *   - [InMemoryUsageStore] — totals are lost on app restart. Useful for
 *     tests and short-lived demos.
 *   - `SqlDelightUsageStore` (in `:substrate:android`) — per-day rows
 *     persist via WeftDatabase. Lifetime totals are recomputed
 *     from the daily aggregates on startup. Last-call ephemeral state
 *     intentionally lives in-memory only.
 *
 * Implementations are responsible for picking up the right `nowProvider`
 * — same shape, different time-zone strategies allowed.
 */
interface UsageStore {
    /** Live snapshot of accumulated usage. The cost badge reads from this. */
    val totals: StateFlow<UsageTotals>

    /**
     * Record an LLM call's usage. Returns the per-call cost in USD; the
     * UI displays this as a "this call cost $X" badge.
     *
     * [agentName] attributes the spend to a registered
     * [AgentDeclaration][dev.weft.harness.agents.AgentDeclaration].
     * Defaults to `"default"` so single-agent callers don't have to
     * thread anything new. Multi-agent hosts populate this so the
     * per-agent cost breakdown (`UsageTotals.byAgent`,
     * `selectLifetimeByAgent` SQL query) is queryable.
     */
    fun record(
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0,
        agentName: String = DEFAULT_AGENT_NAME,
    ): Double

    /** Convenience: today's spend per the implementation's clock. */
    fun usdToday(): Double

    /** Wipe all aggregates. Used by the "reset usage" admin action. */
    fun reset()

    companion object {
        /**
         * Canonical name for the auto-default agent — kept in sync
         * with `AgentDeclaration.DEFAULT_AGENT_NAME` and
         * `ConversationStore.DEFAULT_AGENT_NAME`. Duplicated here to
         * keep `:harness:cost` free of a back-dep on `:harness:agents`.
         */
        const val DEFAULT_AGENT_NAME: String = "default"
    }
}

/**
 * In-memory [UsageStore]. State lives in a [MutableStateFlow]; daily
 * aggregates are kept in an immutable map.
 *
 * Use this for tests, or in apps that don't need usage to survive
 * restart. Production apps should use `SqlDelightUsageStore`.
 */
class InMemoryUsageStore(
    private val priceTable: PriceTable = PriceTable(),
    private val nowProvider: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) : UsageStore {
    private val _totals: MutableStateFlow<UsageTotals> = MutableStateFlow(UsageTotals())
    override val totals: StateFlow<UsageTotals> = _totals.asStateFlow()

    override fun record(
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        cacheReadTokens: Int,
        cacheWriteTokens: Int,
        agentName: String,
    ): Double {
        val price = priceTable.lookup(modelId) ?: return 0.0
        val cost = price.costUsd(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
        )
        val today = nowProvider().toString()
        _totals.update { current ->
            val newToday = current.byDay[today]?.plus(cost) ?: cost
            val newAgentTotal = current.byAgent[agentName]?.plus(cost) ?: cost
            current.copy(
                lifetimeUsd = current.lifetimeUsd + cost,
                lifetimeInputTokens = current.lifetimeInputTokens + inputTokens,
                lifetimeOutputTokens = current.lifetimeOutputTokens + outputTokens,
                lifetimeCacheReadTokens = current.lifetimeCacheReadTokens + cacheReadTokens,
                lifetimeCacheWriteTokens = current.lifetimeCacheWriteTokens + cacheWriteTokens,
                byDay = current.byDay + (today to newToday),
                byAgent = current.byAgent + (agentName to newAgentTotal),
                lastCallUsd = cost,
                lastCallTokens = inputTokens + outputTokens,
                lastCallModelId = modelId,
                lastCallCacheReadTokens = cacheReadTokens,
                lastCallCacheWriteTokens = cacheWriteTokens,
            )
        }
        return cost
    }

    override fun usdToday(): Double = _totals.value.byDay[nowProvider().toString()] ?: 0.0

    override fun reset() {
        _totals.value = UsageTotals()
    }
}

/**
 * Snapshot of accumulated usage. The viewer reads from this via the
 * StateFlow on UsageStore.
 */
data class UsageTotals(
    val lifetimeUsd: Double = 0.0,
    val lifetimeInputTokens: Int = 0,
    val lifetimeOutputTokens: Int = 0,
    /** Cumulative cache-read input tokens — billed at ~0.1× base on Anthropic. */
    val lifetimeCacheReadTokens: Int = 0,
    /** Cumulative cache-write input tokens — billed at ~1.25× base on Anthropic. */
    val lifetimeCacheWriteTokens: Int = 0,
    val byDay: Map<String, Double> = emptyMap(),
    /**
     * Lifetime USD by registered agent name. Single-agent apps see
     * one entry under `"default"`; multi-agent hosts get the
     * per-agent breakdown the "where did my budget go" UI needs.
     * Populated by [UsageStore.record]'s `agentName` parameter.
     */
    val byAgent: Map<String, Double> = emptyMap(),
    val lastCallUsd: Double = 0.0,
    val lastCallTokens: Int = 0,
    val lastCallModelId: String? = null,
    /**
     * Cache-read input tokens from the most recent LLM call. The UI uses
     * this to show "saved $X by cache" badges; over time these add up to
     * [lifetimeCacheReadTokens]. Volatile in persistent stores.
     */
    val lastCallCacheReadTokens: Int = 0,
    val lastCallCacheWriteTokens: Int = 0,
)

/**
 * Quota policy. Default: 5 USD/day soft warning, 10 USD/day hard cap.
 * The check is invoked by WeftAgent before each send; over the hard
 * cap, send() throws [QuotaExceededException].
 */
data class QuotaPolicy(
    val dailySoftWarningUsd: Double? = 5.0,
    val dailyHardCapUsd: Double? = 10.0,
) {
    fun check(usdToday: Double): QuotaState = when {
        dailyHardCapUsd != null && usdToday >= dailyHardCapUsd -> QuotaState.Blocked(usdToday, dailyHardCapUsd)
        dailySoftWarningUsd != null && usdToday >= dailySoftWarningUsd -> QuotaState.Warning(usdToday, dailySoftWarningUsd)
        else -> QuotaState.Ok(usdToday)
    }
}

sealed class QuotaState {
    abstract val usdToday: Double
    data class Ok(override val usdToday: Double) : QuotaState()
    data class Warning(override val usdToday: Double, val thresholdUsd: Double) : QuotaState()
    data class Blocked(override val usdToday: Double, val thresholdUsd: Double) : QuotaState()
}

class QuotaExceededException(val usdToday: Double, val capUsd: Double) :
    RuntimeException("Daily cost cap reached: \$%.2f used of \$%.2f cap".format(usdToday, capUsd))
