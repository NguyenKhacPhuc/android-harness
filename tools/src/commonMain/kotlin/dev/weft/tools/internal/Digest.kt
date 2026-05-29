package dev.weft.tools.internal

/**
 * KMP cryptographic-digest entry point used by `TextTransformTool`'s
 * `hash` action. Accepts the same algorithm names `MessageDigest` did
 * — "MD5", "SHA-1", "SHA-256", "SHA-512" — and returns the raw digest
 * bytes for the caller to hex-encode.
 *
 * JVM/Android `actual` defers to `java.security.MessageDigest` so the
 * Android tool behavior is byte-compat with the pre-KMP impl. iOS
 * `actual` uses CommonCrypto's CC_* functions. Algorithms outside the
 * four documented above throw — callers should validate at the tool
 * boundary before invoking.
 */
internal expect fun computeDigest(algorithm: String, data: ByteArray): ByteArray
