package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.regex.Pattern
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Match a regex against text. Operations:
 *   - `find` — first / all matches with capture groups
 *   - `replace` — find/replace with regex (use `$1`, `$2` for backrefs)
 *   - `split` — split by the pattern
 *
 * Use to avoid LLM string-manipulation errors on structured text
 * (phone numbers, emails, codes).
 */
class RegexMatchTool(ctx: WeftContext) :
    WeftTool<RegexMatchTool.Args, RegexMatchTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "regex_match",
            description = "Run a Java-style regex against text. ops: find (all matches), " +
                "replace (replacement supports \$1/\$2 backrefs), split. Use for structured " +
                "text — phone numbers, emails, codes.",
            requiredParameters = listOf(
                ToolParameterDescriptor("op", "find / replace / split.", ToolParameterType.String),
                ToolParameterDescriptor("pattern", "Java regex.", ToolParameterType.String),
                ToolParameterDescriptor("text", "Input text.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor(
                    "replacement",
                    "For op=replace; \$1/\$2 reference capture groups.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor(
                    "ignoreCase",
                    "Case-insensitive match (default false).",
                    ToolParameterType.Boolean,
                ),
            ),
        ),
    ) {

    @Serializable
    data class Match(val match: String, val groups: List<String?> = emptyList(), val start: Int, val end: Int)

    @Serializable
    data class Args(
        val op: String,
        val pattern: String,
        val text: String,
        val replacement: String? = null,
        val ignoreCase: Boolean = false,
    )

    @Serializable
    data class Result(
        val ok: Boolean,
        val matches: List<Match>? = null,
        val replaced: String? = null,
        val parts: List<String>? = null,
        val error: String? = null,
    )

    override suspend fun executeWeft(args: Args): Result = runCatching {
        val flags = if (args.ignoreCase) Pattern.CASE_INSENSITIVE else 0
        val regex = Pattern.compile(args.pattern, flags)
        when (args.op.lowercase()) {
            "find" -> {
                val matcher = regex.matcher(args.text)
                val out = mutableListOf<Match>()
                while (matcher.find()) {
                    val groups = (1..matcher.groupCount()).map {
                        runCatching { matcher.group(it) }.getOrNull()
                    }
                    out += Match(
                        match = matcher.group(),
                        groups = groups,
                        start = matcher.start(),
                        end = matcher.end(),
                    )
                }
                Result(ok = true, matches = out)
            }
            "replace" -> {
                val replacement = args.replacement
                    ?: return@runCatching Result(ok = false, error = "replacement required for op=replace")
                Result(ok = true, replaced = regex.matcher(args.text).replaceAll(replacement))
            }
            "split" -> Result(ok = true, parts = regex.split(args.text).toList())
            else -> Result(ok = false, error = "Unknown op '${args.op}'.")
        }
    }.getOrElse { Result(ok = false, error = it.message ?: "Regex error.") }
}

/**
 * Break a URL into components — scheme, host, port, path, query
 * params, fragment. Use for: deciding which app handles a link,
 * extracting a tracking parameter, building a canonical form.
 */
class UrlParseTool(ctx: WeftContext) :
    WeftTool<UrlParseTool.Args, UrlParseTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "url_parse",
            description = "Parse a URL into scheme/host/port/path/queryParams/fragment. Use " +
                "instead of guessing — handles odd schemes, percent-encoded paths, and " +
                "multi-value query parameters.",
            requiredParameters = listOf(
                ToolParameterDescriptor("url", "URL to parse.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val url: String)

    @Serializable
    data class Result(
        val ok: Boolean,
        val scheme: String? = null,
        val host: String? = null,
        val port: Int? = null,
        val path: String? = null,
        val queryParams: Map<String, List<String>>? = null,
        val fragment: String? = null,
        val error: String? = null,
    )

    override suspend fun executeWeft(args: Args): Result = runCatching {
        val uri = URI(args.url)
        val params = uri.query?.let { parseQuery(it) }
        Result(
            ok = true,
            scheme = uri.scheme,
            host = uri.host,
            port = uri.port.takeIf { it > 0 },
            path = uri.path?.ifBlank { null },
            queryParams = params,
            fragment = uri.fragment,
        )
    }.getOrElse { Result(ok = false, error = it.message ?: "Parse failed.") }

    private fun parseQuery(query: String): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        for (kv in query.split('&')) {
            if (kv.isEmpty()) continue
            val eq = kv.indexOf('=')
            val k = if (eq < 0) kv else kv.substring(0, eq)
            val v = if (eq < 0) "" else kv.substring(eq + 1)
            out.getOrPut(java.net.URLDecoder.decode(k, "UTF-8")) { mutableListOf() }
                .add(java.net.URLDecoder.decode(v, "UTF-8"))
        }
        return out
    }
}

/**
 * Convert colors between hex, rgb, and hsl. Use to: normalize a
 * user-provided color name, generate a darker/lighter variant,
 * answer "what's the hex for cornflower blue?".
 */
class ColorConvertTool(ctx: WeftContext) :
    WeftTool<ColorConvertTool.Args, ColorConvertTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "color_convert",
            description = "Convert a color across hex (#RRGGBB / #AARRGGBB), rgb (r,g,b 0-255), " +
                "hsl (h 0-360, s/l 0-100%). Specify input via 'from' (hex/rgb/hsl) and 'to'.",
            requiredParameters = listOf(
                ToolParameterDescriptor("from", "hex / rgb / hsl.", ToolParameterType.String),
                ToolParameterDescriptor("to", "hex / rgb / hsl.", ToolParameterType.String),
                ToolParameterDescriptor(
                    "value",
                    "Input value: '#3399ff' / '51,153,255' / '210,100,60'.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val from: String, val to: String, val value: String)

    @Serializable
    data class Result(val ok: Boolean, val value: String? = null, val error: String? = null)

    override suspend fun executeWeft(args: Args): Result = runCatching {
        val rgb = when (args.from.lowercase()) {
            "hex" -> parseHex(args.value)
            "rgb" -> parseRgb(args.value)
            "hsl" -> hslToRgb(parseHsl(args.value))
            else -> error("from must be hex/rgb/hsl")
        }
        val out = when (args.to.lowercase()) {
            "hex" -> formatHex(rgb)
            "rgb" -> "${rgb[0]},${rgb[1]},${rgb[2]}"
            "hsl" -> {
                val hsl = rgbToHsl(rgb)
                "${hsl[0].roundToInt()},${(hsl[1] * MAX_PERCENT).roundToInt()},${(hsl[2] * MAX_PERCENT).roundToInt()}"
            }
            else -> error("to must be hex/rgb/hsl")
        }
        Result(ok = true, value = out)
    }.getOrElse { Result(ok = false, error = it.message ?: "Conversion failed.") }

    private fun parseHex(s: String): IntArray {
        val cleaned = s.trim().removePrefix("#")
        val full = when (cleaned.length) {
            3 -> cleaned.map { "$it$it" }.joinToString("")   // #abc → aabbcc
            6, 8 -> cleaned.takeLast(MAX_HEX_RGB_LEN)
            else -> error("hex must be #RGB, #RRGGBB, or #AARRGGBB")
        }
        return intArrayOf(
            full.substring(0, 2).toInt(HEX_RADIX),
            full.substring(2, 4).toInt(HEX_RADIX),
            full.substring(4, 6).toInt(HEX_RADIX),
        )
    }

    private fun parseRgb(s: String): IntArray {
        val parts = s.split(',').map { it.trim().toInt() }
        require(parts.size == 3) { "rgb must be 'r,g,b'" }
        return parts.map { it.coerceIn(0, MAX_BYTE) }.toIntArray()
    }

    private fun parseHsl(s: String): DoubleArray {
        val parts = s.split(',').map { it.trim().removeSuffix("%").toDouble() }
        require(parts.size == 3) { "hsl must be 'h,s,l'" }
        return doubleArrayOf(
            parts[0].rem(HUE_FULL_DEG),
            (parts[1] / MAX_PERCENT).coerceIn(0.0, 1.0),
            (parts[2] / MAX_PERCENT).coerceIn(0.0, 1.0),
        )
    }

    private fun formatHex(rgb: IntArray): String =
        "#%02x%02x%02x".format(rgb[0], rgb[1], rgb[2])

    @Suppress("MagicNumber")
    private fun rgbToHsl(rgb: IntArray): DoubleArray {
        val r = rgb[0] / 255.0
        val g = rgb[1] / 255.0
        val b = rgb[2] / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        if (max == min) return doubleArrayOf(0.0, 0.0, l)
        val d = max - min
        val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
        var h = when (max) {
            r -> ((g - b) / d) + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        h *= 60
        return doubleArrayOf(h, s, l)
    }

    @Suppress("MagicNumber")
    private fun hslToRgb(hsl: DoubleArray): IntArray {
        val (h, s, l) = hsl
        if (s == 0.0) {
            val v = (l * 255).roundToInt()
            return intArrayOf(v, v, v)
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val hk = h / 360
        return intArrayOf(
            (hueToRgb(p, q, hk + 1.0 / 3) * 255).roundToInt(),
            (hueToRgb(p, q, hk) * 255).roundToInt(),
            (hueToRgb(p, q, hk - 1.0 / 3) * 255).roundToInt(),
        )
    }

    @Suppress("MagicNumber")
    private fun hueToRgb(p: Double, q: Double, t: Double): Double {
        var tt = t
        if (tt < 0) tt += 1
        if (tt > 1) tt -= 1
        return when {
            tt < 1.0 / 6 -> p + (q - p) * 6 * tt
            tt < 0.5 -> q
            tt < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - tt) * 6
            else -> p
        }
    }

    private companion object {
        const val HEX_RADIX = 16
        const val MAX_BYTE = 255
        const val MAX_PERCENT = 100.0
        const val HUE_FULL_DEG = 360.0
        const val MAX_HEX_RGB_LEN = 6
    }
}

/**
 * Random number / random selection. Seedable for reproducible tests.
 * Use for: dice rolls, pick-a-card, name a random thing, generate a
 * fresh ID without crypto.
 */
class RandomChoiceTool(ctx: WeftContext) :
    WeftTool<RandomChoiceTool.Args, RandomChoiceTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "random_choice",
            description = "Pick a random integer in [min,max] inclusive, or pick a random " +
                "element from a comma-separated list. Use 'op=int' or 'op=pick'. Seedable " +
                "for reproducibility.",
            requiredParameters = listOf(
                ToolParameterDescriptor("op", "int or pick.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("min", "Inclusive min for op=int.", ToolParameterType.Integer),
                ToolParameterDescriptor("max", "Inclusive max for op=int.", ToolParameterType.Integer),
                ToolParameterDescriptor(
                    "choices",
                    "Comma-separated list for op=pick.",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor("seed", "Optional seed for reproducibility.", ToolParameterType.Integer),
            ),
        ),
    ) {

    @Serializable
    data class Args(
        val op: String,
        val min: Int? = null,
        val max: Int? = null,
        val choices: String? = null,
        val seed: Int? = null,
    )

    @Serializable
    data class Result(
        val ok: Boolean,
        val intValue: Int? = null,
        val pickValue: String? = null,
        val error: String? = null,
    )

    override suspend fun executeWeft(args: Args): Result {
        val rng = args.seed?.let { Random(it.toLong()) } ?: Random.Default
        return when (args.op.lowercase()) {
            "int" -> {
                val lo = args.min ?: 0
                val hi = args.max ?: return Result(ok = false, error = "max required")
                if (hi < lo) return Result(ok = false, error = "max < min")
                Result(ok = true, intValue = rng.nextInt(lo, hi + 1))
            }
            "pick" -> {
                val options = args.choices?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: return Result(ok = false, error = "choices required")
                if (options.isEmpty()) return Result(ok = false, error = "no choices")
                Result(ok = true, pickValue = options[rng.nextInt(options.size)])
            }
            else -> Result(ok = false, error = "Unknown op '${args.op}'")
        }
    }
}

/**
 * Dot-path JSON query. Use to: pull a field from a tool's prior
 * structured output without re-asking, navigate a deep response,
 * extract data from a `network_fetch` result.
 *
 * Path syntax: `a.b.c` for objects, `a.0.name` for arrays. Returns
 * the value as a JSON-encoded string (or null when path doesn't
 * resolve).
 */
class JsonQueryTool(ctx: WeftContext) :
    WeftTool<JsonQueryTool.Args, JsonQueryTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "json_query",
            description = "Query a JSON value with a dot-path: 'a.b.0.name' navigates " +
                "objects + arrays. Returns the value as JSON text, or null if the path " +
                "doesn't resolve.",
            requiredParameters = listOf(
                ToolParameterDescriptor("json", "JSON-encoded string.", ToolParameterType.String),
                ToolParameterDescriptor(
                    "path",
                    "Dot path: 'a.b.c' or 'a.0.name'. Empty string = root.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val json: String, val path: String)

    @Serializable
    data class Result(val ok: Boolean, val value: String? = null, val error: String? = null)

    private val parser = Json { ignoreUnknownKeys = true }

    override suspend fun executeWeft(args: Args): Result = runCatching {
        var node: JsonElement? = parser.parseToJsonElement(args.json)
        if (args.path.isNotBlank()) {
            for (segment in args.path.split('.')) {
                if (segment.isEmpty()) continue
                node = when (val current = node) {
                    is JsonObject -> current[segment]
                    is JsonArray -> {
                        val idx = segment.toIntOrNull() ?: return@runCatching Result(ok = true, value = null)
                        current.getOrNull(idx)
                    }
                    is JsonPrimitive, null -> null
                }
                if (node == null) return@runCatching Result(ok = true, value = null)
            }
        }
        Result(ok = true, value = node?.toString())
    }.getOrElse { Result(ok = false, error = it.message ?: "Query failed.") }
}
