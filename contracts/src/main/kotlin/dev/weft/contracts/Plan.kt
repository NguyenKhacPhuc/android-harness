package dev.weft.contracts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * A structured proposal the agent surfaces to the user before doing any
 * write work. Mirrors Claude Code's plan-mode UX: the model spends a
 * turn (or several) exploring + drafting, calls the substrate's
 * `exit_plan_mode` tool with one of these, and the user reviews on a
 * surface the app renders before any [ToolRisk.Write] / [ToolRisk.Destructive]
 * tool runs.
 *
 * Keep [steps] under ~10 entries. The model adheres better to short
 * structured plans than long ones, and the UI is easier to scan.
 */
@Serializable
public data class Plan(
    /** One-line summary, e.g. "Build a habit tracker mini-app". */
    public val title: String,
    /**
     * Ordered execution steps. Each step is a small, reviewable chunk —
     * "Define the data model", "Add storage", "Build the screen". Avoid
     * single-line plans ("Build the app") and exhaustive task lists.
     */
    public val steps: List<PlanStep>,
    /**
     * Things the agent is uncertain about and wants the user to clarify
     * before committing. Empty list = the agent thinks the plan is
     * complete as drafted.
     */
    public val openQuestions: List<String> = emptyList(),
    /**
     * Assumptions the agent baked into the plan. Surfacing these here
     * lets the user spot disagreement early ("you assumed SQL — use
     * key-value instead").
     */
    public val assumptions: List<String> = emptyList(),
) {
    /**
     * Render a compact, plain-text form suitable for re-injecting into
     * the agent's next turn after approval ("Plan approved — execute
     * the following:"). Stable formatting so the model can parse it
     * back as a checklist.
     */
    public fun toExecutionInstructions(): String = buildString {
        append("Plan: ")
        appendLine(title)
        steps.forEachIndexed { i, step ->
            appendLine("${i + 1}. ${step.title}")
            if (step.rationale.isNotBlank()) appendLine("   why: ${step.rationale}")
            if (step.toolsUsed.isNotEmpty()) {
                appendLine("   tools: ${step.toolsUsed.joinToString(", ")}")
            }
        }
        if (assumptions.isNotEmpty()) {
            appendLine()
            appendLine("Assumptions:")
            assumptions.forEach { appendLine("- $it") }
        }
    }
}

@Serializable
public data class PlanStep(
    /** Short imperative, e.g. "Add the habit data model". */
    public val title: String,
    /**
     * One-sentence why. Helps the user catch reasoning errors without
     * inspecting the full step. Empty string is allowed for trivial
     * steps but discouraged.
     */
    public val rationale: String = "",
    /**
     * Tool names this step will call, in order. Used by the UI to show
     * "Step 3 will call data_update, ui_render" so the user can spot
     * surprises before approving. Empty list = step is informational
     * only (e.g. "Review the design").
     */
    public val toolsUsed: List<String> = emptyList(),
)

/** Where a plan is in its lifecycle. */
@Serializable
public enum class PlanState {
    /** No plan yet, or a previous one was rejected. */
    Empty,
    /** Agent has drafted a plan and is iterating on it. */
    Drafting,
    /** Plan was proposed via `exit_plan_mode`; waiting for the user. */
    AwaitingApproval,
    /** User approved; agent is (or will be) executing it. */
    Approved,
    /** User cancelled / rejected without refinement. */
    Rejected,
}

/**
 * User's response to a proposed [Plan]. Returned by
 * [UiBridge.confirmPlan] and surfaced to the agent as the
 * `exit_plan_mode` tool's result string.
 */
@Serializable
public sealed class PlanDecision {
    /** Approve as drafted. The agent should execute next. */
    @Serializable
    public data object Approved : PlanDecision()

    /**
     * Request changes. [feedback] is the user's note, fed back to the
     * agent as the tool result so the next turn re-drafts.
     */
    @Serializable
    public data class Refine(public val feedback: String) : PlanDecision()

    /** Abandon the plan entirely. The agent should stop. */
    @Serializable
    public data object Cancelled : PlanDecision()
}

/**
 * Holds the current draft plan plus its lifecycle state. The substrate
 * ships [InMemoryPlanStore] as a process-local default; apps that want
 * persistence across restarts implement on top of SQLDelight (similar to
 * `ConversationStore`).
 *
 * Observers — typically the app's plan-review screen — subscribe to
 * [state] and re-render whenever the agent's `exit_plan_mode` call
 * mutates it.
 */
public interface PlanStore {
    public val state: StateFlow<PlanSnapshot>

    /** Latest snapshot. Convenience for non-Flow consumers. */
    public fun current(): PlanSnapshot

    /** Replace the draft and move state to [PlanState.AwaitingApproval]. */
    public fun propose(plan: Plan)

    /** Mark the current plan approved (state → [PlanState.Approved]). */
    public fun markApproved()

    /**
     * Note that the user asked for changes. State → [PlanState.Drafting]
     * and [PlanSnapshot.lastFeedback] is set so the agent's next turn
     * sees what changed.
     */
    public fun markRefining(feedback: String)

    /** Mark the current plan rejected (state → [PlanState.Rejected]). */
    public fun markRejected()

    /** Drop the plan entirely and reset to [PlanState.Empty]. */
    public fun clear()
}

/**
 * Snapshot of a [PlanStore]. [plan] is null only when [state] is
 * [PlanState.Empty].
 */
@Serializable
public data class PlanSnapshot(
    public val plan: Plan?,
    public val state: PlanState,
    public val lastFeedback: String? = null,
) {
    public companion object {
        public val EMPTY: PlanSnapshot = PlanSnapshot(plan = null, state = PlanState.Empty)
    }
}

/**
 * Process-local [PlanStore]. Thread-safe via [MutableStateFlow]'s atomic
 * updates. Suitable for the substrate's default; production apps that
 * want plans to survive process death implement against SQLDelight.
 */
public class InMemoryPlanStore : PlanStore {
    private val _state: MutableStateFlow<PlanSnapshot> = MutableStateFlow(PlanSnapshot.EMPTY)
    override val state: StateFlow<PlanSnapshot> = _state.asStateFlow()

    override fun current(): PlanSnapshot = _state.value

    override fun propose(plan: Plan) {
        _state.value = PlanSnapshot(plan = plan, state = PlanState.AwaitingApproval)
    }

    override fun markApproved() {
        _state.value = _state.value.copy(state = PlanState.Approved)
    }

    override fun markRefining(feedback: String) {
        _state.value = _state.value.copy(
            state = PlanState.Drafting,
            lastFeedback = feedback,
        )
    }

    override fun markRejected() {
        _state.value = _state.value.copy(state = PlanState.Rejected)
    }

    override fun clear() {
        _state.value = PlanSnapshot.EMPTY
    }
}

/**
 * Mutable holder for the session-wide [ApprovalMode]. Replaces the
 * supplier-only pattern so [PlanSession][] and other controllers can
 * flip the mode in response to user actions (approving a plan, toggling
 * Yolo).
 *
 * Both [dev.weft.tools.WeftContext] (for tool gating) and the agent
 * loop (for prompt injection) read from the same holder, so a single
 * call to [set] takes effect across the whole session.
 */
public class ApprovalModeHolder(initial: ApprovalMode = ApprovalMode.Default) {
    private val _state: MutableStateFlow<ApprovalMode> = MutableStateFlow(initial)
    public val state: StateFlow<ApprovalMode> = _state.asStateFlow()
    public fun current(): ApprovalMode = _state.value
    public fun set(mode: ApprovalMode) { _state.value = mode }
}
