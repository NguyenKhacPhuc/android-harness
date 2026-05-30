package dev.weft.harness.agents

/**
 * Transient one-shot signals emitted by [WeftAgent].
 *
 * Effects are things that *happen at a moment in time* and don't
 * belong on the state object: surfacing a toast, telling the host the
 * quota cap was hit on this turn, signaling that the breaker opened.
 *
 * Hosts collect from [WeftAgent.effects] (a `SharedFlow`) inside a UI
 * scope and react — typically by emitting a host-level [Notify]-like
 * intent into their own MVI stack.
 *
 * State changes that should also be reflected on the [AgentState]
 * (e.g. the breaker entering Open) appear in BOTH places — once on the
 * state as a transition of [AgentState.breaker], once here as a
 * one-shot [BreakerOpened] for hosts that want to fire a snackbar.
 */
public sealed interface AgentEffect {

    /**
     * Free-form user-visible notification — surfaced for retry-attempt
     * messages, soft warnings, and other miscellaneous turn-time
     * signals that don't have a dedicated effect type.
     *
     * Hosts typically project this into their own snackbar/toast.
     */
    public data class Notify(public val message: String) : AgentEffect

    /**
     * Fired when [dev.weft.harness.cost.QuotaPolicy.check] returns
     * [dev.weft.harness.cost.QuotaState.Blocked] at the start of a
     * turn, after which the turn aborts with
     * [dev.weft.harness.cost.QuotaExceededException]. Hosts can
     * intercept to show a "you've used $X of $Y today" upgrade nudge.
     */
    public data class QuotaBlocked(
        public val usdToday: Double,
        public val capUsd: Double,
    ) : AgentEffect

    /**
     * Fired when the [dev.weft.harness.reliability.CircuitBreaker]
     * trips into the [dev.weft.harness.reliability.CircuitBreaker.State.Open]
     * state. The breaker's own [openDuration] is included so the host
     * can render a "retrying in Ns" countdown without re-reading
     * config.
     */
    public data class BreakerOpened(
        public val openedAtEpochMs: Long,
        public val openDurationMs: Long,
    ) : AgentEffect
}
