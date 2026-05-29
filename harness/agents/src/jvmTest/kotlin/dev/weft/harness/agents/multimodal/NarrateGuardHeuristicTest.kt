package dev.weft.harness.agents.multimodal

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Heuristic-detector tests for the narrate-without-emit guard in
 * `weftSingleRunStrategy`. The full graph integration is exercised by
 * the existing `weftSingleRunStrategy` tests + the live app — these
 * cases pin the regex behavior so a tweak doesn't silently regress the
 * trip rate or expand false positives.
 */
class NarrateGuardHeuristicTest : StringSpec({

    // ── True positives — should trip the guard ───────────────────────

    "trips on 'Now let me X' tail" {
        looksLikeNarrateWithoutEmit("Got it! Now let me fetch your weather!") shouldBe true
    }

    "trips on 'I'll X' future intent" {
        looksLikeNarrateWithoutEmit("Location locked in. I'll go set up the briefing now.") shouldBe true
    }

    "trips on 'Going to X'" {
        looksLikeNarrateWithoutEmit("Perfect. Going to render that for you.") shouldBe true
    }

    "trips on 'Let me X' standalone" {
        looksLikeNarrateWithoutEmit("Let me put together the weather card.") shouldBe true
    }

    "trips on 'Let's X' collective intent" {
        looksLikeNarrateWithoutEmit("Sounds great — let's build that mini-app.") shouldBe true
    }

    // ── False positives we explicitly exclude ────────────────────────

    "does NOT trip on 'Let me know if'" {
        looksLikeNarrateWithoutEmit("Here's the weather. Let me know if you want hourly detail.") shouldBe false
    }

    "does NOT trip on 'Would you like'" {
        looksLikeNarrateWithoutEmit("Saved as a feature. Would you like to pin it?") shouldBe false
    }

    "does NOT trip on 'I'll be happy to'" {
        looksLikeNarrateWithoutEmit("Done. I'll be happy to refine the layout if needed.") shouldBe false
    }

    "does NOT trip on 'I'll remember' (memory-related closing)" {
        looksLikeNarrateWithoutEmit("Saved. I'll remember that for next time.") shouldBe false
    }

    // ── Non-intent text — should never trip ──────────────────────────

    "does NOT trip on past-tense recap" {
        looksLikeNarrateWithoutEmit("Got it — location locked in, briefing scheduled for 8 AM.") shouldBe false
    }

    "does NOT trip on plain question reply" {
        looksLikeNarrateWithoutEmit("The capital of France is Paris.") shouldBe false
    }

    "does NOT trip on empty string" {
        looksLikeNarrateWithoutEmit("") shouldBe false
    }

    "does NOT trip on whitespace-only" {
        looksLikeNarrateWithoutEmit("   \n  ") shouldBe false
    }

    // ── Tail-window behavior — only the last ~200 chars matter ───────

    "ignores future intent buried far above the tail" {
        // Repro of the false-negative side: if the model said "I'll do X"
        // somewhere in a long recap but ended past-tense, we don't trip.
        val text = buildString {
            append("Earlier you mentioned I'll set this up. ")
            repeat(20) { append("All the work is now complete. ") }
        }
        looksLikeNarrateWithoutEmit(text) shouldBe false
    }

    "trips when future intent appears in the tail of a long message" {
        val text = buildString {
            repeat(10) { append("All the setup tools have returned successfully. ") }
            append("Now let me render the card!")
        }
        looksLikeNarrateWithoutEmit(text) shouldBe true
    }
})
