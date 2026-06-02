package dev.weft.osbridge

import dev.weft.osbridge.clipboard.IosClipboard
import dev.weft.osbridge.haptics.IosHaptics
import dev.weft.osbridge.imageops.IosImageOps
import dev.weft.osbridge.intents.IosIntents
import dev.weft.osbridge.keyvault.IosKeyVault
import dev.weft.osbridge.permissions.IosPermissions
import dev.weft.osbridge.power.IosPower
import dev.weft.osbridge.sharing.IosSharing
import dev.weft.osbridge.systeminfo.IosSystemInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * The no-arg [IosOsCapabilities] is the turnkey, fully-wired set the iOS
 * `WeftRuntime.create` factory defaults to. Pure construction + a
 * throwing call — runs in CI.
 */
class IosOsCapabilitiesTest {

    private val os = IosOsCapabilities()

    @Test
    fun foundationalCapabilitiesAreTheirRealImpls() {
        os.keyVault.shouldBeInstanceOf<IosKeyVault>()
        os.clipboard.shouldBeInstanceOf<IosClipboard>()
        os.permissions.shouldBeInstanceOf<IosPermissions>()
        os.haptics.shouldBeInstanceOf<IosHaptics>()
        os.power.shouldBeInstanceOf<IosPower>()
        os.imageOps.shouldBeInstanceOf<IosImageOps>()
        os.systemInfo.shouldBeInstanceOf<IosSystemInfo>()
        os.sharing.shouldBeInstanceOf<IosSharing>()
        os.intents.shouldBeInstanceOf<IosIntents>()
    }

    @Test
    fun unimplementedCapabilityFailsLoudlyNotSilently() {
        runBlocking {
            // A still-stubbed capability throws (surfaced to the LLM as a
            // tool error) rather than returning a silent no-op result.
            shouldThrow<NotImplementedError> { os.telephony.dial("12345") }
        }
    }
}
