package dev.weft.osbridge.keyvault

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Runs against the real iOS Keychain on the simulator.
 *
 * The read / not-found paths run in CI. The write paths (anything that
 * calls [IosKeyVault.put]) are `@Ignore`d: a bare Kotlin/Native
 * simulator test binary has no app-host entitlement, so the
 * data-protection Keychain rejects `SecItemAdd` with
 * `errSecNotAvailable (-25291)`. The write paths are verified manually
 * on a device / app-hosted build — they exercise the same proven
 * cinterop the undercurrent host already ships in production.
 */
class IosKeyVaultTest {

    private val vault = IosKeyVault(service = "dev.weft.osbridge.keyvault.test")

    private suspend fun freshAlias(name: String): String {
        vault.remove(name)
        return name
    }

    @Test
    fun missingSecretReadsAsNull() {
        runBlocking {
            val alias = freshAlias("kv-missing")
            vault.get(alias) shouldBe null
        }
    }

    @Test
    fun removingAbsentSecretReturnsFalse() {
        runBlocking {
            val alias = freshAlias("kv-remove-absent")
            vault.remove(alias) shouldBe false
        }
    }

    @Test
    fun absentSecretDoesNotExist() {
        runBlocking {
            val alias = freshAlias("kv-exists-absent")
            vault.exists(alias) shouldBe false
        }
    }

    @Ignore // write path: needs app-host Keychain entitlement (see class KDoc); verified manually
    @Test
    fun storedSecretRoundTrips() {
        runBlocking {
            val alias = freshAlias("kv-roundtrip")
            vault.put(alias, "s3cret-value")
            vault.get(alias) shouldBe "s3cret-value"
            vault.remove(alias)
        }
    }

    @Ignore // write path: needs app-host Keychain entitlement (see class KDoc); verified manually
    @Test
    fun presentSecretExistsAndRemovingClearsIt() {
        runBlocking {
            val alias = freshAlias("kv-remove-present")
            vault.put(alias, "to-be-removed")
            vault.exists(alias) shouldBe true
            vault.remove(alias) shouldBe true
            vault.get(alias) shouldBe null
            vault.exists(alias) shouldBe false
        }
    }

    @Ignore // write path: needs app-host Keychain entitlement (see class KDoc); verified manually
    @Test
    fun secondPutOverwritesFirst() {
        runBlocking {
            val alias = freshAlias("kv-overwrite")
            vault.put(alias, "first")
            vault.put(alias, "second")
            vault.get(alias) shouldBe "second"
            vault.remove(alias)
        }
    }
}
