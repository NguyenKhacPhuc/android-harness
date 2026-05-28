package dev.weft.harness.reliability

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Run [block] with the given [RetryPolicy] and [CircuitBreaker]. Returns the
 * block's result on success. Throws:
 *   - [CircuitBreakerOpenException] if the breaker is OPEN.
 *   - The last failure if all retries are exhausted.
 *   - Any non-retryable exception immediately, without retrying.
 *
 * Exponential backoff: `baseDelay * 2^(attempt-1)`, capped at `maxDelay`,
 * with `±jitterFraction` of jitter applied multiplicatively.
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    breaker: CircuitBreaker,
    onAttemptFailed: suspend (attempt: Int, cause: Throwable, retryingIn: Long?) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T {
    if (!breaker.allowCall()) {
        throw CircuitBreakerOpenException("LLM circuit is open after consecutive failures. Try again shortly.")
    }

    var lastError: Throwable? = null
    for (attempt in 1..policy.maxAttempts) {
        try {
            val result = block()
            breaker.recordSuccess()
            return result
        } catch (t: Throwable) {
            lastError = t

            // Non-retryable: bail immediately. Auth errors, validation errors.
            if (!policy.isRetryable(t)) {
                breaker.recordFailure()
                throw t
            }

            val isLastAttempt = attempt == policy.maxAttempts
            if (isLastAttempt) {
                breaker.recordFailure()
                onAttemptFailed(attempt, t, null)
                throw t
            }

            val delayMs = computeDelay(policy, attempt)
            onAttemptFailed(attempt, t, delayMs)
            delay(delayMs)
        }
    }

    // Unreachable in practice; satisfies the compiler.
    throw lastError ?: IllegalStateException("withRetry exited the loop without success or failure")
}

private fun computeDelay(policy: RetryPolicy, attempt: Int): Long {
    val base = policy.baseDelay.inWholeMilliseconds
    val exponential = base shl (attempt - 1)              // base * 2^(attempt-1)
    val capped = min(exponential, policy.maxDelay.inWholeMilliseconds)
    val jitter = (capped * policy.jitterFraction).toLong()
    val jittered = capped + Random.nextLong(-jitter, jitter + 1)
    return jittered.coerceAtLeast(0)
}
