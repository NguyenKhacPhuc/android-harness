package dev.weft.harness.reliability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Simple consecutive-failure circuit breaker.
 *
 * State machine:
 *   - CLOSED (normal): each failure increments a counter. After
 *     [failureThreshold] consecutive failures, transition to OPEN.
 *   - OPEN: all calls short-circuit immediately for [openDuration]. After
 *     that, transition to HALF_OPEN.
 *   - HALF_OPEN: a single call is allowed through. Success → CLOSED + reset
 *     counter. Failure → OPEN.
 *
 * Suitable for guarding against extended Anthropic outages — if 3 calls in a
 * row fail, the next 60s of calls fast-fail instead of pounding the API.
 */
class CircuitBreaker(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    /**
     * How long the breaker stays in [State.Open] before allowing a single
     * HALF_OPEN probe. Exposed so UI surfaces (banners, retry timers) can
     * compute "retry in Xs" without re-reading config from elsewhere.
     */
    val openDuration: Duration = DEFAULT_OPEN_DURATION,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Closed(failureCount = 0))

    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Check whether a call should be allowed through. If the breaker is
     * OPEN and the cooldown has elapsed, transitions to HALF_OPEN and
     * permits a single probe.
     */
    fun allowCall(): Boolean = when (val s = _state.value) {
        is State.Closed -> true
        is State.HalfOpen -> false                                // only one probe at a time
        is State.Open -> {
            if (nowEpochMs() - s.openedAtEpochMs >= openDuration.inWholeMilliseconds) {
                _state.value = State.HalfOpen
                true
            } else {
                false
            }
        }
    }

    fun recordSuccess() {
        _state.value = State.Closed(failureCount = 0)
    }

    fun recordFailure() {
        _state.value = when (val s = _state.value) {
            is State.HalfOpen -> State.Open(openedAtEpochMs = nowEpochMs())
            is State.Open -> s
            is State.Closed -> {
                val next = s.failureCount + 1
                if (next >= failureThreshold) State.Open(openedAtEpochMs = nowEpochMs()) else State.Closed(failureCount = next)
            }
        }
    }

    sealed class State {
        data class Closed(val failureCount: Int) : State()
        data class Open(val openedAtEpochMs: Long) : State()
        data object HalfOpen : State()
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD: Int = 3
        val DEFAULT_OPEN_DURATION: Duration = 60.seconds
    }
}

class CircuitBreakerOpenException(message: String = "Circuit breaker is open") : RuntimeException(message)
