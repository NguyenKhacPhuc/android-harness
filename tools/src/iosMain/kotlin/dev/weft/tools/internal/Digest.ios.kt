package dev.weft.tools.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA512
import platform.CoreCrypto.CC_SHA512_DIGEST_LENGTH

/**
 * iOS actual for [computeDigest]. Bridges to CommonCrypto's CC_*
 * family. Algorithm names match the JVM ones the call sites use
 * ("MD5", "SHA-1", "SHA-256", "SHA-512"). The CC functions operate
 * on UByte pointers — we allocate scratch in a `memScoped` block,
 * pass the input via `refTo`, and read the digest bytes back into a
 * `ByteArray` that matches the JVM return shape.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun computeDigest(algorithm: String, data: ByteArray): ByteArray = memScoped {
    when (algorithm.uppercase()) {
        "MD5" -> {
            val out = allocArray<UByteVar>(CC_MD5_DIGEST_LENGTH)
            CC_MD5(data.refTo(0), data.size.convert(), out)
            out.readBytes(CC_MD5_DIGEST_LENGTH)
        }
        "SHA-1", "SHA1" -> {
            val out = allocArray<UByteVar>(CC_SHA1_DIGEST_LENGTH)
            CC_SHA1(data.refTo(0), data.size.convert(), out)
            out.readBytes(CC_SHA1_DIGEST_LENGTH)
        }
        "SHA-256", "SHA256" -> {
            val out = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
            CC_SHA256(data.refTo(0), data.size.convert(), out)
            out.readBytes(CC_SHA256_DIGEST_LENGTH)
        }
        "SHA-512", "SHA512" -> {
            val out = allocArray<UByteVar>(CC_SHA512_DIGEST_LENGTH)
            CC_SHA512(data.refTo(0), data.size.convert(), out)
            out.readBytes(CC_SHA512_DIGEST_LENGTH)
        }
        else -> error("Unsupported algorithm '$algorithm' (supported: MD5, SHA-1, SHA-256, SHA-512)")
    }
}
