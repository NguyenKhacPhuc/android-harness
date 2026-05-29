package dev.weft.oauth

import java.security.MessageDigest
import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun oauthSecureRandom(size: Int): ByteArray {
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
}

internal actual fun oauthSha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
