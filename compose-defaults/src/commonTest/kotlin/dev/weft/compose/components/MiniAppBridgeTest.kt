package dev.weft.compose.components

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Pure-logic tests for the mini-app bridge core. Covers the three
 * round-trip outcomes the story asserts — success, unknown action,
 * failure — plus payload parsing and JS-string escaping. The WebView
 * transport itself is platform-only and verified by compilation.
 */
class MiniAppBridgeTest {

    @Test
    fun successfulCallResolvesWithResultJson() {
        runBlocking {
            val bridge = MiniAppBridge(MiniAppActionInvoker { _, _ -> """{"epoch":42}""" })
            val js = bridge.handle("""{"id":"1","name":"get_time","args":{}}""")
            js shouldStartWith "window.weft.__resolve("
            js shouldContain "\"1\""
            js shouldContain "epoch"
        }
    }

    @Test
    fun unknownActionRejectsWithNoSuchAction() {
        runBlocking {
            val bridge = MiniAppBridge(MiniAppActionInvoker { _, _ -> null })
            val js = bridge.handle("""{"id":"7","name":"nope","args":null}""")
            js shouldStartWith "window.weft.__reject("
            js shouldContain "no such action: nope"
            js shouldContain "\"7\""
        }
    }

    @Test
    fun failingActionRejectsWithMessageAndDoesNotHang() {
        runBlocking {
            val bridge = MiniAppBridge(
                MiniAppActionInvoker { _, _ -> throw IllegalStateException("boom") },
            )
            val js = bridge.handle("""{"id":"3","name":"explode","args":{}}""")
            js shouldStartWith "window.weft.__reject("
            js shouldContain "boom"
        }
    }

    @Test
    fun parseCallExtractsIdNameAndArgsJson() {
        val bridge = MiniAppBridge(MiniAppActionInvoker { _, _ -> null })
        val call = bridge.parseCall("""{"id":"9","name":"store_set","args":{"k":"v"}}""")
        call shouldBe MiniAppCall("9", "store_set", """{"k":"v"}""")
    }

    @Test
    fun argsAreHandedToTheInvokerAsRawJson() {
        runBlocking {
            var seen: String? = null
            val bridge = MiniAppBridge(
                MiniAppActionInvoker { _, args -> seen = args; "1" },
            )
            bridge.handle("""{"id":"1","name":"x","args":{"a":1}}""")
            seen shouldBe """{"a":1}"""
        }
    }

    @Test
    fun malformedPayloadProducesNoJs() {
        runBlocking {
            val bridge = MiniAppBridge(MiniAppActionInvoker { _, _ -> "x" })
            bridge.handle("not json at all") shouldBe ""
        }
    }

    @Test
    fun resultJsonIsEscapedIntoAJsStringLiteral() {
        runBlocking {
            val bridge = MiniAppBridge(MiniAppActionInvoker { _, _ -> "say \"hi\"\nbye" })
            val js = bridge.handle("""{"id":"1","name":"x","args":null}""")
            js shouldContain "\\\"hi\\\""
            js shouldContain "\\n"
        }
    }

    @Test
    fun shimExposesCallToolAndRoutesThroughTheGivenTransport() {
        val shim = MiniAppBridge.jsShim("AndroidWeftBridge.postMessage(msg);")
        shim shouldContain "window.weft"
        shim shouldContain "callTool"
        shim shouldContain "AndroidWeftBridge.postMessage(msg);"
    }
}
