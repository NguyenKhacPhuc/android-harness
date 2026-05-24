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
    extraComponents: List<WeftComponent<*>> = emptyList(),
) {
    public val imageLoader: ImageLoader = buildWeftImageLoader(context.applicationContext)

    public val components: List<WeftComponent<*>> =
        defaultWeftComponents(imageLoader) + extraComponents

    // Typed as the concrete impl so callers (ComposeUiBridge) can use the
    // typed `get()` for Compose-specific validation. The interface form
    // [dev.weft.contracts.ComponentRegistry] is the supertype — callers
    // that only need the SDK contract just use it as-is.
    public val componentRegistry: WeftComponentRegistry = WeftComponentRegistry(components)
}
