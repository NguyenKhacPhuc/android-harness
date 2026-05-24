package dev.weft.harness.agents.routing

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart

/**
 * Picks the LLM model for a given turn.
 *
 * Routing is **per-turn**: the chosen model handles the initial user
 * query *and* every tool-result follow-up inside that turn's loop.
 * Per-call routing inside the loop (e.g., Haiku decides which tool to
 * call → Sonnet synthesises) would require switching model inside the
 * strategy's nodes; that's a follow-up.
 *
 * Implementations should be cheap to call — [route] runs once per
 * [dev.weft.harness.agents.WeftAgent.send] and should not perform LLM calls
 * itself unless that's explicitly the routing strategy (classifier-
 * based routers). The default [DefaultModelRouter] uses only free
 * deterministic signals.
 */
public interface ModelRouter {
    /** Pick a model for the upcoming turn. */
    public suspend fun route(context: RoutingContext): LLModel
}

/**
 * Inputs available to a [ModelRouter] when picking the model for a turn.
 *
 * @property userText the user's current message (post volatile-prefix
 *   merge). Used for length-based and pattern-based rules.
 * @property attachments multimodal attachments going out with this
 *   message. Forces a vision-capable model when non-empty.
 * @property historyTurns total turns already in this conversation
 *   (`history.size` on [dev.weft.harness.agents.WeftAgent]). Lets routers
 *   distinguish "fresh chat" (often classification) from "deep thread"
 *   (often reasoning).
 * @property availableTools the registered tool catalog. Routers can
 *   bias toward heavier models when tool-heavy turns are likely (not
 *   used by the default impl but exposed for custom routers).
 * @property pool the provider's model pool — the only set of models
 *   the router is allowed to return from.
 */
public data class RoutingContext(
    public val userText: String,
    public val attachments: List<MessagePart.Attachment>,
    public val historyTurns: Int,
    public val availableTools: List<ToolDescriptor>,
    public val pool: ModelPool,
    /**
     * Optional per-call tier override. Set by the caller via
     * [dev.weft.harness.agents.WeftAgent.send]'s `modelTier` parameter.
     * [DefaultModelRouter] honors it first, before its other rules.
     * Custom routers MAY ignore the hint, but apps that built their UX
     * around the override (e.g., a "force Opus" chip) will then be
     * silently overridden — usually not what you want.
     */
    public val tierHint: ModelTier? = null,
)

/**
 * The provider-specific menu of models a router can pick from.
 *
 * `cheap` and `standard` are required; `vision` and `heavy` default to
 * `standard` for providers where one model handles everything. Built
 * by [dev.weft.android.WeftRuntime.buildExecutorFor] based on the
 * active [dev.weft.contracts.ProviderKind].
 *
 * The router contract is: callers MUST pick from one of these models.
 * Returning anything else is a bug (the substrate has no other models
 * configured for the active provider's executor).
 */
public data class ModelPool(
    public val cheap: LLModel,
    public val standard: LLModel,
    public val vision: LLModel = standard,
    public val heavy: LLModel = standard,
)

/** Trivial [ModelRouter] that always returns the same model. */
public class StaticModelRouter(private val model: LLModel) : ModelRouter {
    override suspend fun route(context: RoutingContext): LLModel = model
}
