package dev.weft.harness.agents

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.weft.harness.agents.routing.ModelPool
import dev.weft.harness.agents.routing.StaticModelRouter
import dev.weft.harness.conversation.InMemoryConversationStore
import dev.weft.harness.observability.InMemoryTraceStore
import dev.weft.harness.testing.FakeStep
import dev.weft.harness.testing.FakeWeftLLM
import dev.weft.harness.testing.UnscriptedPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * [WeftAgent.ask] runs a full turn but leaves the conversation untouched:
 * no in-memory history, no state sync (so the chat UI sees nothing), no
 * persistence. Contrasted against a normal [AgentIntent.Send] turn, which
 * records + persists. Backs the "mini-app asks the assistant off-the-record"
 * feature.
 */
class WeftAgentAskTest : BehaviorSpec({

    val testModel = LLModel(
        provider = LLMProvider.Anthropic,
        id = "claude-fake",
        capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion),
    )

    fun agentWith(store: InMemoryConversationStore, conversationId: String): WeftAgent {
        val fake = FakeWeftLLM(
            onUnscripted = UnscriptedPolicy.Default(FakeStep.Text("the answer")),
        )
        return WeftAgent(
            executor = fake,
            modelPool = ModelPool(cheap = testModel, standard = testModel),
            modelRouter = StaticModelRouter(testModel),
            toolRegistry = ToolRegistry { },
            traceStore = InMemoryTraceStore(),
            baseSystemPromptSupplier = { "You are a test assistant." },
            conversationId = conversationId,
            conversationStore = store,
        )
    }

    Given("an agent on a persisted conversation") {

        When("ask() runs an isolated turn") {
            Then("it returns the reply but records nothing in history or the store") {
                runTest {
                    val store = InMemoryConversationStore()
                    val convId = store.newConversation()
                    val agent = agentWith(store, convId)

                    val reply = agent.ask("what's the forecast?")

                    reply shouldBe "the answer"
                    agent.state.value.history.size shouldBe 0
                    store.loadMessages(convId).size shouldBe 0
                }
            }
        }

        When("a normal Send turn runs") {
            Then("it records both halves in history and persists them") {
                runTest {
                    val store = InMemoryConversationStore()
                    val convId = store.newConversation()
                    val agent = agentWith(store, convId)

                    agent.dispatchAndAwait(AgentIntent.Send(text = "hello", streaming = false))

                    agent.state.value.history.size shouldBe 2
                    store.loadMessages(convId).size shouldBe 2
                }
            }
        }

        When("ask() runs after a normal turn") {
            Then("the isolated turn adds nothing on top of the recorded one") {
                runTest {
                    val store = InMemoryConversationStore()
                    val convId = store.newConversation()
                    val agent = agentWith(store, convId)

                    agent.dispatchAndAwait(AgentIntent.Send(text = "hello", streaming = false))
                    agent.ask("background question")

                    agent.state.value.history.size shouldBe 2
                    store.loadMessages(convId).size shouldBe 2
                }
            }
        }
    }
})
