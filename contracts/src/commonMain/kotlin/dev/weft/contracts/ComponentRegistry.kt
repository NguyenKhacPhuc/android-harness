package dev.weft.contracts

/**
 * Read-only lookup over the component palette the LLM can reference in
 * `ui_render` trees. The SDK uses this contract to validate trees
 * (`UiBridge.validateTree`), describe components in the system prompt
 * (`assembleSystemPrompt`), and surface "what's available" to any future
 * tool that needs to introspect the palette.
 *
 * Apps provide a concrete impl that holds whatever native component
 * abstractions their UI framework requires (Compose `WeftComponent`s,
 * SwiftUI views, web custom elements, …). The SDK only ever sees the
 * [ComponentMetadata] face of each registered component.
 *
 * Lookup is by [ComponentMetadata.name] and is case-sensitive. Names
 * must be unique across the registry; impls should reject duplicates
 * at construction time.
 */
public interface ComponentRegistry {
    /** Find a component by exact name. Returns null if unregistered. */
    public fun get(name: String): ComponentMetadata?

    /** All registered names (for "did you mean" / debug listings). */
    public fun names(): Set<String>

    /** Every component in registration order. Used by the system-prompt catalog. */
    public fun all(): List<ComponentMetadata>
}
