package dev.weft.compose.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.weft.contracts.ComponentEvent
import dev.weft.contracts.ComponentNode
import dev.weft.contracts.ComponentRegistry
import dev.weft.contracts.DataSourceRegistry
import dev.weft.harness.bindings.BindingEvaluator
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * A [TreeRenderer] wrapper that resolves `$binding` sentinels in
 * component props against the host's [DataSourceRegistry] BEFORE the
 * tree is handed to the standard renderer.
 *
 * The flow per composition:
 *   1. Subscribe to every referenced `source`'s `changes` flow. Each
 *      emission bumps a version counter that drives recomposition.
 *   2. On every composition: walk the tree, replace each `$binding`
 *      object with the evaluator's resolved JsonElement.
 *   3. Hand the resolved tree to [TreeRenderer] — which is unchanged
 *      and continues to dispatch nodes to their `WeftComponent`s.
 *
 * Bindings are resolved synchronously per recomposition (the evaluator
 * runs against a freshly-queried snapshot of the data source). Slow
 * sources can briefly lag the UI; we resolve on `Dispatchers.IO` and
 * fall back to the previous resolution while loading.
 *
 * Apps that don't need bindings can keep calling [TreeRenderer]
 * directly — this composable is opt-in.
 */
@Composable
public fun BindingAwareRenderer(
    tree: ComponentNode,
    registry: ComponentRegistry,
    sources: DataSourceRegistry,
    onEvent: (ComponentEvent) -> Unit,
) {
    // Collect every source the tree references. Stable across taps —
    // bound sources rarely change between renders.
    val referencedSources = remember(tree) { collectReferencedSources(tree, sources) }

    // Diagnostic — only logs once per tree change. If no sources show
    // up here, the tree has no `$binding` props (or the walker missed
    // them); display values won't refresh on data changes. Visible on
    // Android via `adb logcat | grep WeftBindings`, on iOS via the
    // Xcode console. `println` is used (not platform-specific loggers)
    // because this file is shared across androidMain + iosMain via
    // commonMain; an expect/actual logger is the natural extension if
    // these traces ever stop being throwaway.
    LaunchedEffect(tree, referencedSources) {
        println(
            "WeftBindings: Tree composed: referencedSources=" +
                "${referencedSources.map { it.name }} " +
                "(empty list = no \$binding sentinels found in tree props)",
        )
    }

    // Subscribe to every referenced source's `changes` flow; merge into
    // one signal so a mutation on any of them triggers a fresh
    // resolution. produceState handles the launch/cancel lifecycle.
    val tick by produceState(initialValue = 0L, key1 = tree, key2 = referencedSources) {
        if (referencedSources.isEmpty()) return@produceState
        val merged = referencedSources.map { it.changes }.merge()
        merged.collect {
            println(
                "WeftBindings: Source-change tick from one of " +
                    "${referencedSources.map { s -> s.name }}; resolving tree again",
            )
            value = value + 1
        }
    }

    // Resolve bindings on every tick (or first composition). Use
    // produceState so we don't block the UI thread on the evaluator —
    // the in-flight resolution shows the *previous* resolved tree.
    val resolvedTree by produceState(initialValue = tree, key1 = tree, key2 = tick) {
        val resolved = resolveTree(tree, sources)
        println("WeftBindings: resolveTree complete (tick=$tick)")
        value = resolved
    }

    TreeRenderer(tree = resolvedTree, registry = registry, onEvent = onEvent)
}

/**
 * Walk [node] and collect every `DataSource` mentioned via a
 * `$binding.source` key. Returns a stable list so subscription
 * setup doesn't churn across recompositions.
 */
private fun collectReferencedSources(
    node: ComponentNode,
    registry: DataSourceRegistry,
): List<dev.weft.contracts.DataSource> {
    val names = mutableSetOf<String>()
    walkNodeForBindingSources(node, names)
    return names.mapNotNull { registry.get(it) }
}

private fun walkNodeForBindingSources(node: ComponentNode, out: MutableSet<String>) {
    walkPropsForBindingSources(node.props, out)
    node.children.forEach { walkNodeForBindingSources(it, out) }
}

private fun walkPropsForBindingSources(value: JsonElement, out: MutableSet<String>) {
    when (value) {
        is JsonObject -> {
            val binding = value["\$binding"] as? JsonObject
            if (binding != null) {
                val source = (binding["source"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (!source.isNullOrBlank()) out += source
            }
            value.values.forEach { walkPropsForBindingSources(it, out) }
        }
        is JsonArray -> value.forEach { walkPropsForBindingSources(it, out) }
        else -> Unit
    }
}

/**
 * Build a copy of [node] with every `$binding` sentinel in its props
 * replaced by its resolved value. Children are recursed.
 *
 * Suspending because the evaluator is suspend (it queries the data
 * source). Called from a produceState block so the in-flight
 * resolution doesn't block the UI thread.
 */
private suspend fun resolveTree(
    node: ComponentNode,
    sources: DataSourceRegistry,
): ComponentNode {
    val resolvedProps = resolveProps(node.props, sources) as? JsonObject ?: node.props
    val resolvedChildren = node.children.map { resolveTree(it, sources) }
    return ComponentNode(
        type = node.type,
        props = resolvedProps,
        children = resolvedChildren,
    )
}

private suspend fun resolveProps(value: JsonElement, sources: DataSourceRegistry): JsonElement {
    return when (value) {
        is JsonObject -> {
            // A binding sentinel: evaluate + substitute.
            if (value["\$binding"] is JsonObject) {
                BindingEvaluator.evaluate(value, sources)
            } else {
                // Recurse into nested objects; preserves all other keys.
                buildJsonObject {
                    for ((k, v) in value) {
                        put(k, resolveProps(v, sources))
                    }
                }
            }
        }
        is JsonArray -> JsonArray(value.map { resolveProps(it, sources) })
        else -> value
    }
}
