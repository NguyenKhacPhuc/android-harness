package dev.weft.tools.internal

import java.security.MessageDigest

internal actual fun computeDigest(algorithm: String, data: ByteArray): ByteArray =
    MessageDigest.getInstance(algorithm).digest(data)
