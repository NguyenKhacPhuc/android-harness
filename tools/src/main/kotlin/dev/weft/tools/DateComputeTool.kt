package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pure date/time arithmetic. Eliminates LLM date math mistakes — the
 * model picks an op + amount + timezone instead of trying to add days
 * in its head and getting DST/leap-day cases wrong.
 *
 * Ops:
 *   - `now`       — current instant. Output is iso + epochMs.
 *   - `add`       — anchor + amount * unit. Defaults anchor to now.
 *   - `subtract`  — anchor - amount * unit.
 *   - `diff`      — between(b - a) in [unit]. Output is amount.
 *   - `start_of`  — start of DAY/WEEK/MONTH/YEAR for the anchor in tz.
 *   - `end_of`    — end of DAY/WEEK/MONTH/YEAR for the anchor in tz.
 *   - `weekday`   — day-of-week for the anchor in tz (MONDAY..SUNDAY).
 *
 * Time zone defaults to the device default if omitted. Anchor instants
 * are ISO-8601 (`2026-05-26T12:00:00Z`).
 */
@OptIn(ExperimentalTime::class)
public class DateComputeTool(ctx: WeftContext) :
    WeftTool<DateComputeTool.Args, DateComputeTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "date_compute",
            description = "Pure date/time arithmetic. Ops: now, add, subtract, diff, " +
                "start_of, end_of, weekday. Units: SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, " +
                "YEAR. Use INSTEAD OF doing date math in your head — handles DST, leap " +
                "days, timezone correctly.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "op",
                    "One of: now, add, subtract, diff, start_of, end_of, weekday.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "anchorIso",
                    "Anchor instant ISO-8601 (e.g. '2026-05-26T12:00:00Z'). Defaults to now.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "otherIso",
                    "Second instant for diff ('other' - 'anchor'). Required for op=diff.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "amount",
                    "Integer amount for add/subtract (negative allowed).",
                    ToolParameterType.Integer,
                ),
                ToolParameterDescriptor(
                    "unit",
                    "SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, YEAR (case-insensitive).",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "tz",
                    "IANA timezone (e.g. 'America/Los_Angeles'). Defaults to device default.",
                    ToolParameterType.String,
                ),
            ),
        ),
    ) {

    @Serializable
    public data class Args(
        val op: String,
        val anchorIso: String? = null,
        val otherIso: String? = null,
        val amount: Int? = null,
        val unit: String? = null,
        val tz: String? = null,
    )

    @Serializable
    public data class Result(
        val ok: Boolean,
        /** ISO-8601 instant for ops that return a moment. */
        val iso: String? = null,
        /** Epoch ms for ops that return a moment. */
        val epochMs: Long? = null,
        /** Integer amount for diff. */
        val amount: Long? = null,
        /** Weekday name MONDAY..SUNDAY for op=weekday. */
        val weekday: String? = null,
        /** Set when ok=false. */
        val error: String? = null,
    )

    override suspend fun executeWeft(args: Args): Result = runCatching {
        val tz = resolveTz(args.tz)
        when (args.op.lowercase()) {
            "now" -> Clock.System.now().asMomentResult()
            "add" -> shifted(args, tz, subtract = false)
            "subtract" -> shifted(args, tz, subtract = true)
            "diff" -> diff(args)
            "start_of" -> startOf(args, tz)
            "end_of" -> endOf(args, tz)
            "weekday" -> weekday(args, tz)
            else -> fail("Unknown op '${args.op}' — use one of: now, add, subtract, diff, start_of, end_of, weekday.")
        }
    }.getOrElse { fail(it.message ?: "Date computation failed.") }

    private fun shifted(args: Args, tz: TimeZone, subtract: Boolean): Result {
        val anchor = parseAnchor(args.anchorIso)
        val amount = args.amount ?: return fail("amount required for add/subtract.")
        val unit = parseDateTimeUnit(args.unit)
            ?: return fail("unit required (SECOND/MINUTE/HOUR/DAY/WEEK/MONTH/YEAR).")
        val shifted = if (subtract) anchor.minus(amount, unit, tz) else anchor.plus(amount, unit, tz)
        return shifted.asMomentResult()
    }

    private fun diff(args: Args): Result {
        val a = parseAnchor(args.anchorIso)
        val b = args.otherIso?.let { parseInstantOrNull(it) }
            ?: return fail("otherIso required for diff.")
        val unitToken = args.unit ?: "MILLISECOND"
        val deltaMs = b.toEpochMilliseconds() - a.toEpochMilliseconds()
        val out = when (unitToken.uppercase()) {
            "MILLISECOND" -> deltaMs
            "SECOND" -> deltaMs / MS_PER_SEC
            "MINUTE" -> deltaMs / (MS_PER_SEC * SEC_PER_MIN)
            "HOUR" -> deltaMs / (MS_PER_SEC * SEC_PER_MIN * MIN_PER_HOUR)
            "DAY" -> deltaMs / (MS_PER_SEC * SEC_PER_MIN * MIN_PER_HOUR * HOUR_PER_DAY)
            "WEEK" -> deltaMs / (MS_PER_SEC * SEC_PER_MIN * MIN_PER_HOUR * HOUR_PER_DAY * DAYS_PER_WEEK)
            else -> return fail("diff unit must be MILLISECOND/SECOND/MINUTE/HOUR/DAY/WEEK.")
        }
        return Result(ok = true, amount = out)
    }

    private fun startOf(args: Args, tz: TimeZone): Result {
        val anchor = parseAnchor(args.anchorIso)
        val local = anchor.toLocalDateTime(tz)
        val unit = args.unit?.uppercase() ?: "DAY"
        val start: Instant = when (unit) {
            "DAY" -> local.date.atStartOfDayIn(tz)
            "WEEK" -> local.date.weekStart().atStartOfDayIn(tz)
            "MONTH" -> LocalDate(local.year, local.month, 1).atStartOfDayIn(tz)
            "YEAR" -> LocalDate(local.year, 1, 1).atStartOfDayIn(tz)
            else -> return fail("start_of unit must be DAY/WEEK/MONTH/YEAR.")
        }
        return start.asMomentResult()
    }

    private fun endOf(args: Args, tz: TimeZone): Result {
        val anchor = parseAnchor(args.anchorIso)
        val local = anchor.toLocalDateTime(tz)
        val unit = args.unit?.uppercase() ?: "DAY"
        val end: LocalDate = when (unit) {
            "DAY" -> local.date
            "WEEK" -> local.date.weekStart().plus(DAYS_PER_WEEK - 1, DateTimeUnit.DAY)
            "MONTH" -> {
                val first = LocalDate(local.year, local.month, 1)
                first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
            }
            "YEAR" -> LocalDate(local.year, 12, 31)
            else -> return fail("end_of unit must be DAY/WEEK/MONTH/YEAR.")
        }
        // 23:59:59.999 in local tz.
        val endOfDay = LocalDateTime(end, LocalTime(HOUR_OF_DAY_MAX, MIN_OF_HOUR_MAX, SEC_OF_MIN_MAX, NS_BEFORE_NEXT_SEC))
        return endOfDay.toInstant(tz).asMomentResult()
    }

    private fun weekday(args: Args, tz: TimeZone): Result {
        val anchor = parseAnchor(args.anchorIso)
        val day: DayOfWeek = anchor.toLocalDateTime(tz).date.dayOfWeek
        return Result(ok = true, weekday = day.name)
    }

    private fun parseAnchor(iso: String?): Instant =
        if (iso.isNullOrBlank()) Clock.System.now() else Instant.parse(iso)

    private fun parseInstantOrNull(iso: String): Instant? =
        runCatching { Instant.parse(iso) }.getOrNull()

    private fun resolveTz(tz: String?): TimeZone =
        if (tz.isNullOrBlank()) TimeZone.currentSystemDefault()
        else runCatching { TimeZone.of(tz) }.getOrDefault(TimeZone.currentSystemDefault())

    private fun parseDateTimeUnit(token: String?): DateTimeUnit? = when (token?.uppercase()) {
        "SECOND" -> DateTimeUnit.SECOND
        "MINUTE" -> DateTimeUnit.MINUTE
        "HOUR" -> DateTimeUnit.HOUR
        "DAY" -> DateTimeUnit.DAY
        "WEEK" -> DateTimeUnit.WEEK
        "MONTH" -> DateTimeUnit.MONTH
        "YEAR" -> DateTimeUnit.YEAR
        else -> null
    }

    private fun LocalDate.weekStart(): LocalDate {
        // ISO weeks start Monday. dayOfWeek.ordinal: MONDAY=0 .. SUNDAY=6
        val backToMonday = this.dayOfWeek.ordinal
        return this.minus(backToMonday, DateTimeUnit.DAY)
    }

    private fun Instant.asMomentResult(): Result =
        Result(ok = true, iso = this.toString(), epochMs = this.toEpochMilliseconds())

    private fun fail(message: String): Result = Result(ok = false, error = message)

    private companion object {
        const val MS_PER_SEC = 1_000L
        const val SEC_PER_MIN = 60L
        const val MIN_PER_HOUR = 60L
        const val HOUR_PER_DAY = 24L
        const val DAYS_PER_WEEK = 7
        const val HOUR_OF_DAY_MAX = 23
        const val MIN_OF_HOUR_MAX = 59
        const val SEC_OF_MIN_MAX = 59
        // Nanoseconds to reach 23:59:59.999 (i.e. 999 ms = 999,000,000 ns).
        const val NS_BEFORE_NEXT_SEC = 999_000_000
    }
}
