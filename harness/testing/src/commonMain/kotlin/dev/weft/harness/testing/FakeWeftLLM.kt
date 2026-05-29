@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.weft.harness.testing

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.atomicfu.atomic
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Scripted [PromptExecutor] for hermetic agent-loop tests.
 *
 * Two resolution layers:
 *
 *   1. **Rules** (run first, in registration order). Each rule sees the
 *      full prompt + model + tools and can return a [FakeStep] to use.
 *      First non-null wins.
 *   2. **Script** (FIFO fallback). When no rule matches, the next
 *      [FakeStep] is popped from the script queue.
 *   3. **Unscripted policy**. When both rules and script are exhausted,
 *      the configured [UnscriptedPolicy] decides: throw a descriptive
 *      error, or emit a default step.
 *
 * Use rules for end-to-end flows where you want to test
 * `agent.send("create persona") → goes through QnA → finalizes`
 * without enumerating every step. Use the explicit script when you
 * care about the exact sequence (loop-shape tests, retry behaviour,
 * parallel tool calls). Mix freely — rules win, the script catches
 * the rest.
 *
 * **NOT thread-safe** for concurrent calls — drive a single agent
 * from a single coroutine. Each `execute` / `executeStreaming` call
 * consumes at most one script step.
 */
public class FakeWeftLLM(
    private val rules: List<Rule> = emptyList(),
    script: List<FakeStep> = emptyList(),
    private val onUnscripted: UnscriptedPolicy = UnscriptedPolicy.Throw,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : PromptExecutor() {

    private val scriptQueue: ArrayDeque<FakeStep> = ArrayDeque(script)
    private val callCounter = atomic(0)
    private val toolIdCounter = atomic(0)

    /** Captured calls — tests assert against this for "was the agent called with X?" */
    private val _calls: MutableList<RecordedCall> = mutableListOf()
    public val calls: List<RecordedCall> get() = _calls.toList()

    /** Total non-streaming + streaming calls served. */
    public val callCount: Int get() = callCounter.value

    @OptIn(ExperimentalTime::class)
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        val step = nextStep(prompt, model, tools, streaming = false)
        callCounter.incrementAndGet()
        return when (step) {
            is FakeStep.Error -> throw FakeLlmException(step.message, step.cause)
            else -> step.toAssistantMessage(model.id)
        }
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        val step = nextStep(prompt, model, tools, streaming = true)
        callCounter.incrementAndGet()
        when (step) {
            is FakeStep.Error -> throw FakeLlmException(step.message, step.cause)
            is FakeStep.Text -> {
                emit(StreamFrame.TextDelta(step.text))
                emit(StreamFrame.TextComplete(step.text))
                emit(StreamFrame.End(finishReason = "end_turn"))
            }
            is FakeStep.CallTool -> {
                emit(StreamFrame.ToolCallDelta(step.id, step.name, step.argsJson))
                emit(StreamFrame.ToolCallComplete(step.id, step.name, step.argsJson))
                emit(StreamFrame.End(finishReason = "tool_use"))
            }
            is FakeStep.CallTools -> {
                step.calls.forEach { c ->
                    emit(StreamFrame.ToolCallDelta(c.id, c.name, c.argsJson))
                    emit(StreamFrame.ToolCallComplete(c.id, c.name, c.argsJson))
                }
                emit(StreamFrame.End(finishReason = "tool_use"))
            }
            is FakeStep.TextThenCall -> {
                emit(StreamFrame.TextDelta(step.text))
                emit(StreamFrame.TextComplete(step.text))
                emit(StreamFrame.ToolCallDelta(step.call.id, step.call.name, step.call.argsJson))
                emit(StreamFrame.ToolCallComplete(step.call.id, step.call.name, step.call.argsJson))
                emit(StreamFrame.End(finishReason = "tool_use"))
            }
        }
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        // Fakes treat all content as benign. Override via subclassing if a test
        // exercises moderation paths specifically.
        throw UnsupportedOperationException("FakeWeftLLM does not implement moderation")
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = listOf(execute(prompt, model, tools))

    override fun close() { /* nothing to release */ }

    // -------------------------------------------------------------------------

    private fun nextStep(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        streaming: Boolean,
    ): FakeStep {
        for (rule in rules) {
            rule.match(prompt, model, tools)?.let { matched ->
                _calls += RecordedCall(prompt, model, tools, streaming, source = CallSource.Rule)
                return matched
            }
        }
        val scripted = scriptQueue.removeFirstOrNull()
        if (scripted != null) {
            _calls += RecordedCall(prompt, model, tools, streaming, source = CallSource.Script)
            return scripted
        }
        _calls += RecordedCall(prompt, model, tools, streaming, source = CallSource.Unscripted)
        return when (val policy = onUnscripted) {
            is UnscriptedPolicy.Default -> policy.step
            UnscriptedPolicy.Throw -> {
                val lastUser = prompt.messages.lastOrNull { it is Message.User }?.textContent()?.take(120)
                throw FakeLlmException(
                    "FakeWeftLLM: no rule matched and script is empty. " +
                        "Last user message: '$lastUser'. " +
                        "Call #${callCounter.value + 1}.",
                )
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun FakeStep.toAssistantMessage(modelId: String): Message.Assistant {
        val parts: List<MessagePart.ResponsePart> = when (this) {
            is FakeStep.Text -> listOf(MessagePart.Text(text))
            is FakeStep.CallTool -> listOf(
                MessagePart.Tool.Call(id = id, tool = name, args = argsJson),
            )
            is FakeStep.CallTools -> calls.map {
                MessagePart.Tool.Call(id = it.id, tool = it.name, args = it.argsJson)
            }
            is FakeStep.TextThenCall -> listOf(
                MessagePart.Text(text),
                MessagePart.Tool.Call(id = call.id, tool = call.name, args = call.argsJson),
            )
            is FakeStep.Error -> error("Error step should be handled before toAssistant")
        }
        val finishReason = when (this) {
            is FakeStep.Text -> "end_turn"
            is FakeStep.CallTool, is FakeStep.CallTools, is FakeStep.TextThenCall -> "tool_use"
            is FakeStep.Error -> "error"
        }
        return Message.Assistant(
            parts = parts,
            metaInfo = ResponseMetaInfo(
                timestamp = Instant.fromEpochMilliseconds(nowMs()),
                modelId = modelId,
            ),
            finishReason = finishReason,
        )
    }

    /** Generate a fake tool-call id. Stable per FakeWeftLLM instance. */
    public fun nextToolId(): String = "tu_fake_${toolIdCounter.incrementAndGet().toString().padStart(4, '0')}"

    // -------------------------------------------------------------------------
    // DSL — builder for assembling a fake from a test
    // -------------------------------------------------------------------------

    public class Builder {
        private val rules: MutableList<Rule> = mutableListOf()
        private val script: MutableList<FakeStep> = mutableListOf()
        private var policy: UnscriptedPolicy = UnscriptedPolicy.Throw
        private var nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }

        /** Add a rule. First non-null match across rules wins. */
        public fun rule(rule: Rule): Builder = apply { rules += rule }

        /**
         * Convenience rule: when the latest user message matches [contains],
         * return a [Text] step with [reply].
         */
        public fun whenUserSays(contains: String, reply: String): Builder = rule(
            Rule { prompt, _, _ ->
                val last = prompt.messages.lastOrNull { it is Message.User }?.textContent().orEmpty()
                if (last.contains(contains, ignoreCase = true)) FakeStep.Text(reply) else null
            },
        )

        /**
         * Convenience rule: when the latest user message matches [contains],
         * call [tool] with [argsJson].
         */
        public fun whenUserSaysCallTool(
            contains: String,
            tool: String,
            argsJson: String,
        ): Builder = rule(
            Rule { prompt, _, _ ->
                val last = prompt.messages.lastOrNull { it is Message.User }?.textContent().orEmpty()
                if (last.contains(contains, ignoreCase = true)) {
                    FakeStep.CallTool(name = tool, argsJson = argsJson)
                } else null
            },
        )

        public fun text(reply: String): Builder = apply { script += FakeStep.Text(reply) }

        public fun callTool(name: String, argsJson: String, id: String = "tu_fake_0001"): Builder =
            apply { script += FakeStep.CallTool(name, argsJson, id) }

        public fun callTools(vararg calls: ToolCallSpec): Builder =
            apply { script += FakeStep.CallTools(calls.toList()) }

        public fun textThenCall(text: String, call: ToolCallSpec): Builder =
            apply { script += FakeStep.TextThenCall(text, call) }

        public fun error(message: String, cause: Throwable? = null): Builder =
            apply { script += FakeStep.Error(message, cause) }

        public fun onUnscripted(policy: UnscriptedPolicy): Builder = apply { this.policy = policy }

        public fun clock(nowMs: () -> Long): Builder = apply { this.nowMs = nowMs }

        public fun build(): FakeWeftLLM =
            FakeWeftLLM(rules = rules.toList(), script = script.toList(), onUnscripted = policy, nowMs = nowMs)
    }

    public companion object {
        public inline fun build(block: Builder.() -> Unit): FakeWeftLLM =
            Builder().apply(block).build()
    }
}

// =============================================================================
// Public types
// =============================================================================

/** One scripted action the fake will return. */
public sealed class FakeStep {
    /** Plain text reply, `stop_reason = end_turn`. */
    public data class Text(public val text: String) : FakeStep()

    /** One tool call, `stop_reason = tool_use`. */
    public data class CallTool(
        public val name: String,
        public val argsJson: String,
        public val id: String = "tu_fake_${Clock.System.now().toEpochMilliseconds() % 9999}",
    ) : FakeStep()

    /** Multiple tool calls in one response (parallel tools). */
    public data class CallTools(public val calls: List<ToolCallSpec>) : FakeStep()

    /** Text + one tool call in the same response (common Anthropic pattern). */
    public data class TextThenCall(
        public val text: String,
        public val call: ToolCallSpec,
    ) : FakeStep()

    /** Simulated API failure. */
    public data class Error(
        public val message: String,
        public val cause: Throwable? = null,
    ) : FakeStep()
}

public data class ToolCallSpec(
    public val name: String,
    public val argsJson: String,
    public val id: String = "tu_fake_${Clock.System.now().toEpochMilliseconds() % 9999}",
)

/** A rule sees the prompt + model + tools and optionally produces a step. */
public fun interface Rule {
    public fun match(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): FakeStep?
}

/** What to do when the rules + script are both exhausted. */
public sealed class UnscriptedPolicy {
    /** Throw a descriptive error — the default. Tests should be deterministic. */
    public data object Throw : UnscriptedPolicy()

    /** Use [step] as the response. Set to `FakeStep.Text("OK")` for forgiving tests. */
    public data class Default(public val step: FakeStep) : UnscriptedPolicy()
}

/** Captured invocation. */
public data class RecordedCall(
    public val prompt: Prompt,
    public val model: LLModel,
    public val tools: List<ToolDescriptor>,
    public val streaming: Boolean,
    public val source: CallSource,
)

public enum class CallSource { Rule, Script, Unscripted }

public class FakeLlmException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
