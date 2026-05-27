package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Safe arithmetic evaluator. Use INSTEAD OF doing math in your head —
 * eliminates LLM digit-flip mistakes, especially for percentages,
 * conversions, and multi-step calculations.
 *
 * Supports: `+ - * / %` (mod), `^` (power), parentheses, and
 * functions `abs/sqrt/sin/cos/tan/ln/log/exp/floor/ceil/round/min/max/pow`.
 * Constants: `pi`, `e`. Whitespace ignored.
 *
 * No variables — pass a fully-substituted expression. No security
 * risk: this is a hand-written recursive-descent parser, NOT
 * JavaScript / scripting.
 */
public class MathEvalTool(ctx: WeftContext) : WeftTool<MathEvalTool.Args, MathEvalTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "math_eval",
        description = "Evaluate a math expression. Supports +-*/%^ parens, functions " +
            "abs/sqrt/sin/cos/tan/ln/log/exp/floor/ceil/round/min/max/pow, constants pi/e. " +
            "Use to avoid arithmetic mistakes — percentages, conversions, multi-step " +
            "calculations. No variables.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "expression",
                "The expression, e.g. '(450 * 0.18) + 12.5' or 'sqrt(2)*pi'.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {

    @Serializable
    public data class Args(val expression: String)

    @Serializable
    public data class Result(val ok: Boolean, val value: Double? = null, val error: String? = null)

    override suspend fun executeWeft(args: Args): Result = runCatching {
        val parser = Parser(args.expression)
        val value = parser.parseExpression()
        parser.requireEof()
        Result(ok = true, value = value)
    }.getOrElse { Result(ok = false, error = it.message ?: "parse error") }

    /**
     * Recursive-descent parser. Grammar:
     *   expression ::= term (('+' | '-') term)*
     *   term       ::= factor (('*' | '/' | '%') factor)*
     *   factor     ::= unary ('^' factor)?    // right-assoc
     *   unary      ::= ('+' | '-') unary | primary
     *   primary    ::= number | identifier ['(' args ')'] | '(' expression ')'
     *   args       ::= expression (',' expression)*
     */
    private class Parser(private val src: String) {
        private var pos: Int = 0

        fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                skipWhitespace()
                val op = peek() ?: return left
                if (op != '+' && op != '-') return left
                pos++
                val right = parseTerm()
                left = if (op == '+') left + right else left - right
            }
        }

        private fun parseTerm(): Double {
            var left = parseFactor()
            while (true) {
                skipWhitespace()
                val op = peek() ?: return left
                if (op != '*' && op != '/' && op != '%') return left
                pos++
                val right = parseFactor()
                left = when (op) {
                    '*' -> left * right
                    '/' -> left / right
                    '%' -> left % right
                    else -> error("unreachable")
                }
            }
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            skipWhitespace()
            if (peek() == '^') {
                pos++
                val exponent = parseFactor()
                return base.pow(exponent)
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            return when (peek()) {
                '+' -> { pos++; parseUnary() }
                '-' -> { pos++; -parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipWhitespace()
            val c = peek() ?: error("unexpected end of expression")
            if (c == '(') {
                pos++
                val v = parseExpression()
                skipWhitespace()
                require(peek() == ')') { "expected ')' at $pos" }
                pos++
                return v
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter()) return parseIdentifier()
            error("unexpected character '$c' at $pos")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            // Optional exponent: 1e10, 1.5e-3
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            return src.substring(start, pos).toDouble()
        }

        private fun parseIdentifier(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos).lowercase()
            skipWhitespace()
            if (peek() == '(') {
                pos++
                val args = mutableListOf<Double>()
                skipWhitespace()
                if (peek() != ')') {
                    args += parseExpression()
                    while (true) {
                        skipWhitespace()
                        if (peek() != ',') break
                        pos++
                        args += parseExpression()
                    }
                }
                skipWhitespace()
                require(peek() == ')') { "expected ')' after $name args" }
                pos++
                return applyFunction(name, args)
            }
            return when (name) {
                "pi" -> PI
                "e" -> kotlin.math.E
                else -> error("unknown identifier '$name'")
            }
        }

        private fun applyFunction(name: String, args: List<Double>): Double = when (name) {
            "abs" -> { require(args.size == 1); abs(args[0]) }
            "sqrt" -> { require(args.size == 1); sqrt(args[0]) }
            "sin" -> { require(args.size == 1); sin(args[0]) }
            "cos" -> { require(args.size == 1); cos(args[0]) }
            "tan" -> { require(args.size == 1); tan(args[0]) }
            "ln" -> { require(args.size == 1); ln(args[0]) }
            "log", "log10" -> { require(args.size == 1); log10(args[0]) }
            "exp" -> { require(args.size == 1); exp(args[0]) }
            "floor" -> { require(args.size == 1); floor(args[0]) }
            "ceil" -> { require(args.size == 1); ceil(args[0]) }
            "round" -> { require(args.size == 1); round(args[0]) }
            "min" -> { require(args.size >= 2); args.reduce(::min) }
            "max" -> { require(args.size >= 2); args.reduce(::max) }
            "pow" -> { require(args.size == 2); args[0].pow(args[1]) }
            else -> error("unknown function '$name'")
        }

        fun requireEof() {
            skipWhitespace()
            require(pos == src.length) { "unexpected trailing input at $pos: '${src.substring(pos)}'" }
        }

        private fun peek(): Char? = if (pos < src.length) src[pos] else null

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}
