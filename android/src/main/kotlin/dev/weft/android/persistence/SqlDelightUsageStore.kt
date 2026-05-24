package dev.weft.android.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.weft.harness.cost.PriceTable
import dev.weft.harness.cost.UsageStore
import dev.weft.harness.cost.UsageTotals
import dev.weft.android.db.WeftDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

/**
 * SQLDelight-backed [UsageStore]. Per-day aggregates persist; lifetime
 * totals are summed from the daily rows on every emission.
 *
 * "Last-call" fields ([UsageTotals.lastCallUsd], [UsageTotals.lastCallTokens],
 * [UsageTotals.lastCallModelId]) are transient — they describe "the last
 * call this process saw" and so do not survive restart. We keep them as
 * volatile in-memory state and merge them into each emission.
 *
 * Date handling: a "day" is `LocalDate.now(zone).toString()` (ISO
 * "yyyy-MM-dd") at the time the call was recorded. We don't store the
 * zone — the rollover follows whatever the device thinks "today" is,
 * matching [InMemoryUsageStore].
 */
public class SqlDelightUsageStore(
    private val db: WeftDatabase,
    coroutineScope: CoroutineScope,
    private val priceTable: PriceTable = PriceTable(),
    private val nowProvider: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) : UsageStore {

    /**
     * Volatile last-call state. Merged into each emitted [UsageTotals]
     * so the chat UI's "this call cost $X" badge works as before.
     */
    @Volatile private var lastCallUsd: Double = 0.0
    @Volatile private var lastCallTokens: Int = 0
    @Volatile private var lastCallModelId: String? = null
    @Volatile private var lastCallCacheReadTokens: Int = 0
    @Volatile private var lastCallCacheWriteTokens: Int = 0

    /**
     * Bump on every `record()` call. Combined into the totals flow below
     * so the UI re-emits when only volatile state changes (e.g., back-to-back
     * calls on the same day with the same totals — last-call still moves).
     */
    private val volatileBump = MutableStateFlow(0L)

    public override val totals: StateFlow<UsageTotals> = combine(
        db.usageQueries.selectAll().asFlow().mapToList(Dispatchers.IO),
        volatileBump,
    ) { rows, _ ->
        val byDay = rows.associate { it.day to it.usd_total }
        val lifetimeUsd = rows.sumOf { it.usd_total }
        val lifetimeInput = rows.sumOf { it.input_tokens }
        val lifetimeOutput = rows.sumOf { it.output_tokens }
        val lifetimeCacheRead = rows.sumOf { it.cache_read_tokens }
        val lifetimeCacheWrite = rows.sumOf { it.cache_write_tokens }
        UsageTotals(
            lifetimeUsd = lifetimeUsd,
            lifetimeInputTokens = lifetimeInput.toInt(),
            lifetimeOutputTokens = lifetimeOutput.toInt(),
            lifetimeCacheReadTokens = lifetimeCacheRead.toInt(),
            lifetimeCacheWriteTokens = lifetimeCacheWrite.toInt(),
            byDay = byDay,
            lastCallUsd = lastCallUsd,
            lastCallTokens = lastCallTokens,
            lastCallModelId = lastCallModelId,
            lastCallCacheReadTokens = lastCallCacheReadTokens,
            lastCallCacheWriteTokens = lastCallCacheWriteTokens,
        )
    }.stateIn(coroutineScope, SharingStarted.Eagerly, UsageTotals())

    public override fun record(
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        cacheReadTokens: Int,
        cacheWriteTokens: Int,
    ): Double {
        val price = priceTable.lookup(modelId) ?: return 0.0
        val cost = price.costUsd(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
        )
        val today = nowProvider().toString()
        db.usageQueries.addToDay(
            day = today,
            usd = cost,
            inputTokens = inputTokens.toLong(),
            outputTokens = outputTokens.toLong(),
            cacheReadTokens = cacheReadTokens.toLong(),
            cacheWriteTokens = cacheWriteTokens.toLong(),
        )
        lastCallUsd = cost
        lastCallTokens = inputTokens + outputTokens
        lastCallModelId = modelId
        lastCallCacheReadTokens = cacheReadTokens
        lastCallCacheWriteTokens = cacheWriteTokens
        // Force the combine flow to re-emit so last-call fields propagate
        // even if the daily SQL change is debounced.
        volatileBump.value = volatileBump.value + 1
        return cost
    }

    public override fun usdToday(): Double {
        val today = nowProvider().toString()
        return db.usageQueries.selectDay(today).executeAsOneOrNull()?.usd_total ?: 0.0
    }

    public override fun reset() {
        db.usageQueries.deleteAll()
        lastCallUsd = 0.0
        lastCallTokens = 0
        lastCallModelId = null
        lastCallCacheReadTokens = 0
        lastCallCacheWriteTokens = 0
        volatileBump.value = volatileBump.value + 1
    }
}
