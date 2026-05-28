package dev.weft.contracts

import kotlinx.serialization.Serializable

/**
 * Semantic event fired by a rendered component back through the renderer
 * into the agent loop. Apps' UI implementations construct these from
 * native UI events (button taps, text-field changes, toggle flips) and
 * forward them to `WeftAgent.sendEvent(...)` to give the agent a
 * synthetic observation on the next turn (per ADR-007 §6).
 *
 * Lives in `:contracts` because the SDK consumes these on the way in
 * (`sendEvent` decomposes them into `action`, `sourceLabel`,
 * `fieldValues` primitives) and any UI implementation — Compose,
 * SwiftUI, server-rendered HTML, headless tests — fires the same set.
 */
@Serializable
sealed class ComponentEvent {
    /** A meaningful user action — typically a button tap. */
    @Serializable
    data class Action(
        val action: String,
        val sourceType: String,
        val sourceLabel: String? = null,
    ) : ComponentEvent()

    /** Free-text value entered into a text field (debounced or on-submit). */
    @Serializable
    data class TextChanged(
        val sourceId: String,
        val value: String,
    ) : ComponentEvent()

    /** Toggle (switch, checkbox) flipped. */
    @Serializable
    data class ToggleChanged(
        val sourceId: String,
        val value: Boolean,
    ) : ComponentEvent()
}
