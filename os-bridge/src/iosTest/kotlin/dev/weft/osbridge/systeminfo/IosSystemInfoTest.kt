package dev.weft.osbridge.systeminfo

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * device() is pure reads (UIDevice + Locale + TimeZone + filesystem) and
 * runs in CI. battery()/network()/display() are hardware/daemon-backed
 * and are verified manually.
 */
class IosSystemInfoTest {

    private val info = IosSystemInfo()

    @Test
    fun deviceReportsAppleIosIdentity() {
        runBlocking {
            val d = info.device()
            d.manufacturer shouldBe "Apple"
            d.osName shouldContain "iOS"
            d.sdkInt shouldBe -1
        }
    }

    @Test
    fun deviceReportsNonEmptyLocaleAndTimezone() {
        runBlocking {
            val d = info.device()
            d.osVersion.isNotEmpty().shouldBeTrue()
            d.locale.isNotEmpty().shouldBeTrue()
            d.timezone.isNotEmpty().shouldBeTrue()
        }
    }

    @Test
    fun deviceReportsStorageTotals() {
        runBlocking {
            info.device().storageTotalBytes.toInt() shouldBeGreaterThan 0
        }
    }
}
