package dev.weft.harness.agents.routing

/**
 * Logical model tier. Used as a per-turn override on [WeftAgent.send] /
 * [WeftAgent.sendStreaming] when the caller wants to bypass [ModelRouter]'s
 * normal heuristics and force a specific slot from the active [ModelPool].
 *
 * Each tier maps to one slot:
 *   - [Cheap]    → [ModelPool.cheap]    (e.g. Haiku on Anthropic)
 *   - [Standard] → [ModelPool.standard] (e.g. Sonnet)
 *   - [Vision]   → [ModelPool.vision]   (often == standard)
 *   - [Heavy]    → [ModelPool.heavy]    (e.g. Opus)
 *
 * Routers MAY ignore the hint (custom routers with their own policy), but
 * the shipped [DefaultModelRouter] honors it unconditionally — that's how
 * the "always force Opus for this turn" UX works.
 */
enum class ModelTier {
    Cheap,
    Standard,
    Vision,
    Heavy,
}
