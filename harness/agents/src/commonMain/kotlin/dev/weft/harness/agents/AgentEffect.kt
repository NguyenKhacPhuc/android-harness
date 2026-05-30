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

    // ── Tool lifecycle ────────────────────────────────────────────────
    //
    // Mirror of the (now deprecated) `ToolEvent` SharedFlow on
    // [WeftAgent]. Subscribers that want to render tool bubbles in
    // chat or surface retry chatter filter [AgentEffect] on these
    // variants instead of subscribing to the legacy `events` flow.
    //
    // [AgentState.activeToolCalls] carries the in-flight snapshot for
    // the "what's running RIGHT NOW" question; these effects carry
    // the "what just happened" question. Most chat UIs need both.

    /**
     * A tool call is about to execute. [argsPreview] is the redacted +
     * length-capped argument blob — same value that goes into
     * [dev.weft.harness.observability.TraceStore].
     */
    public data class ToolStarting(
        public val toolName: String,
        public val argsPreview: String,
    ) : AgentEffect

    /**
     * A tool call finished successfully. Tool result preview isn't
     * surfaced on the effect (it goes to [TraceStore] only) — chat
     * bubbles only need the name.
     */
    public data class ToolCompleted(
        public val toolName: String,
    ) : AgentEffect

    /**
     * A tool call threw. [message] is redacted; safe to surface in UI.
     *
     * **`toolName == "llm.retry"`** is a synthetic effect emitted by
     * [WeftAgent]'s retry-with-circuit-breaker wrapper for failed LLM
     * attempts that are being retried. Chat UIs typically filter
     * these out of the scroll (the retry chatter would crowd the
     * bubble view); telemetry sinks keep them.
     */
    public data class ToolFailed(
        public val toolName: String,
        public val message: String,
    ) : AgentEffect
}
