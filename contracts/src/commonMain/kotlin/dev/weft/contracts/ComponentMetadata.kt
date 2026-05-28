package dev.weft.contracts

/**
 * Catalog-shape metadata for a renderable UI component. Lives in
 * `:contracts` (not in a UI module) so the system-prompt assembler and
 * any other catalog consumers can describe components to the LLM without
 * depending on Compose.
 *
 * The full `WeftComponent<TProps>` (in `:substrate:android-ui`)
 * implements this interface and adds the `@Composable Render(...)`
 * function + a `KSerializer<TProps>` for prop decoding. This split lets
 * the agent-side prompt machinery (which doesn't render anything) stay
 * Compose-free.
 */
interface ComponentMetadata {
    /** Stable identifier the LLM uses to reference this component in trees. */
    val name: String

    /** One-line "what is this" summary. First line of the system-prompt catalog entry. */
    val description: String

    /** Semantic role — drives grouping in the system prompt. */
    val category: ComponentCategory

    /** Optional layout hint. Surfaces as a `Layout:` line in the catalog entry. */
    val layoutNotes: String?

    /** Optional canonical JSON example. Surfaces as an `Example:` line. */
    val example: String?
}

/**
 * Semantic role of a [ComponentMetadata]. Drives how the catalog is
 * grouped in the system prompt and tells app authors / LLMs what to
 * expect from each component.
 *
 * One category per component — pick the *primary* intent. ListItem-with-
 * action is [ACTION] (firing the event is the load-bearing behavior even
 * though it also displays content). Chip with `filter` variant is
 * [INPUT] (it holds selection state) — but the substrate's `Chip`
 * component sits in [ACTION] because the assist / suggestion variants
 * are the more common use; document the filter variant in the
 * description.
 */
enum class ComponentCategory(val label: String, val hint: String) {
    /** Render content. Stateless, no user-interaction events. */
    DISPLAY("Display", "Render content. Stateless, no events."),

    /** Arrange children. Doesn't own content of its own. */
    LAYOUT("Layout", "Arrange children. No content of its own."),

    /**
     * Fire a semantic event when the user interacts. The component
     * declares an `action` (or similar) prop the agent receives.
     */
    ACTION("Action", "Fire an event on interaction. Set the `action` key."),

    /**
     * Capture user data. Maintains local state (typed text, slider value,
     * picked date). Declares an `id` so values can be bundled into the
     * next sibling Action event's payload.
     */
    INPUT("Input", "Capture user data. Set an `id` so the value rides along."),

    /**
     * Behaviorally-complete widget. Bundles multiple primitives + local
     * state + controls. Fires one or two *semantic* events; everything
     * else stays local.
     */
    MACRO("Macro", "Complete widget. One semantic event back to you when done."),

    /** Wrap external content (web pages, future video, future audio). */
    EMBED("Embed", "External content wrapper."),
}
