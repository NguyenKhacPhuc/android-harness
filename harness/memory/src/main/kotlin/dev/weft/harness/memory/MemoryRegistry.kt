package dev.weft.harness.memory

import dev.weft.contracts.MemoryHit
import dev.weft.contracts.MemoryProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Aggregates N [MemoryProvider]s and combines their hits into a single
 * "relevant context" block per turn. Constructed by `WeftRuntime` with
 * the substrate's own provider plus whatever the app registered via
 * `extraMemoryProviders`.
 *
 * Runs providers **concurrently** so adding a slow provider doesn't
 * block the turn entirely — total retrieval latency is bounded by the
 * slowest provider, not the sum. A failing provider is logged via
 * [onProviderError] and contributes zero hits; the rest of the turn
 * proceeds.
 *
 * The [globalLimit] caps total hits across providers (ranked by score
 * then per-provider order). Use a small number — every hit consumes
 * input-prompt tokens for every turn it's injected.
 */
public class MemoryRegistry(
    public val providers: List<MemoryProvider>,
    private val onProviderError: (providerName: String, cause: Throwable) -> Unit = { _, _ -> },
) {
    /**
     * Query every provider with [query]. Returns up to [globalLimit]
     * hits ranked by [MemoryHit.score] descending (ties broken by
     * provider order in [providers]). Each provider is asked for
     * [perProviderLimit] hits internally so a fast provider can return
     * its full allotment without being trimmed before scoring.
     *
     * Empty provider list returns an empty list synchronously.
     */
    public suspend fun retrieveAll(
        query: String,
        perProviderLimit: Int = 5,
        globalLimit: Int = 5,
    ): List<MemoryHit> {
        if (providers.isEmpty()) return emptyList()
        if (query.isBlank()) return emptyList()

        val perProvider: List<List<MemoryHit>> = coroutineScope {
            providers.map { provider ->
                async {
                    runCatching { provider.retrieve(query, perProviderLimit) }
                        .onFailure { onProviderError(provider.name, it) }
                        .getOrDefault(emptyList())
                        // Backfill missing source = provider.name so the LLM
                        // sees consistent provenance even when the provider
                        // left it blank.
                        .map { if (it.source.isBlank()) it.copy(source = provider.name) else it }
                }
            }.awaitAll()
        }

        return perProvider.flatten()
            .sortedByDescending { it.score }
            .take(globalLimit)
    }
}

/**
 * Default substrate-side [MemoryProvider]: wraps the user's curated
 * memory store ([MemoryStore]). Surfaces facts the LLM stored explicitly
 * via the `memory_store` tool. Always registered by `WeftRuntime`.
 */
public class SubstrateMemoryProvider(
    private val store: MemoryStore,
    override val name: String = "substrate",
    override val description: String = "User-curated facts stored via memory_store.",
) : MemoryProvider {
    override suspend fun retrieve(query: String, limit: Int): List<MemoryHit> {
        return store.recall(query = query, limit = limit).map { entry ->
            MemoryHit(
                content = entry.content,
                source = name,
                // Substrate store has no relevance signal — assign a flat
                // mid-range score so app providers with real scores can
                // out-rank substrate hits when their score > 0.5, and
                // substrate hits otherwise sit ahead of unscored hits.
                score = 0.5f,
            )
        }
    }
}
