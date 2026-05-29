package dev.weft.harness.agents.strategy

import dev.weft.harness.prompt.cache.CacheTier
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.reliability.RetryPolicy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainAll
import kotlin.time.Duration.Companion.seconds

/**
 * Locks in the contract that `DefaultStrategy()` reproduces the values
 * the agent loop previously hardcoded inline. Phase 5.2 replaced four
 * touchpoints in `WeftAgent` with strategy reads — if a future change
 * shifts the defaults, this test catches it.
 *
 * The numbers below are the pre-strategy hardcoded values, copied from
 * the diff that introduced strategy. Update them only when intentionally
 * changing the substrate's default behavior.
 */
class DefaultStrategyParityTest : StringSpec({

    val input = WeftUserInput(text = "hello world", attachments = emptyList())

    "retry policy matches pre-strategy RetryPolicy() defaults" {
        val s = DefaultStrategy()
        s.retry.maxAttempts shouldBe RetryPolicy.DEFAULT_MAX_ATTEMPTS
        s.retry.baseDelay shouldBe 1.seconds
        s.retry.maxDelay shouldBe 30.seconds
        s.retry.jitterFraction shouldBe 0.25
    }

    "max iterations matches the per-WeftAgent default (10)" {
        DefaultStrategy().maxIterations(input) shouldBe DefaultStrategy.DEFAULT_MAX_ITERATIONS
        DefaultStrategy.DEFAULT_MAX_ITERATIONS shouldBe 10
    }

    "max iterations honors the constructor override (runtime supplies 25)" {
        DefaultStrategy(maxIterationsValue = 25).maxIterations(input) shouldBe 25
    }

    "cache tiers match the pre-strategy hardcoded mapping" {
        val tiers = DefaultStrategy().cacheTiers
        tiers["system"] shouldBe CacheTier.STATIC
        tiers["history-older"] shouldBe CacheTier.SESSION
        tiers["history-tail"] shouldBe CacheTier.VOLATILE
        tiers["tools-catalog"] shouldBe CacheTier.STATIC
    }

    "history-volatile-tail default matches the pre-strategy constant (2)" {
        DefaultStrategy().historyVolatileTailTurns shouldBe 2
    }

    "pickTier returns null — defers to ModelRouter heuristics" {
        DefaultStrategy().pickTier(input, recent = emptyList()) shouldBe null
    }

    "FrugalStrategy pins cheap tier, lower iter cap, fewer retries" {
        val s = FrugalStrategy()
        s.maxIterations(input) shouldBe 4
        s.retry.maxAttempts shouldBe 2
        s.pickTier(input, emptyList())?.name shouldBe "Cheap"
    }

    "BurstStrategy fails fast and lifts iter cap" {
        val s = BurstStrategy()
        s.maxIterations(input) shouldBe 20
        s.retry.maxAttempts shouldBe 1
        s.pickTier(input, emptyList()) shouldBe null
    }

    "every reference strategy declares all four special cache keys" {
        // Catches "I added a strategy and forgot to populate the map"
        // before it surfaces as a silent cache regression at runtime.
        val keys = listOf("system", "history-older", "history-tail", "tools-catalog")
        DefaultStrategy().cacheTiers.keys shouldContainAll keys
        FrugalStrategy().cacheTiers.keys shouldContainAll keys
        BurstStrategy().cacheTiers.keys shouldContainAll keys
    }
})
