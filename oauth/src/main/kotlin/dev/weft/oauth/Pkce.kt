package dev.weft.oauth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (RFC 7636) helpers. Public mobile clients can't safely hold a
 * client secret, so OAuth authorize+token requests prove possession of a
 * one-shot code verifier instead.
 *
 * The flow:
 *   1. Generate a high-entropy random `code_verifier` (we use 64 bytes).
 *   2. Compute `code_challenge = base64url(SHA-256(code_verifier))`.
 *   3. Send `code_challenge` + `code_challenge_method=S256` on the
 *      authorize request.
 *   4. Send the raw `code_verifier` on the token-exchange request.
 *
 * Only S256 is supported. `plain` is allowed by the spec for compatibility
 * but is strictly weaker; modern providers (Linear, Google, GitHub, …)
 * require S256 anyway.
 */
internal object Pkce {

    private val secureRandom = SecureRandom()

    /**
     * Generate a fresh code verifier. RFC 7636 §4.1 requires high-entropy
     * cryptographic randomness, 43–128 chars of unreserved URL-safe
     * alphabet. 64 random bytes → base64url-encoded gives 86 chars,
     * comfortably inside the cap.
     */
    fun generateVerifier(): String {
        val bytes = ByteArray(VERIFIER_BYTE_COUNT)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, BASE64_FLAGS)
    }

    /** Derive the S256 challenge: `BASE64URL(SHA256(verifier))`. */
    fun deriveChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, BASE64_FLAGS)
    }

    /**
     * Generate a fresh `state` token used to bind the OAuth callback back
     * to the request we initiated. The provider returns this verbatim on
     * the redirect; we verify it matches what we sent. Mismatch → reject
     * the entire flow.
     */
    fun generateState(): String {
        val bytes = ByteArray(STATE_BYTE_COUNT)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, BASE64_FLAGS)
    }

    // URL_SAFE + NO_WRAP + NO_PADDING produces the base64url alphabet
    // RFC 7636 expects — `-`, `_` instead of `+`, `/`, no `=` padding.
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private const val VERIFIER_BYTE_COUNT = 64
    private const val STATE_BYTE_COUNT = 16
}
