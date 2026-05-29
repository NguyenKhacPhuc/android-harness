package dev.weft.oauth

/**
 * Cryptographically-secure random bytes. Used by [Pkce] to generate
 * the code verifier and state nonce.
 *
 * - JVM/Android: `java.security.SecureRandom.nextBytes`.
 * - iOS: `Security.SecRandomCopyBytes(kSecRandomDefault, …)`.
 */
internal expect fun oauthSecureRandom(size: Int): ByteArray

/**
 * SHA-256 digest. Used by [Pkce] to derive the S256 code challenge
 * from the verifier.
 *
 * - JVM/Android: `MessageDigest.getInstance("SHA-256").digest`.
 * - iOS: `CommonCrypto.CC_SHA256`.
 */
internal expect fun oauthSha256(bytes: ByteArray): ByteArray
