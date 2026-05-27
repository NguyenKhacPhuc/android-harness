package dev.weft.contracts

/**
 * Session-wide policy for how tool calls get gated. Mirrors Claude Code's
 * permission modes: a single knob the user (or app code) can flip to
 * change the gate's behaviour for the whole session without per-tool
 * config.
 *
 * Tools consult the current mode via [WeftCredentialProvider]'s sibling
 * supplier on `WeftContext.approvalMode`. Default = [Default].
 */
public enum class ApprovalMode {
    /**
     * Normal behaviour: [ToolRisk.Destructive] tools prompt via
     * [UiBridge.confirmDestructive]; [ToolRisk.Read] and [ToolRisk.Write]
     * run without UI prompt (permission gates still apply).
     */
    Default,

    /**
     * Power-user mode. Skip the destructive-confirm prompt entirely.
     * Permission gates still apply (OS won't grant CONTACTS without
     * runtime consent regardless of mode). Use when the user has
     * explicitly opted in for a session.
     */
    Yolo,

    /**
     * Strict observation mode. Anything classified as [ToolRisk.Write] or
     * [ToolRisk.Destructive] throws immediately without running. Useful
     * for "what would you do" exploration, or when a parent agent
     * delegates to a sub-agent it doesn't fully trust.
     */
    ReadOnly,

    /**
     * Inverse of [Yolo]. Even [ToolRisk.Write] tools require a confirm
     * prompt before running. Useful when shipping to less-technical
     * users who want a beat between "the AI proposed it" and "the AI
     * did it".
     */
    ConfirmAllWrites,

    /**
     * Suggest → confirm → collect → build. The agent reads, asks, and
     * proposes a structured [Plan] via the substrate's `exit_plan_mode`
     * tool, but cannot run [ToolRisk.Write] or [ToolRisk.Destructive]
     * tools directly — they're gated until the user approves the plan
     * and the mode flips back to [Default].
     *
     * Tools tagged `planAware = true` (the plan-proposal tool itself,
     * `ui_ask`) are allowed through so the agent can still gather
     * information and surface the proposal. [ToolRisk.Read] tools are
     * always allowed.
     */
    Plan,
}
