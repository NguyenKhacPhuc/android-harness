package dev.weft.contracts

import kotlinx.serialization.json.JsonObject

/**
 * A named source of substrate-level "context" — read-only state the LLM may
 * want to inspect without it taking up space in the system prompt.
 *
 * Examples (substrate-provided): `device` (time/timezone/locale).
 * Examples (app-provided): `user_profile`, `subscription`, `active_screen`.
 *
 * Registered into [ContextRegistry] at app startup. Queried by the
 * `system_user_context` tool when the LLM wants fresh structured values.
 */
public interface ContextProvider {
    /**
     * Stable identifier used as the key in [ContextRegistry] and in
     * `system_user_context`'s `providers` parameter. Convention: snake_case.
     */
    public val name: String

    /** Human-readable description shown to the LLM via tool docs. */
    public val description: String

    /** Capture the current snapshot of this provider's context. */
    public suspend fun snapshot(): JsonObject
}

/**
 * Lookup for named [ContextProvider]s. App builds this once at startup with
 * the substrate's defaults plus any app-specific providers.
 */
public class ContextRegistry(providers: List<ContextProvider>) {
    private val byName: Map<String, ContextProvider> =
        providers.associateBy { it.name }
            .also { require(it.size == providers.size) { "Duplicate context provider names" } }

    public fun get(name: String): ContextProvider? = byName[name]

    public fun all(): Collection<ContextProvider> = byName.values

    public fun names(): Set<String> = byName.keys
}
