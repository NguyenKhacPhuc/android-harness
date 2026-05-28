package dev.weft.harness.prompt.bindings

import dev.weft.contracts.DataSource
import dev.weft.contracts.DataSourceRegistry
import dev.weft.contracts.SortOrder
import dev.weft.contracts.SortSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure evaluator for `$binding` expressions emitted by the agent in a
 * `ComponentNode`'s props. Lives in `:harness:prompt` because it's
 * pure JVM data — no Android, no Compose — and unit-testable.
 *
 * ### Binding shape
 *
 * A binding is a JsonObject with a `$binding` key whose value is a
 * spec object:
 *
 * ```jsonc
 * {
 *   "$binding": {
 *     "source": "notes",             // required: data-source name
 *     "where": {                     // optional: filter expression
 *       "$and": [
 *         { "type": { "$eq": "water_log" } },
 *         { "logged_at_ms": { "$gte": { "$today": "start" } } }
 *       ]
 *     },
 *     "aggregate": {                 // optional: one of sum/count/avg/min/max/list
 *       "kind": "sum",
 *       "field": "amount_oz"
 *     },
 *     "orderBy": [{ "field": "logged_at_ms", "order": "desc" }],
 *     "limit": 50,
 *     "format": "Today: {value} oz"  // optional: template; "{value}" for the
 *                                    //   aggregate result, "{field}" for fields
 *                                    //   on a single record (list aggregates)
 *   }
 * }
 * ```
 *
 * Without `aggregate`, the binding returns the raw filtered + sorted +
 * limited result list. Without `format`, the binding returns the raw
 * aggregated value (a JsonPrimitive for sum/count/avg/min/max, a
 * JsonArray for list, or null when no rows match a `min`/`max`).
 *
 * ### Filter operators (`where`)
 *
 * Equality shorthand: `{ "field": value }` is `{ "field": { "$eq": value } }`.
 *
 * Comparison: `$eq`, `$ne`, `$gt`, `$gte`, `$lt`, `$lte`.
 * Set: `$in` (value list).
 * String/array containment: `$contains` (substring or member).
 * Existence: `$exists` (boolean).
 * Logical: `$and`, `$or`, `$not` (compose sub-expressions).
 *
 * Time sentinels resolve to epoch milliseconds:
 *   - `{"$now": true}` — current time.
 *   - `{"$today": "start"}` / `{"$today": "end"}` — today's 00:00 / 23:59 in local TZ.
 *   - `{"$weekStart": true}` / `{"$monthStart": true}` — start of week / month.
 *   - `{"$dateOffset": {"from": <expr>, "days": N, "hours": N, "minutes": N}}` —
 *     subtract (negative N) or add (positive N) a duration. `from` defaults
 *     to `$now`. Useful for "last 7 days" filters.
 *
 * ### Aggregations
 *
 * `sum` / `avg` / `min` / `max` — operate on a numeric field. `min`/`max`
 *   return null on empty match set.
 * `count` — total matching rows (ignores `field`).
 * `list` — returns the filtered + sorted + limited rows as a JsonArray.
 *   Combine with `format` (e.g. `"{amount_oz} oz at {logged_at_ms}"`) to
 *   render each row as a string; without `format`, returns the rows raw.
 *
 * Designed not to throw on bad input — invalid bindings return
 * [JsonNull] so the renderer can fall back to a blank value rather
 * than crashing the screen. Use [evaluateWithError] when you want the
 * underlying exception for diagnostics.
 */
object BindingEvaluator {

    /**
     * Evaluate a binding spec. Returns the resolved value (or [JsonNull]
     * on any failure). Failure includes: unknown source, malformed
     * filter, unsupported aggregate kind.
     */
    suspend fun evaluate(
        binding: JsonObject,
        sources: DataSourceRegistry,
    ): JsonElement = runCatching { evaluateWithError(binding, sources) }.getOrElse { JsonNull }

    /**
     * Same as [evaluate] but rethrows on error — exposed for callers
     * that want diagnostics (e.g. a debug overlay or a unit test).
     */
    suspend fun evaluateWithError(
        binding: JsonObject,
        sources: DataSourceRegistry,
    ): JsonElement {
        val spec = binding["\$binding"] as? JsonObject
            ?: throw IllegalArgumentException("Not a binding: missing \$binding key")

        val sourceName = (spec["source"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Binding missing 'source'")
        val source = sources.get(sourceName)
            ?: throw IllegalArgumentException("Unknown source '$sourceName'")

        val whereExpr = spec["where"] as? JsonObject
        val orderBy = parseOrderBy(spec["orderBy"])
        val limit = (spec["limit"] as? JsonPrimitive)?.longOrNull?.toInt()
            ?: DataSource.LIMIT_DEFAULT

        // Pull all matching rows. The DataSource contract's filter only
        // supports equality; we fetch with no filter and apply our richer
        // filter in-memory. Fine for the dataset sizes a personal app
        // sees; revisit if any source ever grows past ~10k records.
        val allRows = source.query(
            filter = JsonObject(emptyMap()),
            sort = orderBy,
            projection = emptyList(),
            limit = DataSource.LIMIT_MAX,
        ).items.filterIsInstance<JsonObject>()

        val filtered = if (whereExpr == null) allRows
        else allRows.filter { matchesFilter(it, whereExpr) }

        val limited = filtered.take(limit)

        val aggregate = spec["aggregate"] as? JsonObject
        val resolved: JsonElement = if (aggregate == null) {
            JsonArray(limited)
        } else {
            applyAggregate(aggregate, filtered, limited)
        }

        val format = (spec["format"] as? JsonPrimitive)?.contentOrNull()
        return if (format == null) resolved else applyFormat(format, resolved)
    }

    // ─── Filter evaluation ─────────────────────────────────────────────

    /**
     * Recursively evaluate a where-expression against a row.
     * Supports both the equality shorthand (`{field: value}`) and the
     * operator form (`{field: {$op: value}}`), plus logical combiners.
     */
    private suspend fun matchesFilter(row: JsonObject, filter: JsonObject): Boolean {
        // Logical combiners — recognized regardless of key order. Any
        // unrecognized leading key is treated as a field predicate so
        // mixed shapes work ("$and: [...]" alongside a couple of
        // field-level predicates).
        for ((key, value) in filter) {
            val ok = when (key) {
                "\$and" -> {
                    val list = value as? JsonArray ?: return false
                    list.all { it is JsonObject && matchesFilter(row, it) }
                }
                "\$or" -> {
                    val list = value as? JsonArray ?: return false
                    list.any { it is JsonObject && matchesFilter(row, it) }
                }
                "\$not" -> {
                    val sub = value as? JsonObject ?: return false
                    !matchesFilter(row, sub)
                }
                else -> matchesFieldPredicate(row, key, value)
            }
            if (!ok) return false
        }
        return true
    }

    /**
     * Field-level predicate. [predicate] is either a raw value (equality
     * shorthand) or an object of operators (`{$gt: 5, $lt: 10}` — AND'd).
     */
    private suspend fun matchesFieldPredicate(
        row: JsonObject,
        field: String,
        predicate: JsonElement,
    ): Boolean {
        val actual = row[field]
        return when (predicate) {
            // Shorthand: {field: 8} = {field: {$eq: 8}}
            is JsonPrimitive, is JsonArray -> equals(actual, predicate)
            is JsonObject -> predicate.all { (op, expected) ->
                applyOperator(op, actual, expected)
            }
            else -> false
        }
    }

    private suspend fun applyOperator(
        op: String,
        actual: JsonElement?,
        expected: JsonElement,
    ): Boolean {
        val resolvedExpected = resolveDynamic(expected)
        return when (op) {
            "\$eq" -> equals(actual, resolvedExpected)
            "\$ne" -> !equals(actual, resolvedExpected)
            "\$gt" -> compareNumeric(actual, resolvedExpected) > 0
            "\$gte" -> compareNumeric(actual, resolvedExpected) >= 0
            "\$lt" -> compareNumeric(actual, resolvedExpected) < 0
            "\$lte" -> compareNumeric(actual, resolvedExpected) <= 0
            "\$in" -> {
                val list = resolvedExpected as? JsonArray ?: return false
                list.any { equals(actual, it) }
            }
            "\$contains" -> {
                val needle = (resolvedExpected as? JsonPrimitive)?.content ?: return false
                val haystack = (actual as? JsonPrimitive)?.content ?: return false
                needle in haystack
            }
            "\$exists" -> {
                val want = (resolvedExpected as? JsonPrimitive)?.booleanOrNull ?: return false
                (actual != null && actual !is JsonNull) == want
            }
            else -> false
        }
    }

    private fun equals(a: JsonElement?, b: JsonElement): Boolean {
        if (a == null || a is JsonNull) return b is JsonNull
        return a == b
    }

    private fun compareNumeric(a: JsonElement?, b: JsonElement): Int {
        val ad = (a as? JsonPrimitive)?.doubleOrNull ?: return -1
        val bd = (b as? JsonPrimitive)?.doubleOrNull ?: return 1
        return ad.compareTo(bd)
    }

    // ─── Dynamic value resolution ─────────────────────────────────────
    //
    // Sentinels like `{"$now": true}` and `{"$today": "start"}` need to
    // become concrete values before filter / format steps. resolveDynamic
    // walks one level deep — sentinels can't be nested inside each other
    // (yet). $dateOffset's `from` field IS resolved recursively so
    // "yesterday at this time" works.

    /** Recursively resolve $now / $today / $weekStart / $monthStart / $dateOffset. */
    private fun resolveDynamic(value: JsonElement): JsonElement {
        if (value !is JsonObject) return value
        // Each sentinel is identified by a leading $-prefixed key. If
        // none match, return the object unchanged (it's a regular value
        // object, not a sentinel).
        return when {
            "\$now" in value -> JsonPrimitive(System.currentTimeMillis())
            "\$today" in value -> {
                val which = (value["\$today"] as? JsonPrimitive)?.content
                JsonPrimitive(todayBoundary(which))
            }
            "\$weekStart" in value -> JsonPrimitive(startOfPeriod(Calendar.WEEK_OF_YEAR))
            "\$monthStart" in value -> JsonPrimitive(startOfPeriod(Calendar.MONTH))
            "\$dateOffset" in value -> {
                val spec = value["\$dateOffset"] as? JsonObject ?: return value
                val base = (resolveDynamic(spec["from"] ?: JsonObject(mapOf("\$now" to JsonPrimitive(true)))) as? JsonPrimitive)
                    ?.longOrNull ?: System.currentTimeMillis()
                val days = (spec["days"] as? JsonPrimitive)?.longOrNull ?: 0L
                val hours = (spec["hours"] as? JsonPrimitive)?.longOrNull ?: 0L
                val minutes = (spec["minutes"] as? JsonPrimitive)?.longOrNull ?: 0L
                JsonPrimitive(base + days * 86_400_000L + hours * 3_600_000L + minutes * 60_000L)
            }
            else -> value
        }
    }

    private fun todayBoundary(which: String?): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (which == "end") {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MILLISECOND, -1)
        }
        return cal.timeInMillis
    }

    private fun startOfPeriod(periodField: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        when (periodField) {
            Calendar.WEEK_OF_YEAR -> cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            Calendar.MONTH -> cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    // ─── Aggregation ──────────────────────────────────────────────────

    /**
     * Apply the spec's aggregate against the filtered rows. `limited`
     * is used only for the `list` aggregation (which respects the
     * binding's `limit`); numeric aggregations operate on the full
     * filtered set so `sum` / `count` don't lie when there are more
     * matches than `limit` allows.
     */
    private fun applyAggregate(
        spec: JsonObject,
        filtered: List<JsonObject>,
        limited: List<JsonObject>,
    ): JsonElement {
        val kind = (spec["kind"] as? JsonPrimitive)?.content
        val field = (spec["field"] as? JsonPrimitive)?.content
        return when (kind) {
            "count" -> JsonPrimitive(filtered.size)
            "sum" -> {
                val f = requireField(kind, field)
                JsonPrimitive(filtered.sumOf { numericField(it, f) })
            }
            "avg" -> {
                val f = requireField(kind, field)
                if (filtered.isEmpty()) JsonNull
                else JsonPrimitive(filtered.sumOf { numericField(it, f) } / filtered.size)
            }
            "min" -> {
                val f = requireField(kind, field)
                filtered.minOfOrNull { numericField(it, f) }?.let { JsonPrimitive(it) } ?: JsonNull
            }
            "max" -> {
                val f = requireField(kind, field)
                filtered.maxOfOrNull { numericField(it, f) }?.let { JsonPrimitive(it) } ?: JsonNull
            }
            "list" -> JsonArray(limited)
            else -> throw IllegalArgumentException("Unsupported aggregate kind '$kind'")
        }
    }

    private fun requireField(kind: String, field: String?): String {
        require(!field.isNullOrBlank()) { "Aggregate '$kind' requires 'field'" }
        return field
    }

    private fun numericField(row: JsonObject, field: String): Double =
        (row[field] as? JsonPrimitive)?.doubleOrNull ?: 0.0

    // ─── Sort + format ───────────────────────────────────────────────

    private fun parseOrderBy(json: JsonElement?): List<SortSpec> {
        val list = json as? JsonArray ?: return emptyList()
        return list.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val field = (obj["field"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val order = when ((obj["order"] as? JsonPrimitive)?.content?.lowercase()) {
                "desc", "descending" -> SortOrder.DESC
                else -> SortOrder.ASC
            }
            SortSpec(field, order)
        }
    }

    /**
     * Substitute `{token}` placeholders in [template]. For a single-
     * value resolved binding (sum/count/avg/min/max/raw primitive),
     * `{value}` resolves to that value. For a row-bearing resolved
     * binding (list aggregation or no aggregate), each row is formatted
     * separately and joined by newlines — `{field}` references row
     * fields by name.
     */
    private fun applyFormat(template: String, resolved: JsonElement): JsonElement {
        return when (resolved) {
            is JsonArray -> {
                val lines = resolved.mapNotNull { row ->
                    (row as? JsonObject)?.let { fillTemplate(template, it) }
                }
                JsonPrimitive(lines.joinToString("\n"))
            }
            else -> {
                val ctx = buildJsonObject { put("value", resolved) }
                JsonPrimitive(fillTemplate(template, ctx))
            }
        }
    }

    /** {field} substitution. Unknown fields become an empty string. */
    private fun fillTemplate(template: String, ctx: JsonObject): String =
        TEMPLATE_REGEX.replace(template) { m ->
            val key = m.groupValues[1]
            val v = ctx[key]
            when (v) {
                is JsonPrimitive -> v.content
                null, is JsonNull -> ""
                else -> v.toString()
            }
        }

    private val TEMPLATE_REGEX = Regex("\\{([^{}]+)\\}")

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else content
}
