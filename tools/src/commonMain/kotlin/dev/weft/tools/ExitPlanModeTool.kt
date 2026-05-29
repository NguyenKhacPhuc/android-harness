package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.Plan
import dev.weft.contracts.PlanDecision
import dev.weft.contracts.PlanStep
import kotlinx.serialization.Serializable

/**
 * The substrate's plan-mode exit point. When the user is in
 * [dev.weft.contracts.ApprovalMode.Plan], the agent reads / asks /
 * drafts and then calls this tool with a structured [Plan]. The tool:
 *
 *   1. Writes the plan into [WeftContext.planStore] (state →
 *      `AwaitingApproval`) so the app's plan-review screen renders it.
 *   2. Calls [UiBridge.confirmPlan] and **suspends** until the user
 *      decides.
 *   3. On [PlanDecision.Approved]: marks the plan approved, flips
 *      [WeftContext.approvalMode] to `Default`, and returns a result
 *      string that primes the agent's next turn to execute.
 *   4. On [PlanDecision.Refine]: marks the plan drafting, returns the
 *      user's feedback as the tool result so the agent's next turn
 *      re-proposes.
 *   5. On [PlanDecision.Cancelled]: marks rejected, flips the mode
 *      back to `Default`, and returns a stop instruction.
 *
 * Tagged `planAware = true` so it's the one Write-class tool allowed
 * to run while in Plan mode.
 */
class ExitPlanModeTool(ctx: WeftContext) : WeftTool<ExitPlanModeTool.Args, String>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = "exit_plan_mode",
        description = "Propose a structured plan and wait for the user's decision. " +
            "Call this AFTER you've read, asked, and drafted a plan you're ready to " +
            "execute. The user can approve, request refinements, or cancel — only " +
            "execute write/destructive tools AFTER approval. Do NOT call this for " +
            "trivial requests that need no plan.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "title",
                description = "One-line summary of the plan, e.g. 'Build a habit tracker'.",
                type = ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                name = "steps",
                description = "Ordered execution steps. Each step is an object with " +
                    "{title, rationale, toolsUsed}. Keep under 10 steps.",
                type = ToolParameterType.List(
                    ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList()),
                ),
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "openQuestions",
                description = "Things you're uncertain about and want the user to clarify before approving.",
                type = ToolParameterType.List(ToolParameterType.String),
            ),
            ToolParameterDescriptor(
                name = "assumptions",
                description = "Assumptions baked into the plan so the user can flag disagreement.",
                type = ToolParameterType.List(ToolParameterType.String),
            ),
        ),
    ),
    sideEffecting = true,
    planAware = true,
) {

    @Serializable
    data class Args(
        val title: String,
        val steps: List<PlanStep>,
        val openQuestions: List<String> = emptyList(),
        val assumptions: List<String> = emptyList(),
    )

    override suspend fun executeWeft(args: Args): String {
        val plan = Plan(
            title = args.title,
            steps = args.steps,
            openQuestions = args.openQuestions,
            assumptions = args.assumptions,
        )
        ctx.planStore.propose(plan)
        return when (val decision = ui.confirmPlan(plan)) {
            is PlanDecision.Approved -> {
                ctx.planStore.markApproved()
                ctx.approvalMode.set(dev.weft.contracts.ApprovalMode.Default)
                buildString {
                    appendLine("Plan approved. Execute it now, step by step.")
                    appendLine()
                    append(plan.toExecutionInstructions())
                }
            }
            is PlanDecision.Refine -> {
                ctx.planStore.markRefining(decision.feedback)
                "Plan needs refinement. User feedback: ${decision.feedback}\n" +
                    "Call exit_plan_mode again with the revised plan."
            }
            is PlanDecision.Cancelled -> {
                ctx.planStore.markRejected()
                ctx.approvalMode.set(dev.weft.contracts.ApprovalMode.Default)
                "User cancelled the plan. Stop and acknowledge — do not retry."
            }
        }
    }
}
