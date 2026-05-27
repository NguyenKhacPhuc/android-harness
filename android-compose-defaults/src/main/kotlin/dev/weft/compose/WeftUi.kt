package dev.weft.compose

import android.content.Context
import coil.ImageLoader
import dev.weft.compose.components.WeftComponent
import dev.weft.compose.components.WeftComponentRegistry
import dev.weft.compose.components.buildWeftImageLoader
import dev.weft.compose.components.defaultWeftComponents

/**
 * App-side composition helper for the Compose UI half of Undercurrent.
 *
 * The substrate SDK is interface-only for UI: it ships the `ui_render` /
 * `ui_notify` tools (which talk through `UiBridge`) and the
 * `ComponentMetadata` contract (so the system prompt can describe what's
 * available). Everything Compose lives here in the app — components,
 * the registry, the bridge implementation, the image loader.
 *
 * This class is just convenience: a single place that holds the image
 * loader, the component list, and the registry so MainActivity can pass
 * them where they're needed.
 *
 *   - `components` → `WeftRuntime.create(componentMetadata = …)`
 *     so the system prompt advertises them.
 *   - `componentRegistry` → `ComposeUiBridge(registry = …)` so the bridge
 *     can implement `validateTree` against the known set, and the tree
 *     renderer can resolve nodes by name.
 *   - `imageLoader` → shared with any custom component that loads bitmaps.
 *
 * Apps with completely different UI (no Material 3, no Compose, etc.)
 * don't need this class at all — write your own bridge, your own
 * registry, your own renderer. The SDK doesn't know the difference.
 */
public class WeftUi(
    context: Context,
    /**
     * App-specific components to register on top of (or instead of) the
     * substrate's built-in palette. With [includeDefaults] = true
     * (default) these are appended to the defaults; with
     * [includeDefaults] = false the registry contains ONLY these.
     */
    extraComponents: List<WeftComponent<*>> = emptyList(),
    /**
     * Whether to register the substrate's built-in component palette
     * (Display / Layout / Action / Input / Macro / Embed — 33 components
     * via [defaultWeftComponents]). Default `true` preserves
     * pre-existing behaviour. Set `false` when the app wants a fully
     * curated component set — only [extraComponents] are registered and
     * advertised to the model.
     *
     * When `false` and [extraComponents] is empty, `ui_render` becomes
     * inert (no components for the model to call). That's intentional —
     * apps opting out of defaults usually do so to lock down the
     * surface area; an empty list is a valid configuration.
     */
    includeDefaults: Boolean = true,
) {
    public val imageLoader: ImageLoader = buildWeftImageLoader(context.applicationContext)

    public val components: List<WeftComponent<*>> =
        if (includeDefaults) defaultWeftComponents(imageLoader) + extraComponents
        else extraComponents

    // Typed as the concrete impl so callers (ComposeUiBridge) can use the
    // typed `get()` for Compose-specific validation. The interface form
    // [dev.weft.contracts.ComponentRegistry] is the supertype — callers
    // that only need the SDK contract just use it as-is.
    public val componentRegistry: WeftComponentRegistry = WeftComponentRegistry(components)
}
