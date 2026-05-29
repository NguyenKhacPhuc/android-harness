package dev.weft.compose.components

import androidx.compose.runtime.Composable
import dev.weft.contracts.ComponentCategory
import dev.weft.contracts.ComponentEvent
import dev.weft.contracts.ComponentMetadata
import dev.weft.contracts.ComponentRegistry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A renderable component in the substrate's palette. Apps register
 * implementations with the [ComponentRegistry] at startup; the LLM
 * references them by [name] in the trees it emits via `ui_render`.
 *
 * Per [ADR-007](../../../../../../../../docs/adr/ADR-007-component-tree-ui-protocol.md)
 * the substrate's Tier-1 components are thin wrappers over Material 3 —
 * the substrate adds an LLM-shaped props schema, not a new design system.
 *
 * Implements [ComponentMetadata] (defined in `:contracts`) so the
 * Compose-free side of the substrate (system prompt assembly, catalog
 * descriptions) can iterate components without depending on this module.
 *
 * **Implementation note:** [Render] receives already-deserialized typed
 * props plus an [onEvent] callback the component should fire on
 * meaningful interactions. The renderer is responsible for walking
 * children — components that compose other components call into the
 * children-renderer slot (see [ColumnComponent], [RowComponent]).
 *
 * @param TProps the typed prop bag for this component, deserialized via
 *               [propsSerializer] before [Render] is invoked.
 */
public abstract class WeftComponent<TProps>(
    override val name: String,
    override val description: String,
    override val category: ComponentCategory,
    public val propsSerializer: KSerializer<TProps>,
    override val layoutNotes: String? = null,
    override val example: String? = null,
) : ComponentMetadata {

    @Composable
    public abstract fun Render(
        props: TProps,
        children: @Composable () -> Unit,
        onEvent: (ComponentEvent) -> Unit,
    )

    /** Deserialize raw JSON props into [TProps]. Throws on malformed input. */
    public fun decode(props: JsonObject, json: Json = DEFAULT_JSON): TProps =
        json.decodeFromJsonElement(propsSerializer, props)

    public companion object {
        public val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }
}

/**
 * Compose-backed [ComponentRegistry] over a list of [WeftComponent]s.
 * Names must be unique; duplicates throw at construction.
 *
 * The SDK consumes this through the [ComponentRegistry] contract — it
 * sees `ComponentMetadata`, not the Compose render function. The bridge's
 * `validateTree` uses the typed [get] (returning `WeftComponent<*>`)
 * to call `decode()` and surface prop-shape errors.
 */
public class WeftComponentRegistry(
    components: List<WeftComponent<*>>,
) : ComponentRegistry {

    private val byName: Map<String, WeftComponent<*>> =
        components.associateBy { it.name }
            .also {
                require(it.size == components.size) {
                    "Duplicate component names in registry: ${components.map { c -> c.name }}"
                }
            }

    override fun get(name: String): WeftComponent<*>? = byName[name]

    override fun names(): Set<String> = byName.keys

    override fun all(): List<WeftComponent<*>> = byName.values.toList()
}
