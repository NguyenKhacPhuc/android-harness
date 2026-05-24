package dev.weft.contracts

import kotlinx.serialization.Serializable

/**
 * Bridges scripts to the UI host. The platform UI (Compose on Android,
 * SwiftUI on iOS) implements this; scripts call it through ScriptContext
 * to ask the user questions, confirm destructive actions, or push UI updates.
 *
 * All methods are suspending: askUser and confirmDestructive suspend until
 * the user answers (or cancels); emit returns once the host has accepted
 * the UIUpdate for rendering.
 */
interface UiBridge {
    /**
     * Ask the user a question. Suspends until the user answers via the UI.
     * The host is responsible for rendering an appropriate prompt for [kind].
     */
    suspend fun askUser(question: String, kind: AskKind, options: List<String> = emptyList()): UserAnswer

    /**
     * Show a confirmation dialog for a destructive action. Returns true on
     * confirm, false on cancel. The executor calls this automatically for
     * any script with destructive=true.
     */
    suspend fun confirmDestructive(action: String, body: String? = null): Boolean

    /**
     * Show an informational dialog and suspend until the user dismisses it.
     * No answer expected — the dialog has a single dismiss button.
     */
    suspend fun showInfo(title: String, body: String? = null)

    /** Push a UIUpdate to the host (Navigate, Overlay, etc.). */
    suspend fun emit(update: UIUpdate)

    /**
     * Validate a component tree against the host's component registry +
     * any other policy (depth, recursion, mutually-exclusive props…)
     * BEFORE the host commits to rendering it. `ui_render` calls this
     * before `emit(UIUpdate.RenderTree(…))` so the LLM gets a structured,
     * actionable error result instead of a runtime crash mid-render.
     *
     * Default impl returns [TreeValidationResult.Ok] — hosts that don't
     * pre-validate just rely on `emit` failing if the tree is malformed.
     * Hosts that ship a known component set (the standard Compose
     * reference UI, for example) override this to surface "unknown
     * component", "missing required prop", "bad prop type" up front so
     * the LLM can fix it next turn without wasting a render.
     */
    suspend fun validateTree(tree: ComponentNode): TreeValidationResult =
        TreeValidationResult.Ok
}

/**
 * Outcome of [UiBridge.validateTree]. [Invalid.message] is shown to the
 * LLM verbatim as the tool error, so it should be specific + actionable:
 * which node, what's wrong, what to do next.
 */
@Serializable
sealed class TreeValidationResult {
    @Serializable
    data object Ok : TreeValidationResult()

    @Serializable
    data class Invalid(val message: String) : TreeValidationResult()
}

@Serializable
enum class AskKind { YES_NO, CHOICE, FREE_TEXT }

@Serializable
sealed class UserAnswer {
    @Serializable
    data class Text(val value: String) : UserAnswer()

    @Serializable
    data class Choice(val value: String) : UserAnswer()

    @Serializable
    data class YesNo(val value: Boolean) : UserAnswer()

    /** User dismissed the prompt without providing an answer. */
    @Serializable
    data object Cancelled : UserAnswer()
}

