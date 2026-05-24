package dev.weft.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the agent wants to do to the UI this turn, if anything.
 *
 * Produced by the substrate's UI tools (`ui_render`, `ui_notify`) and
 * consumed by the platform UI host (the app's `ComposeUiBridge` or
 * equivalent). [RenderTree] carries the LLM-composed [ComponentNode]
 * tree per ADR-007; [Overlay] carries ephemeral toasts/banners from
 * `ui_notify`; the legacy [Navigate]/[Replace]/[Patch]/[Dismiss] variants
 * predate ADR-007 and are kept for apps that still drive UI via
 * [ScreenSpec] templates instead of component trees.
 */
@Serializable
sealed class UIUpdate {
    /** Navigate to a new screen (push). */
    @Serializable
    data class Navigate(val screen: ScreenSpec) : UIUpdate()

    /** Replace the current screen entirely. */
    @Serializable
    data class Replace(val screen: ScreenSpec) : UIUpdate()

    /** Update specific props on the current screen without navigating. */
    @Serializable
    data class Patch(val patches: List<PropPatch>) : UIUpdate()

    /** Dismiss the current screen (pop, or specific destination). */
    @Serializable
    data class Dismiss(val to: String? = null) : UIUpdate()

    /** Show an ephemeral overlay (dialog, sheet, toast, banner). */
    @Serializable
    data class Overlay(val overlay: OverlaySpec) : UIUpdate()

    /**
     * Render an LLM-composed component tree (per ADR-007). The host walks
     * the tree and resolves each node against its component registry.
     *
     * Apps that want a "screen-with-back" model use [Navigate]/[Replace]
     * with `ScreenSpec(template = "tree", props = …)`; this variant is
     * for inline / current-surface rendering driven by the agent.
     */
    @Serializable
    data class RenderTree(
        val tree: ComponentNode,
        val agentContext: AgentContext? = null,
    ) : UIUpdate()

    @Serializable
    data object None : UIUpdate()
}

/**
 * A screen the agent has chosen to render, identified by template id with props.
 */
@Serializable
data class ScreenSpec(
    /** Template id, e.g. "Timer", "List", "Form". Must exist in the template registry. */
    val template: String,

    /** Template-specific props, validated against the template's prop schema by the host. */
    val props: JsonObject,

    /**
     * If non-null, this screen is agent-aware: events from the screen (button presses,
     * text input) flow back to the agent under this context tag.
     */
    val agentContext: AgentContext? = null,
)

@Serializable
data class AgentContext(
    val contextId: String,
    val systemPromptAddendum: String? = null,
    /** If non-null, restrict which scripts the agent may call from this screen. */
    val allowedTools: Set<String>? = null,
)

@Serializable
data class PropPatch(
    /** JSON pointer to the property being patched. */
    val path: String,
    val value: JsonElement,
)

@Serializable
data class OverlaySpec(
    val kind: OverlayKind,
    val title: String,
    val body: String? = null,
    val actions: List<OverlayAction> = emptyList(),
    /**
     * Auto-dismiss duration in milliseconds. Convention by [kind]:
     *   - TOAST: ≥ 0; default 3000ms when omitted (set to -1)
     *   - BANNER: ignored if 0 or negative — persists until user dismisses
     *   - DIALOG / SHEET: ignored
     */
    val durationMs: Long = -1,
)

@Serializable
enum class OverlayKind { DIALOG, SHEET, TOAST, BANNER }

@Serializable
data class OverlayAction(
    val label: String,
    /** Optional: tapping this action invokes the named tool with [params]. */
    val tool: String? = null,
    val params: JsonObject? = null,
    val isPrimary: Boolean = false,
    val isDestructive: Boolean = false,
)
