package dev.weft.harness.testing

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

internal class FakeWeftLLMTest : StringSpec({

    val testModel = LLModel(
        provider = LLMProvider.Anthropic,
        id = "claude-sonnet-4-5-fake",
        capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion),
    )

    "script delivers plain text then a tool call" {
        runTest {
            val fake = FakeWeftLLM.build {
                text("Hi! What would you like to build?")
                callTool("create_persona", """{"name":"Editor","kind":"voice"}""")
            }

            val reply1 = fake.execute(prompt("p") { user("hello") }, testModel)
            reply1.textContent() shouldBe "Hi! What would you like to build?"
            reply1.finishReason shouldBe "end_turn"

            val reply2 = fake.execute(prompt("p") { user("make me a persona") }, testModel)
            reply2.finishReason shouldBe "tool_use"
            val call = reply2.parts.filterIsInstance<MessagePart.Tool.Call>().single()
            call.tool shouldBe "create_persona"
            call.args shouldBe """{"name":"Editor","kind":"voice"}"""
        }
    }

    "rule pattern beats script — first non-null rule wins" {
        runTest {
            val fake = FakeWeftLLM.build {
                whenUserSays("hello", "scripted hello reply from rule")
                text("fallback from list")
            }

            val r1 = fake.execute(prompt("p") { user("Hello world") }, testModel)
            r1.textContent() shouldBe "scripted hello reply from rule"

            val r2 = fake.execute(prompt("p") { user("something else") }, testModel)
            r2.textContent() shouldBe "fallback from list"
        }
    }

    "parallel tool calls in one response" {
        runTest {
            val fake = FakeWeftLLM.build {
                callTools(
                    ToolCallSpec("set_theme_palette", """{"palette":"vellum"}"""),
                    ToolCallSpec("set_theme_mode", """{"mode":"dark"}"""),
                )
            }

            val reply = fake.execute(prompt("p") { user("dark vellum please") }, testModel)
            reply.finishReason shouldBe "tool_use"
            val calls = reply.parts.filterIsInstance<MessagePart.Tool.Call>()
            calls shouldHaveSize 2
            calls[0].tool shouldBe "set_theme_palette"
            calls[1].tool shouldBe "set_theme_mode"
        }
    }

    "unscripted Throw policy fires a descriptive error" {
        runTest {
            val fake = FakeWeftLLM()  // empty script, Throw policy
            shouldThrow<FakeLlmException> {
                fake.execute(prompt("p") { user("anything") }, testModel)
            }
        }
    }

    "unscripted Default policy returns the default step" {
        runTest {
            val fake = FakeWeftLLM.build {
                onUnscripted(UnscriptedPolicy.Default(FakeStep.Text("default fallback")))
            }
            val reply = fake.execute(prompt("p") { user("anything") }, testModel)
            reply.textContent() shouldBe "default fallback"
        }
    }

    "calls are recorded for assertions" {
        runTest {
            val fake = FakeWeftLLM.build {
                text("one")
                text("two")
            }
            fake.execute(prompt("p") { user("first") }, testModel)
            fake.execute(prompt("p") { user("second") }, testModel)
            fake.calls shouldHaveSize 2
            fake.calls.forEach { it.source shouldBe CallSource.Script }
            fake.callCount shouldBe 2
        }
    }

    "streaming emits TextDelta + TextComplete + End for Text step" {
        runTest {
            val fake = FakeWeftLLM.build { text("streamed hello") }
            val frames = mutableListOf<ai.koog.prompt.streaming.StreamFrame>()
            fake.executeStreaming(prompt("p") { user("hi") }, testModel)
                .collect { frames += it }
            frames.any { it is ai.koog.prompt.streaming.StreamFrame.TextDelta && it.text == "streamed hello" } shouldBe true
            frames.any { it is ai.koog.prompt.streaming.StreamFrame.TextComplete } shouldBe true
            frames.last() shouldBe ai.koog.prompt.streaming.StreamFrame.End(finishReason = "end_turn")
        }
    }
})
