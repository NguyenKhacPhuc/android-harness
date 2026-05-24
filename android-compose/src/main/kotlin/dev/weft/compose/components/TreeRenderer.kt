package dev.weft.compose.components
import dev.weft.contracts.ComponentRegistry
import dev.weft.contracts.ComponentEvent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.weft.contracts.ComponentNode
import dev.weft.contracts.depth

/**
 * Renders an LLM-composed [ComponentNode] tree by walking it and
 * dispatching each node to its registered [WeftComponent].
 *
 * Unknown component types render an inline error placeholder rather
 * than throwing — the next agent turn sees the failure (via
 * `ComponentEvent` or in the trace) and can correct.
 *
 * Trees deeper than [ComponentNode.MAX_DEPTH] render an error
 * placeholder at the root. Per ADR-007, anything deeper is almost
 * certainly LLM confusion.
 */
@Composable
public fun TreeRenderer(
    tree: ComponentNode,
    registry: ComponentRegistry,
    onEvent: (ComponentEvent) -> Unit,
) {
    val maxDepth = ComponentNode.MAX_DEPTH
    val actualDepth = tree.depth()
    if (actualDepth > maxDepth) {
        ErrorPlaceholder("Tree depth $actualDepth exceeds max ($maxDepth). Simplify the layout.")
        return
    }
    RenderNode(node = tree, registry = registry, onEvent = onEvent)
}

/**
 * The current node being rendered. Components access this to read their
 * own `node.children` selectively (e.g., Tabs renders only one). Use
 * together with [LocalNodeRenderer] to recursively render arbitrary
 * `ComponentNode`s the component holds in props or in its children list.
 *
 * Stable contract: `LocalCurrentNode.current` always refers to the
 * `ComponentNode` whose `Render` is on the stack. Pass children through
 * [LocalNodeRenderer.current] to recurse — never call render functions
 * directly.
 */
public val LocalCurrentNode: ProvidableCompositionLocal<ComponentNode> =
    compositionLocalOf { error("LocalCurrentNode accessed outside TreeRenderer") }

/**
 * A `@Composable` function that renders an arbitrary [ComponentNode]
 * using the substrate's registry + event channel. Components that hold
 * `ComponentNode`s in props (or want to render their children
 * selectively) call this to recurse.
 *
 * Provided automatically by [TreeRenderer] and threaded through every
 * descendant render. Components that don't need it can ignore it.
 */
public val LocalNodeRenderer: ProvidableCompositionLocal<@Composable (ComponentNode) -> Unit> =
    compositionLocalOf { error("LocalNodeRenderer accessed outside TreeRenderer") }

@Composable
private fun RenderNode(
    node: ComponentNode,
    registry: ComponentRegistry,
    onEvent: (ComponentEvent) -> Unit,
) {
    val component = registry.get(node.type)
    if (component == null) {
        ErrorPlaceholder(
            "Unknown component '${node.type}'. Available: ${registry.names().sorted().joinToString()}.",
        )
        return
    }
    @Suppress("UNCHECKED_CAST")
    val typed = component as WeftComponent<Any?>

    val props: Any? = try {
        typed.decode(node.props)
    } catch (t: Throwable) {
        ErrorPlaceholder("Bad props for '${node.type}': ${t.message ?: t::class.simpleName}")
        return
    }

    // Expose the current node + a recursive renderer to descendants. Components
    // that ignore these get the same behavior as before; Tabs / BottomSheet /
    // Carousel use them to render children selectively.
    val renderNode: @Composable (ComponentNode) -> Unit = { child ->
        RenderNode(node = child, registry = registry, onEvent = onEvent)
    }

    CompositionLocalProvider(
        LocalCurrentNode provides node,
        LocalNodeRenderer provides renderNode,
    ) {
        typed.Render(
            props = props,
            children = {
                // Default children renderer: render ALL of node.children in order.
                // Layout components (Column / Row) decide their own arrangement.
                // Switcher components (Tabs, etc.) ignore this and use
                // LocalCurrentNode / LocalNodeRenderer to render selectively.
                node.children.forEach { child -> renderNode(child) }
            },
            onEvent = onEvent,
        )
    }
}

/**
 * Minimal foundation-only error placeholder. Avoids depending on Material 3
 * so the framework module stays palette-agnostic — apps using their own
 * design system don't pull in M3 just to render the substrate's error path.
 *
 * Visually plain on purpose. Apps that want themed error rendering can
 * wrap their `TreeRenderer` call in their own `CompositionLocalProvider`,
 * or replace the renderer entirely.
 */
@Composable
private fun ErrorPlaceholder(message: String) {
    val errorColor = Color(0xFFB3261E) // M3 light-scheme error tone
    Column(
        modifier = Modifier.padding(8.dp),
    ) {
        BasicText(
            text = "⚠ Render error",
            style = TextStyle(color = errorColor, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        BasicText(
            text = message,
            style = TextStyle(color = errorColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        )
    }
}
