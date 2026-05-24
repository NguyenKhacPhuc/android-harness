package dev.weft.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest

class NetworkPolicyTest : StringSpec({

    "allowlist permits api.anthropic.com by default" {
        runTest {
            val engine = MockEngine { respondOk("ok") }
            val client = whitelistingHttpClient(engine)
            client.get("https://api.anthropic.com/v1/messages").bodyAsText() shouldBe "ok"
            client.close()
        }
    }

    "blocked host throws NetworkPolicyException without hitting the engine" {
        runTest {
            var engineCalled = false
            val engine = MockEngine {
                engineCalled = true
                respondOk("should not be reached")
            }
            val client = whitelistingHttpClient(engine)
            shouldThrow<NetworkPolicyException> {
                client.get("https://evil.example.com/exfiltrate")
            }
            engineCalled shouldBe false
            client.close()
        }
    }

    "user-added host is permitted; removal blocks it again" {
        runTest {
            val engine = MockEngine { respondOk("ok") }

            val withMyHost = NetworkPolicy().withUserAddition("my.example.com")
            val permitted = whitelistingHttpClient(engine, withMyHost)
            permitted.get("https://my.example.com/data").bodyAsText() shouldBe "ok"
            permitted.close()

            val removed = whitelistingHttpClient(engine, withMyHost.withUserRemoval("my.example.com"))
            shouldThrow<NetworkPolicyException> { removed.get("https://my.example.com/data") }
            removed.close()
        }
    }

    "assertAllowed throws for non-allowed URLs" {
        val policy = NetworkPolicy()
        policy.assertAllowed("https://api.anthropic.com/v1/messages")  // no throw
        shouldThrow<NetworkPolicyException> {
            policy.assertAllowed("https://attacker.example.com/")
        }
    }
})
