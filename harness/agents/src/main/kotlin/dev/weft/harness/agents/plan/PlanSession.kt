package dev.weft.harness.agents.plan

import dev.weft.contracts.ApprovalMode
import dev.weft.contracts.ApprovalModeHolder
import dev.weft.contracts.InMemoryPlanStore
import dev.weft.contracts.PlanState
import dev.weft.contracts.PlanStore
import dev.weft.harness.agents.WeftAgent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

/**
 * Drives the suggest → confirm → collect → build workflow.
 *
 * Bundle one of these alongside the [WeftAgent] and pass its
 * [approvalMode] + [planStore] to both [WeftAgent] and the agent's
 * [dev.weft.tools.WeftContext]. Then the host UI calls:
 *
 *   - [enter] when the user opts in (e.g. taps a "Plan first" toggle).
 *     Flips the mode to [ApprovalMode.Plan].
 *   - The user types their request → agent reads / asks / drafts →
 *     calls `exit_plan_mode` → tool suspends on [UiBridge.confirmPlan].
 *   - On approve / refine / cancel the tool flips the mode itself + the
 *     [planStore] reflects the latest state. No host action needed for
 *     the common case.
 *
 * [forceCancel] is provided for "the user closed the screen" — flips
 * the mode + clears the store without waiting for the tool to return.
 *
 * Subscribe to [isActive] to drive UI affordances (the "Plan mode" chip
 * in the input bar, for example).
 */
public class PlanSession(
    public val approvalMode: ApprovalModeHolder = ApprovalModeHolder(),
    public val planStore: PlanStore = InMemoryPlanStore(),
) {

    /** Enter plan mode. Idempotent. */
    public fun enter() {
        approvalMode.set(ApprovalMode.Plan)
    }

    /**
     * Force-leave plan mode without going through the tool flow. Use
     * when the user dismisses the plan screen / aborts. Mode flips
     * back to [ApprovalMode.Default] and the store is cleared so the
     * next session starts fresh.
     */
    public fun forceCancel() {
        approvalMode.set(ApprovalMode.Default)
        planStore.clear()
    }

    /**
     * Manually approve the current plan and tell the agent to execute.
     * Use ONLY when the host bypasses the substrate's
     * [dev.weft.contracts.UiBridge.confirmPlan] callback (e.g. a fully
     * custom plan UI). In the normal flow the `exit_plan_mode` tool
     * handles the mode flip itself; calling this on top would queue an
     * extra user turn.
     */
    public suspend fun approveAndExecute(agent: WeftAgent): String {
        val snapshot = planStore.current()
        val plan = snapshot.plan
        check(plan != null) { "Cannot approve — no plan in store." }
        planStore.markApproved()
        approvalMode.set(ApprovalMode.Default)
        return agent.send(
            "Plan approved. Execute it now, step by step.\n\n" +
                plan.toExecutionInstructions(),
        )
    }

    /**
     * Send refinement feedback when the host owns the plan UI. Marks
     * the store as drafting (the `state` collector will update) and
     * pushes the feedback into the agent as a new turn. Mode stays in
     * [ApprovalMode.Plan] so the agent's next `exit_plan_mode` call
     * goes through the same gate.
     */
    public suspend fun refine(agent: WeftAgent, feedback: String): String {
        planStore.markRefining(feedback)
        return agent.send("Refine the plan based on this feedback: $feedback")
    }

    /**
     * Live `true` whenever the session is currently in plan mode.
     * Drive the input-bar chip / status indicator from this. Cold flow
     * — [scope] specifies who owns the subscription.
     */
    public fun isActive(scope: CoroutineScope): StateFlow<Boolean> =
        approvalMode.state
            .map { it == ApprovalMode.Plan }
            .stateIn(scope, SharingStarted.Eagerly, approvalMode.current() == ApprovalMode.Plan)

    /**
     * Convenience: `true` when there's an awaiting-approval plan that
     * the host should be rendering. UIs gate the plan-review sheet on
     * this.
     */
    public fun isAwaitingApproval(scope: CoroutineScope): StateFlow<Boolean> =
        planStore.state
            .map { it.state == PlanState.AwaitingApproval }
            .stateIn(scope, SharingStarted.Eagerly, planStore.current().state == PlanState.AwaitingApproval)
}
