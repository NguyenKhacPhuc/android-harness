package dev.weft.harness.testing

import dev.weft.contracts.AskKind
import dev.weft.contracts.BiometricResult
import dev.weft.contracts.HapticEffect
import dev.weft.contracts.LocationFix
import dev.weft.contracts.UIUpdate
import dev.weft.contracts.UserAnswer
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Smoke tests for the fixtures themselves. These don't exercise any real
 * Weft tool — they verify that the FakeOsCapabilities + FakeUiBridge
 * actually record and replay correctly. Real tool tests live in each
 * tool's own module's `:test` source set and depend on `:harness:testing`.
 */
class FixtureSmokeTest : StringSpec({

    "FakeClipboard records writes and round-trips" {
        val os = FakeOsCapabilities()
        os.clipboard.write("hello")
        os.clipboard.read() shouldBe "hello"
        os.clipboard.writes shouldContainExactly listOf("hello")
    }

    "FakeHaptics records every effect in order" {
        val os = FakeOsCapabilities()
        os.haptics.perform(HapticEffect.CLICK)
        os.haptics.perform(HapticEffect.SUCCESS)
        os.haptics.played shouldContainExactly listOf(HapticEffect.CLICK, HapticEffect.SUCCESS)
    }

    "FakeBiometrics returns the canned result" {
        val os = FakeOsCapabilities()
        os.biometrics.nextResult = BiometricResult.UserCancelled
        os.biometrics.authenticate("test") shouldBe BiometricResult.UserCancelled
        os.biometrics.prompts shouldContainExactly listOf("test")
    }

    "FakeLocation respects canned fixes" {
        val os = FakeOsCapabilities()
        os.location.current().shouldBeNull()
        os.location.nextFix = LocationFix(
            latitude = 40.0, longitude = -74.0,
            accuracyMeters = 5.0f, timestampEpochMs = 0L,
        )
        val fix = os.location.current()
        fix.shouldNotBeNull()
        fix.latitude shouldBe 40.0
    }

    "FakeUiBridge auto-answers when autoAnswer set" {
        val ui = FakeUiBridge().apply { autoAnswer = UserAnswer.YesNo(true) }
        val answer = ui.askUser("ready?", AskKind.YES_NO)
        answer shouldBe UserAnswer.YesNo(true)
        ui.askedQuestions shouldHaveSize 1
    }

    "FakeUiBridge suspends until explicit response" {
        runTest {
            val ui = FakeUiBridge()
            val deferred = async { ui.confirmDestructive("delete", "permanent") }
            // Let the async actually enter the suspending call on the
            // virtual scheduler before we try to resolve it.
            runCurrent()
            ui.respondToConfirm(false)
            deferred.await() shouldBe false
            ui.confirmRequests shouldHaveSize 1
        }
    }

    "FakeUiBridge captures emitted updates" {
        runTest {
            val ui = FakeUiBridge()
            ui.emit(UIUpdate.None)
            ui.emittedUpdates shouldHaveSize 1
        }
    }

    "weftToolContext wires sensible defaults" {
        val ctx = weftToolContext()
        // Each tool gets its own ScriptStorage; verify we can construct one.
        shouldNotThrow<Throwable> {
            ctx.storageFactory("any_tool_name")
        }
    }
})
