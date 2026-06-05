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

    @Test
    fun shimMergesOntoExistingWindowWeftSoThemeCoexists() {
        val shim = MiniAppBridge.jsShim("AndroidWeftBridge.postMessage(msg);")
        // must extend window.weft, not replace it (would drop window.weft.theme)
        shim shouldContain "window.weft = window.weft || {}"
        shim shouldContain "window.weft.callTool ="
    }

    @Test
    fun gatedBridgeAllowsAnApprovedAction() {
        runBlocking {
            var called = false
            val bridge = MiniAppBridge(
                MiniAppActionInvoker { _, _ -> called = true; """{"ok":true}""" },
                approvedActions = setOf("get_time", "store_set"),
            )
            val js = bridge.handle("""{"id":"1","name":"get_time","args":{}}""")
            called shouldBe true
            js shouldStartWith "window.weft.__resolve("
        }
    }

    @Test
    fun gatedBridgeRefusesAnUndeclaredActionWithoutInvoking() {
        runBlocking {
            var called = false
            val bridge = MiniAppBridge(
                MiniAppActionInvoker { _, _ -> called = true; "{}" },
                approvedActions = setOf("get_time"),
            )
            val js = bridge.handle("""{"id":"9","name":"delete_everything","args":{}}""")
            // refused at the gate — the host invoker is never reached
            called shouldBe false
            js shouldStartWith "window.weft.__reject("
            js shouldContain "not permitted: delete_everything"
        }
    }

    @Test
    fun anEmptyApprovedSetRefusesEverything() {
        runBlocking {
            val bridge = MiniAppBridge(
                MiniAppActionInvoker { _, _ -> "{}" },
                approvedActions = emptySet(),
            )
            val js = bridge.handle("""{"id":"1","name":"get_time","args":{}}""")
            js shouldContain "not permitted: get_time"
        }
    }

    @Test
    fun aNullApprovedSetIsUngatedAndCallsThrough() {
        runBlocking {
            val bridge = MiniAppBridge(MiniAppActionInvoker { _, _ -> """{"ok":true}""" })
            val js = bridge.handle("""{"id":"1","name":"anything","args":{}}""")
            js shouldStartWith "window.weft.__resolve("
        }
    }

    /** In-memory state store keyed by mini-app id — the host's role, faked. */
    private class FakeStateStore : MiniAppStateStore {
        val saved = mutableMapOf<String?, String>()
        override suspend fun get(miniAppId: String?): String? = saved[miniAppId]
        override suspend fun set(miniAppId: String?, stateJson: String) {
            saved[miniAppId] = stateJson
        }
    }

    private fun noopInvoker() = MiniAppActionInvoker { _, _ -> null }

    @Test
    fun setStateThenGetStateRoundTripsTheSavedState() {
        runBlocking {
            val store = FakeStateStore()
            val bridge = MiniAppBridge(noopInvoker(), stateStore = store, miniAppId = "counter")
            bridge.handle("""{"id":"1","kind":"setState","state":{"count":3}}""")
            val js = bridge.handle("""{"id":"2","kind":"getState"}""")
            js shouldStartWith "window.weft.__resolve("
            js shouldContain "count"
            store.saved["counter"] shouldBe """{"count":3}"""
        }
    }

    @Test
    fun getStateForNeverSavedResolvesNullNotAnError() {
        runBlocking {
            val bridge = MiniAppBridge(noopInvoker(), stateStore = FakeStateStore(), miniAppId = "fresh")
            val js = bridge.handle("""{"id":"1","kind":"getState"}""")
            js shouldStartWith "window.weft.__resolve("
            js shouldContain "null"
        }
    }

    @Test
    fun oneMiniAppsStateIsIsolatedFromAnothers() {
        runBlocking {
            val store = FakeStateStore()
            val appA = MiniAppBridge(noopInvoker(), stateStore = store, miniAppId = "A")
            val appB = MiniAppBridge(noopInvoker(), stateStore = store, miniAppId = "B")
            appA.handle("""{"id":"1","kind":"setState","state":{"v":"a-only"}}""")
            // B reads its own (empty) slot, never A's
            val jsB = appB.handle("""{"id":"2","kind":"getState"}""")
            jsB shouldStartWith "window.weft.__resolve("
            (jsB.contains("a-only")) shouldBe false
        }
    }

    @Test
    fun stateOpsWithoutAStoreRejectAsNotAvailable() {
        runBlocking {
            val bridge = MiniAppBridge(noopInvoker(), miniAppId = "x")
            bridge.handle("""{"id":"1","kind":"getState"}""") shouldContain "state not available"
            bridge.handle("""{"id":"2","kind":"setState","state":{}}""") shouldContain "state not available"
        }
    }

    @Test
    fun shimExposesGetStateAndSetState() {
        val shim = MiniAppBridge.jsShim("AndroidWeftBridge.postMessage(msg);")
        shim shouldContain "window.weft.getState ="
        shim shouldContain "window.weft.setState ="
        shim shouldContain "\"getState\""
        shim shouldContain "\"setState\""
    }

    @Test
    fun pushJsInvokesTheRegisteredOnDataCallback() {
        val js = MiniAppBridge.pushJs("""{"price":42}""")
        js shouldStartWith "window.weft.__data("
        js shouldContain "price"
    }

    @Test
    fun pushedDataIsEscapedIntoAJsStringLiteral() {
        val js = MiniAppBridge.pushJs("tick \"now\"\nline")
        js shouldContain "\\\"now\\\""
        js shouldContain "\\n"
    }

    @Test
    fun shimExposesOnDataAndDataDelivery() {
        val shim = MiniAppBridge.jsShim("AndroidWeftBridge.postMessage(msg);")
        shim shouldContain "window.weft.onData ="
        shim shouldContain "window.weft.__data ="
    }

    @Test
    fun shimExposesOnOpenAndOnCloseLifecycle() {
        val shim = MiniAppBridge.jsShim("AndroidWeftBridge.postMessage(msg);")
        shim shouldContain "window.weft.onOpen ="
        shim shouldContain "window.weft.onClose ="
        shim shouldContain "window.weft.__open ="
        shim shouldContain "window.weft.__close ="
    }

    @Test
    fun lifecycleJsFiresTheOpenAndCloseCallbacksDefensively() {
        // guarded so firing before the shim exists is a no-op, not an error
        MiniAppBridge.openJs() shouldContain "window.weft.__open"
        MiniAppBridge.openJs() shouldContain "if (window.weft"
        MiniAppBridge.closeJs() shouldContain "window.weft.__close"
        MiniAppBridge.closeJs() shouldContain "if (window.weft"
    }

    @Test
    fun sendMessageResolvesWithTheAssistantsReply() {
        runBlocking {
            val bridge = MiniAppBridge(
                noopInvoker(),
                assistant = MiniAppAssistantHandler { _, text -> "you said: $text" },
            )
            val js = bridge.handle("""{"id":"1","kind":"sendMessage","text":"hi"}""")
            js shouldStartWith "window.weft.__resolve("
            js shouldContain "you said: hi"
            js shouldContain "\"1\""
        }
    }

    @Test
    fun sendMessageWithoutAHandlerRejectsAsNotAvailableNotHang() {
        runBlocking {
            val bridge = MiniAppBridge(noopInvoker())
            val js = bridge.handle("""{"id":"2","kind":"sendMessage","text":"hi"}""")
            js shouldStartWith "window.weft.__reject("
            js shouldContain "assistant not available"
            js shouldContain "\"2\""
        }
    }

    @Test
    fun sendMessageHandsTheMiniAppIdAndTextToTheHandler() {
        runBlocking {
            var seenId: String? = "unset"
            var seenText = ""
            val bridge = MiniAppBridge(
                noopInvoker(),
                miniAppId = "calc.1",
                assistant = MiniAppAssistantHandler { id, text ->
                    seenId = id
                    seenText = text
                    "ok"
                },
            )
            bridge.handle("""{"id":"5","kind":"sendMessage","text":"plan my day"}""")
            seenId shouldBe "calc.1"
            seenText shouldBe "plan my day"
        }
    }

    @Test
    fun failingAssistantRejectsWithItsMessage() {
        runBlocking {
            val bridge = MiniAppBridge(
                noopInvoker(),
                assistant = MiniAppAssistantHandler { _, _ -> throw IllegalStateException("model down") },
            )
            val js = bridge.handle("""{"id":"6","kind":"sendMessage","text":"hi"}""")
            js shouldStartWith "window.weft.__reject("
            js shouldContain "model down"
        }
    }

    @Test
    fun shimExposesSendMessage() {
        val shim = MiniAppBridge.jsShim("AndroidWeftBridge.postMessage(msg);")
        shim shouldContain "window.weft.sendMessage ="
        shim shouldContain "\"sendMessage\""
    }
}
