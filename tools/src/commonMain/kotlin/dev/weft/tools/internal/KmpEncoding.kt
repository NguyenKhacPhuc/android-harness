package dev.weft.tools.internal

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Internal KMP-friendly replacements for the JVM-only encoding /
 * percent-encoding APIs the text + network tools used to reach into
 * (`java.util.Base64`, `java.net.URLEncoder`, `java.net.URLDecoder`).
 *
 * The Base64 piece is just a thin wrapper over `kotlin.io.encoding.Base64`
 * so call sites don't have to repeat the `@OptIn(ExperimentalEncodingApi)`.
 * The percent-encoder follows RFC 3986 unreserved-character semantics
 * matching `URLEncoder.encode(s, "UTF-8")` for the characters Koog and
 * substrate tools generate (mostly ASCII path/query bits).
 *
 * Not exhaustive — `URLEncoder.encode` historically maps space to `+`
 * (the application/x-www-form-urlencoded behavior). We keep that
 * behavior here so the tool output is byte-compat with the JVM impl.
 */

@OptIn(ExperimentalEncodingApi::class)
internal fun base64Encode(bytes: ByteArray): String = Base64.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
internal fun base64Decode(text: String): ByteArray = Base64.decode(text)

/**
 * Percent-encode [text] as UTF-8 bytes matching the behavior of
 * `java.net.URLEncoder.encode(text, "UTF-8")`. Space becomes '+'.
 */
internal fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> append(c)
            c == '-' || c == '_' || c == '.' || c == '*' -> append(c)
            c == ' ' -> append('+')
            else -> {
                append('%')
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
    }
}

/**
 * Percent-decode [text] back to its original string, matching the
 * behavior of `java.net.URLDecoder.decode(text, "UTF-8")`. '+' is
 * decoded as a space.
 */
internal fun percentDecode(text: String): String {
    val bytes = ByteArray(text.length)
    var written = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '+' -> {
                bytes[written++] = ' '.code.toByte()
                i++
            }
            c == '%' && i + 2 < text.length -> {
                bytes[written++] = ((digit(text[i + 1]) shl 4) or digit(text[i + 2])).toByte()
                i += 3
            }
            else -> {
                bytes[written++] = c.code.toByte()
                i++
            }
        }
    }
    return bytes.copyOf(written).decodeToString()
}

private fun digit(c: Char): Int = when (c) {
    in '0'..'9' -> c.code - '0'.code
    in 'a'..'f' -> c.code - 'a'.code + 10
    in 'A'..'F' -> c.code - 'A'.code + 10
    else -> error("Invalid percent-encoding hex char: $c")
}

private val HEX = "0123456789ABCDEF".toCharArray()

/**
 * Hex-encode the bytes as a lowercase string. Mirrors the
 * `bytes.joinToString("") { "%02x".format(it) }` JVM idiom the
 * pre-KMP tool code used — `String.format` is JVM-only, this helper
 * works in commonMain.
 */
internal fun ByteArray.toHexString(): String = buildString(size * 2) {
    for (byte in this@toHexString) {
        val b = byte.toInt() and 0xFF
        append(HEX_LOWER[b ushr 4])
        append(HEX_LOWER[b and 0x0F])
    }
}

private val HEX_LOWER = "0123456789abcdef".toCharArray()
