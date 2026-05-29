package dev.weft.oauth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * PKCE (RFC 7636) helpers. Public mobile clients can't safely hold a
 * client secret, so OAuth authorize+token requests prove possession of
 * a one-shot code verifier instead.
 *
 * The flow:
 *   1. Generate a high-entropy random `code_verifier` (we use 64 bytes).
 *   2. Compute `code_challenge = base64url(SHA-256(code_verifier))`.
 *   3. Send `code_challenge` + `code_challenge_method=S256` on the
 *      authorize request.
 *   4. Send the raw `code_verifier` on the token-exchange request.
 *
 * Only S256 is supported. `plain` is allowed by the spec for
 * compatibility but is strictly weaker; modern providers (Linear,
 * Google, GitHub, …) require S256 anyway.
 *
 * KMP — commonMain. Crypto primitives go through `oauthSecureRandom`
 * + `oauthSha256` expect/actual helpers (JVM = MessageDigest +
 * SecureRandom; iOS = CommonCrypto + Security.SecRandomCopyBytes).
 * Base64 URL-safe encoding uses kotlin.io.encoding.Base64.UrlSafe.
 */
internal object Pkce {

    /**
     * Generate a fresh code verifier. RFC 7636 §4.1 requires high-
     * entropy cryptographic randomness, 43–128 chars of unreserved
     * URL-safe alphabet. 64 random bytes → base64url-encoded gives 86
     * chars, comfortably inside the cap.
     */
    fun generateVerifier(): String =
        encodeUrlSafe(oauthSecureRandom(VERIFIER_BYTE_COUNT))

    /** Derive the S256 challenge: `BASE64URL(SHA256(verifier))`. */
    fun deriveChallenge(verifier: String): String =
        encodeUrlSafe(oauthSha256(verifier.encodeToByteArray()))

    /**
     * Generate a fresh `state` token used to bind the OAuth callback
     * back to the request we initiated. The provider returns this
     * verbatim on the redirect; we verify it matches what we sent.
     * Mismatch → reject the entire flow.
     */
    fun generateState(): String =
        encodeUrlSafe(oauthSecureRandom(STATE_BYTE_COUNT))

    /**
     * RFC 7636 base64url shape: URL-safe alphabet, no `=` padding.
     * kotlin.io.encoding.Base64.UrlSafe defaults to padded; strip the
     * trailing `=` chars manually to match the
     * `Base64.URL_SAFE or Base64.NO_PADDING` flags the Android impl
     * used.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeUrlSafe(bytes: ByteArray): String =
        Base64.UrlSafe.encode(bytes).trimEnd('=')

    private const val VERIFIER_BYTE_COUNT = 64
    private const val STATE_BYTE_COUNT = 16
}
