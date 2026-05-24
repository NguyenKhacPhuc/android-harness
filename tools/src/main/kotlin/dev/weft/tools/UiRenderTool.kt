package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.ComponentNode
import dev.weft.contracts.TreeValidationResult
import dev.weft.contracts.UIUpdate
import dev.weft.contracts.depth
import kotlinx.serialization.Serializable

/**
 * `ui_render` — the LLM emits a component tree and the substrate posts
 * it to the UI via `UiBridge.emit(UIUpdate.RenderTree)`.
 *
 * Per [ADR-007](../../../../../../../../docs/adr/ADR-007-component-tree-ui-protocol.md)
 * this is the primary surface for LLM-driven UI in v1. The tool itself
 * is platform-neutral — it knows nothing about Compose, components, or
 * rendering. Pre-validation (unknown component, bad props, etc.) is
 * delegated to the [UiBridge] implementation via `validateTree`, since
 * the bridge is the one that owns the component registry. The tool
 * keeps one universal check — depth — because that's a property of the
 * tree shape, not the component set.
 */
public class UiRenderTool(ctx: WeftContext) : WeftTool<UiRenderTool.Args, UiRenderTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "ui_render",
        description = "Render a UI on the current surface as a tree of components. " +
            "The `tree` is a nested JSON object: each node has `type`, optional `props` " +
            "(object), and optional `children` (array of nodes). " +
            "Use Column/Row to lay out children. Buttons fire an `action` string back " +
            "to you on the next turn. See the component catalog in the system prompt.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "tree",
                description = "The component tree to render. Root is a single node; lay out " +
                    "multiple top-level items inside a Column or Row.",
                type = ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList()),
            ),
        ),
        optionalParameters = emptyList(),
    ),
    sideEffecting = true,
) {

    @Serializable
    public data class Args(val tree: ComponentNode)

    @Serializable
    public data class Result(
        val rendered: Boolean,
        val nodeCount: Int,
        val depth: Int,
    )

    override suspend fun executeWeft(args: Args): Result {
        val depth = args.tree.depth()
        if (depth > ComponentNode.MAX_DEPTH) {
            error(
                "Component tree depth $depth exceeds max ${ComponentNode.MAX_DEPTH}. " +
                    "Flatten the structure — anything deeper than ${ComponentNode.MAX_DEPTH} levels " +
                    "is almost certainly overcomplicated.",
            )
        }
        // Delegate component-set + per-prop validation to the bridge, which
        // owns the registry. Apps with custom renderers implement their
        // own validation; the default Compose reference UI checks unknown
        // types + decode-failures against its component palette.
        when (val v = ui.validateTree(args.tree)) {
            is TreeValidationResult.Invalid -> error(v.message)
            TreeValidationResult.Ok -> Unit
        }
        ui.emit(UIUpdate.RenderTree(tree = args.tree))
        return Result(
            rendered = true,
            nodeCount = countNodes(args.tree),
            depth = depth,
        )
    }

    private fun countNodes(node: ComponentNode): Int =
        1 + node.children.sumOf { countNodes(it) }
}
