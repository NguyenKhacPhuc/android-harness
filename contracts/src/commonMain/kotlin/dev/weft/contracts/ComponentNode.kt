package dev.weft.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A node in the LLM-composed component tree. Emitted by the agent via the
 * `ui_render` tool; rendered by the platform host through its
 * [ComponentRegistry][dev.weft.contracts.ComponentNode] equivalent.
 *
 * The wire format is deliberately simple — `type` + free-form `props` +
 * children — so the LLM can produce it and so the contract stays
 * cross-platform. Each platform substrate (Android first; iOS later)
 * resolves `type` against its registered component palette and
 * deserializes `props` against the component's typed schema.
 *
 * Per [ADR-007](../adr/ADR-007-component-tree-ui-protocol.md):
 *   - `type` must match a name registered in the component registry,
 *     otherwise the renderer shows an inline error placeholder and the
 *     LLM sees the failure in the next turn.
 *   - Trees deeper than [MAX_DEPTH] are rejected with a structured error;
 *     anything deeper is almost certainly LLM confusion.
 */
@Serializable
data class ComponentNode(
    val type: String,
    val props: JsonObject = JsonObject(emptyMap()),
    val children: List<ComponentNode> = emptyList(),
) {
    companion object {
        const val MAX_DEPTH: Int = 6
    }
}

/**
 * Compute the maximum depth of a tree (a leaf node = depth 1).
 * Used by the renderer to enforce [ComponentNode.MAX_DEPTH] before render.
 */
fun ComponentNode.depth(): Int =
    if (children.isEmpty()) 1 else 1 + children.maxOf { it.depth() }
