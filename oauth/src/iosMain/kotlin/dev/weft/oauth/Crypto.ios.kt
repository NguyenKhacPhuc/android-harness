package dev.weft.oauth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS actuals for the OAuth crypto primitives.
 *
 * Security.SecRandomCopyBytes is iOS's CSPRNG (backed by the kernel's
 * RNG); kSecRandomDefault picks the default generator. CommonCrypto's
 * CC_SHA256 fills a fixed-length digest into a UByte buffer that we
 * then read back into a Kotlin ByteArray.
 */

@OptIn(ExperimentalForeignApi::class)
internal actual fun oauthSecureRandom(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val result = SecRandomCopyBytes(kSecRandomDefault, size.convert(), bytes.refTo(0))
    check(result == 0) { "SecRandomCopyBytes failed with status $result" }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun oauthSha256(bytes: ByteArray): ByteArray = memScoped {
    val out = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
    CC_SHA256(bytes.refTo(0), bytes.size.convert(), out)
    out.readBytes(CC_SHA256_DIGEST_LENGTH)
}
