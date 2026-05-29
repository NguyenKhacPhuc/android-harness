package dev.weft.harness.reliability

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Policy for retrying a failed agent turn. Used by WeftAgent.send() to
 * wrap the underlying Koog AIAgent.run() call.
 *
 * "Retryable" means the exception is transient (network blip, 5xx, 429
 * rate limit, timeout). Auth errors and validation errors are NOT retried.
 *
 * Defaults match the plan ([07-harness.md:51](07-harness.md)).
 */
data class RetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val baseDelay: Duration = DEFAULT_BASE_DELAY,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    /** Predicate: should this exception trigger a retry? Default uses [defaultIsRetryable]. */
    val isRetryable: (Throwable) -> Boolean = ::defaultIsRetryable,
) {
    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        val DEFAULT_BASE_DELAY: Duration = 1.seconds
        val DEFAULT_MAX_DELAY: Duration = 30.seconds
        const val DEFAULT_JITTER_FRACTION: Double = 0.25
    }
}

/**
 * Heuristic for "this is probably a transient failure." Conservative —
 * we'd rather under-retry than retry user-facing errors.
 */
fun defaultIsRetryable(t: Throwable): Boolean {
    val msg = t.message.orEmpty().lowercase()
    return when {
        // Auth / validation never retried — the request itself is wrong.
        "401" in msg || "authentication" in msg -> false
        "400" in msg || "invalid_request" in msg -> false
        "schema" in msg || "validation" in msg -> false

        // Transient network / server / rate-limit signals.
        "5xx" in msg -> true
        "500" in msg || "502" in msg || "503" in msg || "504" in msg -> true
        "529" in msg || "overload" in msg -> true
        "429" in msg || "rate" in msg -> true
        "timeout" in msg -> true
        "connection" in msg -> true
        "unreachable" in msg || "unknown host" in msg -> true

        // Default: don't retry. Surfaces unexpected errors to the user fast.
        else -> false
    }
}
