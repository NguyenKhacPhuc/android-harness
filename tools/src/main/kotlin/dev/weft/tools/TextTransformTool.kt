package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * One tool, many string transforms. Use INSTEAD OF generating
 * transformed text inline — eliminates LLM errors on slug
 * normalization, case conversion, base64, URL encoding, hex.
 *
 * Operations:
 *   - `upper`, `lower`, `title`, `swapcase` — case
 *   - `trim`, `trim_start`, `trim_end` — whitespace
 *   - `slug` — `Hello, World!` → `hello-world`
 *   - `reverse` — characters reversed
 *   - `base64_encode`, `base64_decode` — standard alphabet
 *   - `hex_encode`, `hex_decode` — utf-8 ↔ hex
 *   - `url_encode`, `url_decode` — percent-encoding
 *   - `length` — UTF-16 length (matches String.length)
 */
class TextTransformTool(ctx: WeftContext) :
    WeftTool<TextTransformTool.Args, TextTransformTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "text_transform",
            description = "Apply a named transform to text. ops: upper, lower, title, " +
                "swapcase, trim, trim_start, trim_end, slug, reverse, base64_encode, " +
                "base64_decode, hex_encode, hex_decode, url_encode, url_decode, length. " +
                "Use to avoid case/encoding errors.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "op",
                    "Transform name (see description).",
                    ToolParameterType.String,
                ),
                ToolParameterDescriptor("text", "Input text.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    data class Args(val op: String, val text: String)

    @Serializable
    data class Result(
        val ok: Boolean,
        val result: String? = null,
        val length: Int? = null,
        val error: String? = null,
    )

    override suspend fun executeWeft(args: Args): Result = runCatching {
        when (args.op.lowercase()) {
            "upper" -> Result(ok = true, result = args.text.uppercase())
            "lower" -> Result(ok = true, result = args.text.lowercase())
            "title" -> Result(ok = true, result = titleCase(args.text))
            "swapcase" -> Result(ok = true, result = swapCase(args.text))
            "trim" -> Result(ok = true, result = args.text.trim())
            "trim_start" -> Result(ok = true, result = args.text.trimStart())
            "trim_end" -> Result(ok = true, result = args.text.trimEnd())
            "slug" -> Result(ok = true, result = slugify(args.text))
            "reverse" -> Result(ok = true, result = args.text.reversed())
            "base64_encode" -> Result(
                ok = true,
                result = Base64.getEncoder().encodeToString(args.text.toByteArray()),
            )
            "base64_decode" -> Result(
                ok = true,
                result = String(Base64.getDecoder().decode(args.text)),
            )
            "hex_encode" -> Result(
                ok = true,
                result = args.text.toByteArray().joinToString("") { "%02x".format(it) },
            )
            "hex_decode" -> {
                val bytes = ByteArray(args.text.length / 2) { i ->
                    args.text.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte()
                }
                Result(ok = true, result = String(bytes))
            }
            "url_encode" -> Result(ok = true, result = URLEncoder.encode(args.text, "UTF-8"))
            "url_decode" -> Result(ok = true, result = URLDecoder.decode(args.text, "UTF-8"))
            "length" -> Result(ok = true, length = args.text.length)
            else -> Result(ok = false, error = "Unknown op '${args.op}'.")
        }
    }.getOrElse { Result(ok = false, error = it.message ?: "Transform failed.") }

    private fun titleCase(s: String): String = s.split(' ').joinToString(" ") { word ->
        if (word.isEmpty()) word
        else word[0].uppercase() + word.substring(1).lowercase()
    }

    private fun swapCase(s: String): String = s.map {
        when {
            it.isUpperCase() -> it.lowercaseChar()
            it.isLowerCase() -> it.uppercaseChar()
            else -> it
        }
    }.joinToString("")

    private fun slugify(s: String): String {
        val lowered = s.lowercase().trim()
        val collapsed = StringBuilder()
        var inSep = true
        for (ch in lowered) {
            if (ch.isLetterOrDigit()) {
                collapsed.append(ch)
                inSep = false
            } else if (!inSep) {
                collapsed.append('-')
                inSep = true
            }
        }
        return collapsed.toString().trim('-')
    }

    private companion object { const val HEX_RADIX = 16 }
}

/**
 * Cryptographic hash of a UTF-8 string. Supports MD5, SHA-1, SHA-256,
 * SHA-512. Returns lowercase hex. Use for: deduplication keys,
 * fingerprinting, checksum requests.
 *
 * NOT for password hashing — those need a real KDF (Argon2/PBKDF2),
 * not a plain hash. The substrate doesn't ship one.
 */
class HashTool(ctx: WeftContext) : WeftTool<HashTool.Args, HashTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "crypto_hash",
        description = "Hash a UTF-8 string with MD5, SHA-1, SHA-256, or SHA-512. Returns " +
            "lowercase hex. Use for dedup keys, fingerprints, checksums. NOT for password " +
            "hashing — those need a KDF the substrate doesn't ship.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "algorithm",
                "MD5, SHA-1, SHA-256, or SHA-512 (case-insensitive).",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor("text", "Input UTF-8 text.", ToolParameterType.String),
        ),
        optionalParameters = emptyList(),
    ),
) {

    @Serializable
    data class Args(val algorithm: String, val text: String)

    @Serializable
    data class Result(val ok: Boolean, val digest: String? = null, val error: String? = null)

    override suspend fun executeWeft(args: Args): Result = runCatching {
        val alg = args.algorithm.uppercase().replace("-", "").let { stripped ->
            when (stripped) {
                "MD5" -> "MD5"
                "SHA1" -> "SHA-1"
                "SHA256" -> "SHA-256"
                "SHA512" -> "SHA-512"
                else -> error("Unsupported algorithm '${args.algorithm}'.")
            }
        }
        val digest = MessageDigest.getInstance(alg)
        val bytes = digest.digest(args.text.toByteArray())
        Result(ok = true, digest = bytes.joinToString("") { "%02x".format(it) })
    }.getOrElse { Result(ok = false, error = it.message ?: "Hash failed.") }
}
