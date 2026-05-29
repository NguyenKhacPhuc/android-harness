package dev.weft.harness.agents.multimodal

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.weft.contracts.ToolActivationSink
import dev.weft.contracts.ToolProvider
import dev.weft.harness.prompt.multimodal.WeftUserInput
import dev.weft.harness.prompt.multimodal.buildUserParts
import dev.weft.tools.ResolvedWeftTool
import kotlinx.atomicfu.atomic
import kotlin.coroutines.coroutineContext

/**
 * Non-streaming chat strategy that accepts [WeftUserInput] instead of a
 * raw String. Mirrors Koog's built-in
 * [ai.koog.agents.core.agent.singleRunStrategy] one-for-one, with three
 * differences:
 *
 *  1. The first node emits a user message whose `parts` list contains
 *     the text **and** the user's attachments (image / audio / video /
 *     file).
 *  2. A `nodeApplyActivations` sits between `nodeExecuteTools` and
 *     `nodeSendToolResult` to drain the [ToolActivationSink] and apply
 *     the two mutations Stage 2 of `docs/architecture/tool-provider.md`
 *     needs: extend the LLM-facing tool list AND extend the wire-level
 *     ToolRegistry. When [toolProvider] is null (back-compat, eager
 *     mode) this node is a no-op.
 *  3. A `nodeNarrateGuard` sits on the text-after-tools edge and
 *     re-prompts when the model emits future-tense intent
 *     ("Now let me fetch your weather!") without firing the
 *     corresponding tool. See the safety-net note below — this is
 *     a defense-in-depth backstop for `BEHAVIORAL_RULES` rules 1+2,
 *     which the model sometimes ignores.
 *
 * Flow:
 *   nodeStart
 *     → nodeCallLLM (WeftUserInput → Message.Assistant)
 *         ↘ onTextMessage → nodeFinish
 *         ↘ onToolCalls   → nodeExecuteTools (ReceivedToolResults)
 *                             → nodeApplyActivations (pass-through + side effect)
 *                             → nodeSendToolResult (Message.Assistant)
 *                                 ↘ onTextMessage → nodeNarrateGuard
 *                                                     ↘ onTextMessage → nodeFinish
 *                                                     ↘ onToolCalls   → nodeExecuteTools (loop)
 *                                 ↘ onToolCalls   → nodeExecuteTools (loop)
 *
 * The first LLM call goes straight to `nodeFinish` on a text response —
 * we only intercept text-after-tools, since the failure mode the guard
 * targets is "agent did setup tools then narrated 'now let me X' without
 * emitting X". A text-only first response is just a question / chat
 * reply and shouldn't be second-guessed.
 *
 * `buildUserParts` lives in `:harness:prompt` (pure prompt-shaping
 * function); imported here so the strategy node can emit the right
 * message-parts list.
 *
 * ### Safety net: narrate-without-emit guard
 *
 * `WeftSystemPromptDefaults.BEHAVIORAL_RULES` already tells the model
 * "act first, narrate after" and "never narrate a tool call you don't
 * make." The model ignores those rules a non-trivial fraction of the
 * time, particularly after a chain of setup tools (location, ui_ask,
 * memory_recall) — it writes a sentence like "Now let me fetch your
 * weather!" and ends the turn without firing the next tool. The guard
 * detects that shape in the assistant's text and re-prompts once per
 * run with a synthetic "continue or rephrase past-tense" nudge. Cap is
 * one retry per `agent.run()`; false positives cost one extra LLM call,
 * false negatives leave behavior unchanged.
 *
 * @param toolProvider the runtime's [ToolProvider]. When non-null, the
 *   activation node resolves names drained from the sink and mutates
 *   `llm.tools` + `llm.toolRegistry`. Null = eager mode, no activation.
 * @param narrateGuardEnabled disable the safety-net guard. Default true.
 *   Hosts can flip to false if their UX prefers terminating cleanly on
 *   text and surfacing a "continue?" affordance themselves instead.
 */
internal fun weftSingleRunStrategy(
    toolProvider: ToolProvider? = null,
    narrateGuardEnabled: Boolean = true,
): AIAgentGraphStrategy<WeftUserInput, String> =
    strategy<WeftUserInput, String>("weft_single_run") {
        // Per-run retry counter. The strategy graph is built once per
        // AIAgent, and AIAgent is rebuilt for every WeftAgent.send() call
        // (see buildAgentForThisTurn) — so this AtomicInteger is
        // effectively per-turn state. Reset on each strategy construction
        // because each call to `strategy { … }` produces a fresh
        // AIAgentGraphStrategy with its own closure.
        val guardRetries = atomic(0)

        val nodeCallLLM by node<WeftUserInput, Message.Assistant>("weft_call_llm") { input ->
            llm.writeSession {
                appendPrompt {
                    user(buildUserParts(input))
                }
                requestLLM()
            }
        }
        val nodeExecuteToolsX by nodeExecuteTools()
        val nodeApplyActivations by node<ReceivedToolResults, ReceivedToolResults>(
            "weft_apply_activations",
        ) { results ->
            if (toolProvider == null) return@node results
            val sink = coroutineContext[ToolActivationSink.Key] ?: return@node results
            val names = sink.drain()
            if (names.isEmpty()) return@node results

            val resolved = mutableListOf<ResolvedWeftTool>()
            for (name in names) {
                val r = toolProvider.resolve(name)
                if (r is ResolvedWeftTool) resolved += r
                // r==null or non-Weft -> silently skip; the LLM saw the
                // search result but the tool isn't registered. Next
                // iteration just won't find it; agent surfaces a
                // tool-not-found error from Koog's executor.
            }
            if (resolved.isEmpty()) return@node results

            // (1) Advertise to the next LLM call. `tools` is a mutable
            // property on the persistent AIAgentLLMContext; updating it
            // here propagates to whatever `nodeCallLLM` opens next.
            // Filter out anything already present (defensive: the model
            // may have called find_tool twice).
            llm.writeSession {
                val current = tools
                val currentNames = current.mapTo(HashSet()) { it.name }
                val additions = resolved
                    .map { it.tool.descriptor }
                    .filter { it.name !in currentNames }
                if (additions.isNotEmpty()) tools = current + additions
            }
            // (2) Make dispatchable when the LLM calls them. ToolRegistry's
            // `add()` is public and the GenericAgentEnvironment looks tools
            // up by name on each executeTool call. `llm.toolRegistry` is
            // a Kotlin property on AIAgentLLMContextCommon (Koog 1.0.0).
            val registry = llm.toolRegistry
            val registryNames = registry.tools.mapTo(HashSet()) { it.descriptor.name }
            for (r in resolved) {
                if (r.tool.descriptor.name !in registryNames) registry.add(r.tool)
            }
            results
        }
        val nodeSendToolResult by nodeLLMSendToolResults()

        // Safety-net node — intercepts text-after-tools. See KDoc above.
        // Either passes through unchanged (→ nodeFinish via onTextMessage)
        // or re-prompts the LLM and returns its new message (which may
        // itself be text or tool_calls — edges route accordingly).
        val nodeNarrateGuard by node<Message.Assistant, Message.Assistant>(
            "weft_narrate_guard",
        ) { msg ->
            // Pass-through when the message contains tool calls — those
            // route to nodeExecuteToolsX via the outgoing onToolCalls
            // edge and don't need guarding. We only target text-only
            // turns that look like narrate-without-emit.
            val hasToolCalls = msg.parts.any { it is MessagePart.Tool.Call }
            if (hasToolCalls) return@node msg
            if (!narrateGuardEnabled) return@node msg
            if (guardRetries.value > 0) return@node msg
            if (!looksLikeNarrateWithoutEmit(msg.textContent())) return@node msg

            guardRetries.incrementAndGet()
            llm.writeSession {
                appendPrompt {
                    user(NARRATE_GUARD_NUDGE)
                }
                requestLLM()
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteToolsX onToolCalls { true })
        // First response is text-only — never guard. Question replies
        // shouldn't be second-guessed; the guard only targets the
        // "did setup tools, then narrated next step" failure mode.
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteToolsX forwardTo nodeApplyActivations)
        edge(nodeApplyActivations forwardTo nodeSendToolResult)
        // Route everything from nodeSendToolResult through the guard;
        // the guard internally distinguishes text vs tool_calls and
        // either passes the message through (→ outgoing edges fork on
        // onTextMessage / onToolCalls) or re-prompts and returns a new
        // assistant message that the outgoing edges also fork on.
        edge(nodeSendToolResult forwardTo nodeNarrateGuard)
        edge(nodeNarrateGuard forwardTo nodeFinish onTextMessage { true })
        edge(nodeNarrateGuard forwardTo nodeExecuteToolsX onToolCalls { true })
    }

/**
 * Synthetic continuation prompt the narrate-guard injects when it
 * detects a turn that ended with future-tense intent and no tool_use.
 *
 * Phrased as a user message (the only thing the LLM session lets us
 * append at this point) — works because the model treats it as a
 * directive to continue the in-progress task. Brief on purpose so it
 * doesn't dominate the next turn's context.
 */
private const val NARRATE_GUARD_NUDGE: String =
    "You ended that turn with future-tense intent but no tool_use block. " +
        "Either emit the next tool_use now to continue the work, or " +
        "rephrase your last message in past tense (or omit it entirely)."

/**
 * Heuristic detector for the narrate-without-emit pattern. Returns true
 * when the message's tail looks like "I'll do X next" / "Now let me do
 * Y" — future-tense intent that should have been a tool_use instead.
 *
 * Tuned for low false-positive rate. Excludes the common conversational
 * tails ("Let me know if…", "Would you like…", "I'll be happy to…")
 * that aren't task-continuation signals. False positives cost one extra
 * LLM call per turn; false negatives leave behavior unchanged.
 *
 * Internal so the test in `WeftStrategiesNarrateGuardTest` (if added)
 * can exercise the edge cases directly without spinning up a full
 * AIAgent.
 */
internal fun looksLikeNarrateWithoutEmit(text: String): Boolean {
    if (text.isBlank()) return false

    // Grab the tail — last 200 chars is enough to catch the closing
    // sentence without scanning multi-paragraph essays. The pattern
    // we target lives at the end of the message ("…now let me X!").
    val tail = text.takeLast(NARRATE_GUARD_TAIL_LEN).lowercase()

    // Cheap exclusions first — these are common assistant-style trailers
    // that look like future intent but are conversational, not task
    // continuation.
    NARRATE_GUARD_EXCLUSIONS.forEach { exclusion ->
        if (tail.contains(exclusion)) return false
    }

    return NARRATE_GUARD_INTENT_PATTERN.containsMatchIn(tail)
}

private const val NARRATE_GUARD_TAIL_LEN = 200

/**
 * Common false-positive triggers. "Let me know if you'd like X" is
 * polite-trailer text, not a task-continuation signal. Listed lowercase
 * for direct match against the lowercased tail.
 */
private val NARRATE_GUARD_EXCLUSIONS: List<String> = listOf(
    "let me know",
    "would you like",
    "if you'd like",
    "if you want",
    "i'll be happy",
    "i'd be happy",
    "i'll wait",
    "i'll keep",
    "i'll remember",
)

/**
 * Future-tense intent patterns the guard treats as "you stopped mid-task."
 * The boundary marker `\b` keeps us from matching "letterme" or similar.
 */
private val NARRATE_GUARD_INTENT_PATTERN: Regex = Regex(
    """\b(let me|let's|i'll|i will|i'm going to|i am going to|going to|now i'll|next i'll|now let me)\b""",
)
