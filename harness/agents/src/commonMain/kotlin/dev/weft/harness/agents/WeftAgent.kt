package dev.weft.harness.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import dev.weft.harness.behavior.BehaviorConfig
import dev.weft.harness.behavior.Compactor
import dev.weft.harness.behavior.Turn
import dev.weft.harness.cost.QuotaExceededException
import dev.weft.harness.cost.QuotaPolicy
import dev.weft.harness.cost.QuotaState
import dev.weft.harness.cost.UsageStore
import dev.weft.harness.observability.Redactor
import dev.weft.harness.observability.TraceStore
import dev.weft.harness.conversation.ConversationStore
import dev.weft.harness.conversation.PersistedRole
import dev.weft.harness.agents.streaming.StreamChunk
import dev.weft.harness.agents.streaming.streamingSingleRunStrategy
import dev.weft.harness.prompt.composeEffectiveText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.weft.harness.reliability.CircuitBreaker
import dev.weft.harness.reliability.RetryPolicy
import dev.weft.harness.reliability.withRetry
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A multi-turn wrapper around Koog's [AIAgent] with built-in observability,
 * reliability, behavior (compaction), and cost-tracking.
 *
 * On each [send]:
 *   1. Checks the daily quota (refuses with [QuotaExceededException] if over the cap).
 *   2. Starts an [AgentTrace][dev.weft.harness.observability.AgentTrace] in the [TraceStore].
 *   3. Builds a fresh [AIAgent] with conversation history baked into the initial [Prompt].
 *   4. Installs a Koog event handler that records every LLM call + tool call
 *      against the current trace AND emits [ToolEvent]s for the chat UI.
 *   5. Wraps the call in retry-with-circuit-breaker.
 *   6. Calls `agent.run(text)` and appends user/assistant turns to history.
 *   7. Marks the trace completed (or failed).
 *
 * Cost: a new AIAgent + ToolRegistry per send. Negligible.
 */
@OptIn(ExperimentalUuidApi::class)
class WeftAgent(
    private val executor: PromptExecutor,
    /**
     * Provider's model pool — the menu the [modelRouter] picks from per
     * turn. Built by [WeftRuntime.buildAgent] based on the active
     * provider kind. Mandatory because routing has no sensible
     * provider-agnostic default.
     */
    private val modelPool: dev.weft.harness.agents.routing.ModelPool,
    /**
     * Picks the model for each turn. Default is
     * [dev.weft.harness.agents.routing.DefaultModelRouter] (deterministic rules:
     * vision → vision-capable, coding hints → heavy, short fresh chat →
     * cheap, else standard). Pass [dev.weft.harness.agents.routing.StaticModelRouter]
     * to disable routing and pin a single model.
     */
    private val modelRouter: dev.weft.harness.agents.routing.ModelRouter =
        dev.weft.harness.agents.routing.DefaultModelRouter(),
    private val toolRegistry: ToolRegistry,
    private val traceStore: TraceStore,
    /** Returns the stable substrate system prompt — no per-turn volatile content. */
    private val baseSystemPromptSupplier: () -> String,
    /**
     * Returns the per-turn volatile prefix (device snapshot etc.) that goes
     * into the latest user message so the system layer stays cacheable.
     * Empty string disables.
     */
    private val volatilePrefixSupplier: () -> String = { "" },
    private val conversationId: String = Uuid.random().toString(),
    private val maxIterations: Int = MAX_ITERATIONS_DEFAULT,
    /**
     * Per-LLM-call `max_tokens` budget. Koog's Anthropic client defaults to
     * 2048 when `LLMParams.maxTokens` is unset — too small for `ui_render`
     * tool calls that emit big component trees (a music-player JSON tree
     * blows past 2048 easily). We bump the default to 8192 which is the
     * documented cap for Claude Sonnet 4.x without extended-thinking.
     * Apps can override if they're using a model with a different ceiling.
     */
    private val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    val circuitBreaker: CircuitBreaker = CircuitBreaker(),
    private val behaviorConfig: BehaviorConfig = BehaviorConfig(),
    private val usageStore: UsageStore = dev.weft.harness.cost.InMemoryUsageStore(),
    private val quotaPolicy: QuotaPolicy = QuotaPolicy(),
    /**
     * Applied to tool args / results / error messages before they're written
     * to [TraceStore]. Default rules mask emails, SSNs, bearer tokens, API
     * keys, and credit-card numbers. Pass `Redactor(rules = emptyList())` to
     * disable; pass `Redactor(Redactor.DEFAULT_RULES + customRules)` to extend.
     */
    private val redactor: Redactor = Redactor(),
    /**
     * Optional persistent conversation store. When provided, every USER and
     * ASSISTANT turn is appended; [resume] hydrates history from it on
     * startup; [newChat] rotates to a fresh conversation id. Pass `null`
     * (the default for tests and one-off agents) to keep history in-memory.
     */
    private val conversationStore: ConversationStore? = null,
    /**
     * Provider-specific cache-control emitter. Defaults to [dev.weft.harness.prompt.cache.NoOpCacheBinder]
     * so tests and non-Anthropic configurations don't accidentally embed markers
     * the backend rejects. `WeftRuntime.buildAgent` injects
     * [dev.weft.harness.prompt.cache.AnthropicCacheBinder] when the active provider is
     * Anthropic / Anthropic-proxy.
     *
     * v1 marks only the system message (`STATIC` tier). Wider coverage —
     * tool catalog, history tail, multi-tier breakpoints — comes in a
     * follow-up so the diff stays small and the cache-hit telemetry can
     * be validated against a single change.
     */
    private val cacheBinder: dev.weft.harness.prompt.cache.CacheBinder = dev.weft.harness.prompt.cache.NoOpCacheBinder,
    /**
     * Memory-retrieval extension point. Queried per-turn with the user's
     * current message text; resulting hits are injected as "Relevant
     * context" inside the volatile prefix so the LLM sees them without
     * needing a tool round-trip. Default is an empty registry — no
     * memory injection — for tests and plain text-only agents.
     *
     * `WeftRuntime` wires this with the substrate's own memory provider
     * plus any apps registered via `extraMemoryProviders`.
     */
    private val memoryRegistry: dev.weft.harness.memory.MemoryRegistry =
        dev.weft.harness.memory.MemoryRegistry(providers = emptyList()),
    /**
     * Per-agent loop policy. Read each turn for retry, cache, routing,
     * and iteration cap. Defaults to [dev.weft.harness.agents.strategy.DefaultStrategy]
     * which mirrors today's hardcoded values; passing a different impl
     * ([dev.weft.harness.agents.strategy.FrugalStrategy] or a custom
     * type) overrides those defaults. See
     * `docs/architecture/strategy-hook.md` for the contract.
     */
    private val strategy: dev.weft.harness.agents.strategy.WeftStrategy =
        dev.weft.harness.agents.strategy.DefaultStrategy(maxIterationsValue = maxIterations),
    /**
     * Name of the [AgentDeclaration] this agent was built from.
     * Defaults to [AgentDeclaration.DEFAULT_AGENT_NAME] when constructed
     * directly (tests, single-agent setups). [WeftRuntime.buildAgent]
     * threads the declaration's name through automatically. Persisted on
     * every conversation row so chat surfaces can label assistant turns
     * by agent.
     */
    private val agentName: String = AgentDeclaration.DEFAULT_AGENT_NAME,
    /**
     * Lifecycle interception. Fires [dev.weft.contracts.HookContext.UserMessage]
     * before any LLM work, [dev.weft.contracts.HookContext.TurnStart] after
     * trace/quota setup, and [dev.weft.contracts.HookContext.TurnEnd] /
     * [dev.weft.contracts.HookContext.TurnFailed] at the boundary. Pre-tool
     * hooks fire from inside [dev.weft.tools.WeftTool.execute] — wire the
     * same registry there via [dev.weft.tools.WeftContext.hooks] so a
     * single set of hooks sees both surfaces.
     *
     * Default empty registry = zero overhead.
     */
    private val hooks: dev.weft.contracts.HookRegistry = dev.weft.contracts.HookRegistry.EMPTY,
    /**
     * Approval-mode holder. Read each turn to decide whether to inject
     * a plan-mode directive into the volatile prefix. Pass the SAME
     * instance to [dev.weft.tools.WeftContext.approvalMode] so the
     * tool gate and the prompt directive stay in sync. Default is a
     * fresh holder pinned to [dev.weft.contracts.ApprovalMode.Default].
     */
    private val approvalMode: dev.weft.contracts.ApprovalModeHolder =
        dev.weft.contracts.ApprovalModeHolder(),
    /**
     * Lazy tool catalog — Stage 2 of `docs/architecture/tool-provider.md`.
     *
     * When non-null, the strategy's activation graph node drains the
     * per-turn [dev.weft.contracts.ToolActivationSink] after every tool
     * batch and uses this provider to resolve names returned by
     * `find_tool` into [dev.weft.tools.ResolvedWeftTool] instances. The
     * resolved tools are added to the LLM's tool descriptor list AND
     * to the Koog `ToolRegistry` for the remainder of the same
     * `agent.run()` call.
     *
     * Null = eager mode. The activation node is a no-op; all tools must
     * already be in [toolRegistry] at agent-build time. Back-compat
     * default; existing single-provider hosts see zero behavior change.
     */
    private val toolProvider: dev.weft.contracts.ToolProvider? = null,
) {
    private val compactor = Compactor(behaviorConfig)
    private val history: MutableList<HistoryEntry> = mutableListOf()

    /**
     * Emit the compacted history into the active prompt builder, marking
     * the last user turn within the older block (`compacted.dropLast(N)`)
     * with a SESSION cache directive. Both `send` and `sendStreaming`
     * share this so cache behavior stays identical across the two paths.
     */
    private fun ai.koog.prompt.dsl.PromptBuilder.emitCompactedHistory(compacted: List<Turn>) {
        val older = compacted.dropLast(strategy.historyVolatileTailTurns)
        // indexOfLast returns -1 when the older block has no User turn —
        // either it's empty or it's an all-Assistant fragment (unusual).
        // Either way the comparison below fails for every index and we
        // emit everything plain, which is fine.
        val sessionMarkerIdx = older.indexOfLast { it is Turn.User }
        val olderTier = strategy.cacheTiers["history-older"]
            ?: dev.weft.harness.prompt.cache.CacheTier.SESSION
        for ((idx, turn) in compacted.withIndex()) {
            when (turn) {
                is Turn.System -> system(turn.text)
                is Turn.User -> {
                    if (idx == sessionMarkerIdx) {
                        cacheBinder.cachedUser(this, turn.text, olderTier)
                    } else {
                        user(turn.text)
                    }
                }
                is Turn.Assistant -> assistant(turn.text)
            }
        }
    }



    // ── Reactive surface (new in Phase 1 of the Flow-based refactor) ──────

    /**
     * Long-lived scope for intent-handling coroutines launched via
     * [dispatch]. Survives across turns; cancelled implicitly when the
     * process dies. The legacy `suspend` API (`send`, `sendStreaming`,
     * etc.) continues to run on the *caller's* scope — only `dispatch`
     * uses this one.
     */
    private val agentScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Tracks the [Job] for the currently-running [AgentIntent.Send] /
     * [AgentIntent.Regenerate] / [AgentIntent.SendEvent] so
     * [AgentIntent.CancelCurrentTurn] can cancel it.
     */
    private var currentTurnJob: Job? = null

    private val _state: MutableStateFlow<AgentState> = MutableStateFlow(
        AgentState.initial(
            conversationId = conversationId,
            agentName = agentName,
            breaker = circuitBreaker.state.value,
        ),
    )

    /**
     * Unified reactive projection of this agent's runtime state.
     *
     * Updated automatically by both the new [dispatch] API and the
     * deprecated suspending methods — every call site that mutates the
     * agent's observable shape (history append, turn start/end, tool
     * lifecycle, conversation switch, error, breaker tick) emits a new
     * state value here.
     *
     * Hosts that want a one-stop subscription for chat UI should tail
     * `state.history`, `state.turnStatus`, `state.pendingAssistantDelta`,
     * and `state.activeToolCalls` instead of stitching together
     * `events` + `currentConversationId` + the conversation store.
     */
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _effects: MutableSharedFlow<AgentEffect> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )

    /**
     * One-shot signals — quota blocked, breaker opened, miscellaneous
     * `Notify` messages from retry attempts and other turn-time events
     * that don't belong on the [state] object.
     */
    val effects: SharedFlow<AgentEffect> = _effects.asSharedFlow()

    init {
        // Mirror the underlying CircuitBreaker into AgentState. The
        // breaker is the canonical source of truth — we just project
        // its state changes into the agent-level projection so hosts
        // have a single subscription. Emit BreakerOpened as a one-shot
        // effect on every Closed→Open transition.
        agentScope.launch {
            var lastWasOpen = circuitBreaker.state.value is CircuitBreaker.State.Open
            circuitBreaker.state.collect { breakerState ->
                _state.update { it.copy(breaker = breakerState) }
                val nowOpen = breakerState is CircuitBreaker.State.Open
                if (nowOpen && !lastWasOpen) {
                    _effects.tryEmit(
                        AgentEffect.BreakerOpened(
                            openedAtEpochMs = breakerState.openedAtEpochMs,
                            openDurationMs = circuitBreaker.openDuration.inWholeMilliseconds,
                        ),
                    )
                }
                lastWasOpen = nowOpen
            }
        }
    }

    /**
     * Push an intent into the agent. Returns the [Job] backing the
     * launched coroutine for intents that do background work, or
     * `null` for intents that complete synchronously
     * ([AgentIntent.ResetHistory] / [AgentIntent.CancelCurrentTurn] /
     * [AgentIntent.ClearError]).
     *
     * Most callers ignore the return value — use [dispatchAndAwait]
     * when you need to suspend until the intent's effects are visible
     * (e.g. reading `state.value.conversationId` immediately after a
     * `NewChat`).
     *
     * Multiple Send/Regenerate intents dispatched in quick succession
     * are NOT serialized by the agent — the caller should gate
     * dispatches behind `state.value.turnStatus == TurnStatus.Idle` to
     * avoid interleaving (the underlying Koog AIAgent is single-turn
     * per `agent.run` call, so two parallel turns share the same
     * compactor + history reads in undefined order).
     *
     * [AgentIntent.CancelCurrentTurn] is always safe to dispatch and
     * is a no-op when nothing is running.
     */
    fun dispatch(intent: AgentIntent): Job? {
        return when (intent) {
            is AgentIntent.Send -> launchTurn {
                if (intent.streaming) {
                    sendStreaming(intent.text, intent.attachments, intent.tier).collect()
                } else {
                    send(intent.text, intent.attachments, intent.tier)
                }
            }
            is AgentIntent.SendEvent -> launchTurn {
                // Stream the synthetic UI-event message so callers
                // observing `state.pendingAssistantDelta` see the
                // reply incrementally — same projection model as a
                // user-typed Send. See `composeEventMessage`.
                val message = composeEventMessage(intent.action, intent.sourceLabel, intent.fieldValues)
                sendStreaming(message).collect()
            }
            is AgentIntent.Regenerate -> launchTurn {
                if (intent.streaming) {
                    regenerateStreaming().collect()
                } else {
                    regenerate()
                }
            }
            AgentIntent.NewChat -> agentScope.launch {
                newChat()
            }
            is AgentIntent.Resume -> agentScope.launch {
                resume(intent.conversationId)
            }
            AgentIntent.ResetHistory -> {
                resetHistory()
                null
            }
            AgentIntent.CancelCurrentTurn -> {
                currentTurnJob?.cancel()
                null
            }
            AgentIntent.ClearError -> {
                _state.update {
                    it.copy(
                        lastError = null,
                        turnStatus = if (it.turnStatus == TurnStatus.Failed) TurnStatus.Idle else it.turnStatus,
                    )
                }
                null
            }
        }
    }

    /**
     * Suspend variant of [dispatch] — fires the intent and suspends
     * until the launched work has completed. Use when the next line
     * reads agent state that the intent mutates (typical example:
     * reading [AgentState.conversationId] immediately after
     * [AgentIntent.NewChat]).
     *
     * Returns when:
     *
     *   - For [AgentIntent.Send] / [AgentIntent.SendEvent] /
     *     [AgentIntent.Regenerate] — the turn has finished (success,
     *     failure, or cancellation).
     *   - For [AgentIntent.NewChat] / [AgentIntent.Resume] — the new
     *     conversation id is live in [state].
     *   - For [AgentIntent.CancelCurrentTurn] — the cancelled turn's
     *     coroutine has fully unwound.
     *   - For sync intents ([AgentIntent.ResetHistory] /
     *     [AgentIntent.ClearError]) — immediately (no work to await).
     *
     * Throws if the underlying turn threw an unhandled exception —
     * mirrors what the deprecated `suspend send()` did before. For
     * fire-and-forget UI dispatches, use [dispatch] and observe state.
     */
    suspend fun dispatchAndAwait(intent: AgentIntent) {
        dispatch(intent)?.join()
    }

    /**
     * Launch [block] in [agentScope] as the new "current turn" job —
     * cancellation of the previous turn (if any) is the caller's
     * responsibility via [AgentIntent.CancelCurrentTurn]. The job
     * pointer is cleared when [block] completes so a stale Job ref
     * isn't held across idle periods.
     *
     * Returns the launched [Job] so [dispatchAndAwait] can `.join()`
     * on it.
     */
    private fun launchTurn(block: suspend () -> Unit): Job {
        val job = agentScope.launch {
            try {
                block()
            } finally {
                // Only clear if no NEW turn was launched after this one started.
                // Use referential equality on Job — set at coroutine creation
                // below so the closure captures the right ref.
                val self = kotlin.coroutines.coroutineContext[Job]
                if (currentTurnJob === self) {
                    currentTurnJob = null
                }
            }
        }
        currentTurnJob = job
        return job
    }

    /**
     * Convert the legacy private `HistoryEntry` list into the [Turn]
     * sequence stored on [AgentState.history]. Called after every
     * mutation of [history] inside the imperative methods so the
     * reactive surface stays in lockstep.
     */
    private fun syncHistoryToState() {
        val turns: List<Turn> = history.map { entry ->
            when (entry.role) {
                Role.USER -> Turn.User(entry.text)
                Role.ASSISTANT -> Turn.Assistant(entry.text)
            }
        }
        _state.update { it.copy(history = turns) }
    }

    /**
     * Remove [key] from [AgentState.activeToolCalls]. Called from the
     * Koog event handler when a tool finishes or fails. We don't
     * transition [AgentState.turnStatus] back to [TurnStatus.Streaming]
     * here — the next text delta will do it naturally, or the turn
     * completion code will set Idle. Avoids a transient
     * ToolRunning→Streaming→ToolRunning flutter when the LLM
     * back-to-backs another tool call.
     */
    private fun removeActiveToolCall(key: String) {
        _state.update { it.copy(activeToolCalls = it.activeToolCalls.filterNot { call -> call.id == key }) }
    }

    /**
     * Text-only convenience overload. Equivalent to
     * `send(userText, attachments = emptyList())`. The most common path
     * stays string-shaped so existing callers don't change.
     */
    internal suspend fun send(userText: String): String =
        send(userText, attachments = emptyList(), modelTier = null)

    /**
     * Text-only send with a per-turn [dev.weft.harness.agents.routing.ModelTier]
     * override. Bypasses the [dev.weft.harness.agents.routing.ModelRouter]'s
     * normal heuristics — the picked tier comes straight from [modelTier].
     * Passing `null` is identical to the no-tier overload (normal routing).
     */
    internal suspend fun send(
        userText: String,
        modelTier: dev.weft.harness.agents.routing.ModelTier?,
    ): String = send(userText, attachments = emptyList(), modelTier = modelTier)

    /**
     * Send a user turn with optional multimodal attachments (image bytes,
     * image URLs, PDFs, audio, etc.). The attachments are baked into a
     * single user message alongside the text — Anthropic and OpenAI vision
     * APIs both expect "one message, mixed parts" rather than separate
     * messages per attachment.
     *
     * Use [dev.weft.harness.prompt.multimodal.Attachments] for ergonomic
     * construction of common attachment shapes (image bytes, image URL,
     * PDF, audio). Power users can construct
     * [ai.koog.prompt.message.MessagePart.Attachment] directly.
     *
     * **Persistence note (v1):** the [attachments] list is in-memory for
     * the current process. Resuming the conversation later replays only
     * the [userText] portion; attached media is not currently stored to
     * disk. The agent's *current* turn always sees the full multimodal
     * input.
     */
    internal suspend fun send(
        userText: String,
        attachments: List<ai.koog.prompt.message.MessagePart.Attachment>,
        modelTier: dev.weft.harness.agents.routing.ModelTier? = null,
    ): String {
        val quotaState = quotaPolicy.check(usageStore.usdToday())
        _state.update { it.copy(quota = quotaState) }
        if (quotaState is QuotaState.Blocked) {
            _effects.tryEmit(
                AgentEffect.QuotaBlocked(
                    usdToday = quotaState.usdToday,
                    capUsd = quotaState.thresholdUsd,
                ),
            )
            throw QuotaExceededException(quotaState.usdToday, quotaState.thresholdUsd)
        }
        _state.update {
            it.copy(
                turnStatus = TurnStatus.Sending,
                lastError = null,
                pendingAssistantDelta = "",
                activeToolCalls = emptyList(),
            )
        }
        hooks.onUserMessage(
            dev.weft.contracts.HookContext.UserMessage(
                traceId = "",
                conversationId = _state.value.conversationId,
                text = userText,
                hasAttachments = attachments.isNotEmpty(),
            ),
        )
        // Read parent trace id from the coroutine context. Null on
        // user-initiated turns (no enclosing TraceContext); set when this
        // `send` is called from inside a SubAgentRunner that itself runs
        // inside an orchestrator's TraceContext. The propagation is
        // automatic — sub-agents don't pass it explicitly.
        val parentTraceId = kotlin.coroutines.coroutineContext[
            dev.weft.harness.observability.TraceContext,
        ]?.traceId
        val traceId = traceStore.startTrace(_state.value.conversationId, userText, parentTraceId)
        // Memory retrieval runs concurrently across all registered
        // providers (substrate + app-provided). Hits are inlined as
        // "Relevant context" inside the volatile prefix so the LLM sees
        // them without a tool round-trip. Empty registry / no hits =
        // identical behaviour to before this hook existed.
        val memoryHits = memoryRegistry.retrieveAll(userText)
        val effectiveText = composeEffectiveText(
            volatilePrefix = composeVolatilePrefixWithMode(),
            memoryHits = memoryHits,
            userText = userText,
        )
        val effectiveInput = dev.weft.harness.prompt.multimodal.WeftUserInput(effectiveText, attachments)
        // Install our own TraceContext for the duration of this turn so
        // every nested `traceStore.startTrace` (sub-agents, future
        // delegated work) inherits this trace as their parent AND
        // shares this conversation id (so sub-agent rows attach to the
        // same conversation in conversation-scoped queries).
        return kotlinx.coroutines.withContext(
            dev.weft.harness.observability.TraceContext(traceId, _state.value.conversationId),
        ) {
        hooks.onTurnStart(
            dev.weft.contracts.HookContext.TurnStart(
                traceId = traceId,
                conversationId = _state.value.conversationId,
                userText = userText,
                modelId = modelPool.standard.id,
            ),
        )
        try {
            val reply = withRetry(
                policy = strategy.retry,
                breaker = circuitBreaker,
                onAttemptFailed = { attempt, cause, retryingInMs ->
                    val msg = if (retryingInMs != null) "attempt $attempt failed (${cause.message}), retrying in ${retryingInMs}ms" else "attempt $attempt failed: ${cause.message}"
                    _effects.tryEmit(AgentEffect.ToolFailed(toolName = "llm.retry", message = msg))
                    _effects.tryEmit(AgentEffect.Notify(message = msg))
                },
            ) {
                val agent = buildAgentForThisTurn(traceId, effectiveInput, modelTier)
                // Stage 2: a fresh ToolActivationSink attached to this
                // turn's coroutine context. find_tool writes activations
                // to it (via currentCoroutineContext) and the strategy's
                // nodeApplyActivations drains+applies them. When
                // toolProvider is null the sink is still present but the
                // node short-circuits — harmless overhead.
                kotlinx.coroutines.withContext(dev.weft.contracts.ToolActivationSink()) {
                    agent.run(effectiveInput)
                }
            }
            history += HistoryEntry(Role.USER, userText)
            history += HistoryEntry(Role.ASSISTANT, reply)
            syncHistoryToState()
            // Persist both halves of the turn — only on success, matching
            // the in-memory history. A failed turn leaves no trace in
            // conversation_history, same as before.
            conversationStore?.let { cs ->
                val convId = _state.value.conversationId
                cs.append(convId, PersistedRole.USER, userText, agentName)
                cs.append(convId, PersistedRole.ASSISTANT, reply, agentName)
            }
            // Trace gets the redacted version; the live reply returned to
            // the caller is unchanged so the chat UI shows the model's actual
            // words. (User typed their own data; they're fine seeing it back.)
            traceStore.completeTrace(traceId, redactor.redact(reply))
            hooks.onTurnEnd(
                dev.weft.contracts.HookContext.TurnEnd(
                    traceId = traceId,
                    conversationId = _state.value.conversationId,
                    assistantText = reply,
                    modelId = modelPool.standard.id,
                ),
            )
            _state.update {
                it.copy(
                    turnStatus = TurnStatus.Idle,
                    pendingAssistantDelta = "",
                    activeToolCalls = emptyList(),
                )
            }
            reply
        } catch (t: Throwable) {
            traceStore.failTrace(traceId, redactor.redact(t.message ?: t::class.simpleName.orEmpty()))
            hooks.onTurnFailed(
                dev.weft.contracts.HookContext.TurnFailed(
                    traceId = traceId,
                    conversationId = _state.value.conversationId,
                    cause = t,
                ),
            )
            _state.update {
                it.copy(
                    turnStatus = TurnStatus.Failed,
                    lastError = t,
                    pendingAssistantDelta = "",
                    activeToolCalls = emptyList(),
                )
            }
            throw t
        }
        }  // close withContext(TraceContext)
    }

    /**
     * Streaming variant of [send]. Returns a cold [Flow] of [StreamChunk]s
     * — text deltas as the model emits them, tool lifecycle events
     * interleaved, and a terminal [StreamChunk.Done] or [StreamChunk.Failed].
     *
     * Same guarantees as [send] (quota, retry, breaker, trace, redaction,
     * persistence) apply — they fire around the streamed turn, not on each
     * delta. Collect from a single consumer; the underlying `agent.run()`
     * is invoked once per Flow subscription.
     */
    /**
     * Text-only convenience overload — mirrors [send]. Pass attachments
     * via the [sendStreaming] overload taking a list.
     */
    internal fun sendStreaming(userText: String): Flow<StreamChunk> =
        sendStreaming(userText, attachments = emptyList(), modelTier = null)

    /**
     * Streaming variant of [send] with a per-turn
     * [dev.weft.harness.agents.routing.ModelTier] override. Bypasses the
     * router's normal heuristics; passing `null` is identical to the
     * no-tier overload.
     */
    internal fun sendStreaming(
        userText: String,
        modelTier: dev.weft.harness.agents.routing.ModelTier?,
    ): Flow<StreamChunk> = sendStreaming(userText, attachments = emptyList(), modelTier = modelTier)

    /**
     * Streaming variant of [send] with multimodal attachments. See the
     * [send] overload taking attachments for the persistence caveat.
     */
    internal fun sendStreaming(
        userText: String,
        attachments: List<ai.koog.prompt.message.MessagePart.Attachment>,
        modelTier: dev.weft.harness.agents.routing.ModelTier? = null,
    ): Flow<StreamChunk> = channelFlow {
        val quotaState = quotaPolicy.check(usageStore.usdToday())
        _state.update { it.copy(quota = quotaState) }
        if (quotaState is QuotaState.Blocked) {
            _effects.tryEmit(
                AgentEffect.QuotaBlocked(
                    usdToday = quotaState.usdToday,
                    capUsd = quotaState.thresholdUsd,
                ),
            )
            throw QuotaExceededException(quotaState.usdToday, quotaState.thresholdUsd)
        }
        _state.update {
            it.copy(
                turnStatus = TurnStatus.Sending,
                lastError = null,
                pendingAssistantDelta = "",
                activeToolCalls = emptyList(),
            )
        }
        hooks.onUserMessage(
            dev.weft.contracts.HookContext.UserMessage(
                traceId = "",
                conversationId = _state.value.conversationId,
                text = userText,
                hasAttachments = attachments.isNotEmpty(),
            ),
        )
        // Same parent-trace-id propagation logic as `send` — see comment
        // there. channelFlow's block inherits the collector's coroutine
        // context, so an enclosing TraceContext (from a sub-agent's
        // streaming send) flows in here as expected.
        val parentTraceId = kotlin.coroutines.coroutineContext[
            dev.weft.harness.observability.TraceContext,
        ]?.traceId
        val traceId = traceStore.startTrace(_state.value.conversationId, userText, parentTraceId)
        // Memory retrieval runs concurrently across all registered
        // providers (substrate + app-provided). Hits are inlined as
        // "Relevant context" inside the volatile prefix so the LLM sees
        // them without a tool round-trip. Empty registry / no hits =
        // identical behaviour to before this hook existed.
        val memoryHits = memoryRegistry.retrieveAll(userText)
        val effectiveText = composeEffectiveText(
            volatilePrefix = composeVolatilePrefixWithMode(),
            memoryHits = memoryHits,
            userText = userText,
        )
        val effectiveInput = dev.weft.harness.prompt.multimodal.WeftUserInput(effectiveText, attachments)
        val replyBuilder = StringBuilder()
        // Install TraceContext for the duration of this streaming turn
        // so nested traces (sub-agents spawned mid-turn) inherit this
        // trace as their parent and share this conversation id.
        // withContext inside channelFlow is fine — trySend / awaitClose
        // still work via the captured ProducerScope.
        kotlinx.coroutines.withContext(
            dev.weft.harness.observability.TraceContext(traceId, _state.value.conversationId),
        ) {
        hooks.onTurnStart(
            dev.weft.contracts.HookContext.TurnStart(
                traceId = traceId,
                conversationId = _state.value.conversationId,
                userText = userText,
                modelId = modelPool.standard.id,
            ),
        )
        try {
            val reply = withRetry(
                policy = strategy.retry,
                breaker = circuitBreaker,
                onAttemptFailed = { attempt, cause, retryingInMs ->
                    val msg = if (retryingInMs != null) "attempt $attempt failed (${cause.message}), retrying in ${retryingInMs}ms" else "attempt $attempt failed: ${cause.message}"
                    _effects.tryEmit(AgentEffect.ToolFailed(toolName = "llm.retry", message = msg))
                    _effects.tryEmit(AgentEffect.Notify(message = msg))
                },
            ) {
                replyBuilder.clear()
                val agent = buildStreamingAgentForThisTurn(
                    traceId,
                    effectiveInput,
                    this@channelFlow,
                    replyBuilder,
                    modelTier,
                )
                // Same ToolActivationSink injection as the non-streaming
                // path — see WeftAgent.send for the rationale.
                kotlinx.coroutines.withContext(dev.weft.contracts.ToolActivationSink()) {
                    agent.run(effectiveInput)
                }
            }
            history += HistoryEntry(Role.USER, userText)
            history += HistoryEntry(Role.ASSISTANT, reply)
            syncHistoryToState()
            conversationStore?.let { cs ->
                val convId = _state.value.conversationId
                cs.append(convId, PersistedRole.USER, userText, agentName)
                cs.append(convId, PersistedRole.ASSISTANT, reply, agentName)
            }
            traceStore.completeTrace(traceId, redactor.redact(reply))
            hooks.onTurnEnd(
                dev.weft.contracts.HookContext.TurnEnd(
                    traceId = traceId,
                    conversationId = _state.value.conversationId,
                    assistantText = reply,
                    modelId = modelPool.standard.id,
                ),
            )
            _state.update {
                it.copy(
                    turnStatus = TurnStatus.Idle,
                    pendingAssistantDelta = "",
                    activeToolCalls = emptyList(),
                )
            }
            trySend(StreamChunk.Done(reply))
        } catch (t: Throwable) {
            val redactedMsg = redactor.redact(t.message ?: t::class.simpleName.orEmpty())
            traceStore.failTrace(traceId, redactedMsg)
            hooks.onTurnFailed(
                dev.weft.contracts.HookContext.TurnFailed(
                    traceId = traceId,
                    conversationId = _state.value.conversationId,
                    cause = t,
                ),
            )
            _state.update {
                it.copy(
                    turnStatus = TurnStatus.Failed,
                    lastError = t,
                    pendingAssistantDelta = "",
                    activeToolCalls = emptyList(),
                )
            }
            trySend(StreamChunk.Failed(redactedMsg))
            throw t
        }
        }  // close withContext(TraceContext)
        // Producer block ends here; channelFlow closes the channel and the
        // consumer's .collect { } returns. Earlier this had `awaitClose { }`
        // which suspended forever, hanging consumers after the Done chunk —
        // wrong pattern for a self-driving producer (awaitClose is for
        // callback-subscription cleanup, not turn-by-turn work).
    }

    /** Drop the in-memory conversation history. Does NOT touch the persistent store. */
    internal fun resetHistory() {
        history.clear()
        syncHistoryToState()
    }

    /**
     * Hydrate in-memory history from the persistent store. Call once at
     * startup BEFORE the first [send] if you want continuity from the
     * previous app session. Passing `conversationId = null` (the default)
     * resumes the most recent thread; passing an explicit id resumes that
     * specific thread; if neither matches, creates a fresh thread.
     *
     * No-op when [conversationStore] is null.
     */
    internal suspend fun resume(conversationId: String? = null) {
        val store = conversationStore ?: return
        val id = conversationId
            ?: store.mostRecentConversationId()
            ?: store.newConversation()
        history.clear()
        store.loadMessages(id).forEach { msg ->
            val role = if (msg.role == PersistedRole.USER) Role.USER else Role.ASSISTANT
            history += HistoryEntry(role, msg.content)
        }
        _state.update { it.copy(conversationId = id) }
        syncHistoryToState()
    }

    /**
     * Start a fresh thread — equivalent to "New chat". Clears in-memory
     * history; if a [conversationStore] is wired, creates a new
     * conversation row and switches [currentConversationId] to it.
     */
    internal suspend fun newChat() {
        history.clear()
        val newId = conversationStore?.newConversation() ?: Uuid.random().toString()
        _state.update {
            it.copy(
                conversationId = newId,
                history = emptyList(),
                turnStatus = TurnStatus.Idle,
                pendingAssistantDelta = "",
                activeToolCalls = emptyList(),
                lastError = null,
            )
        }
    }

    /**
     * Re-run the most recent user turn. Rolls back the previous
     * user+assistant pair from both in-memory history and the conversation
     * store, then re-sends the same user text through [send]. The visible
     * chat looks the same except the assistant reply is fresh.
     *
     * Returns the new assistant reply, or `null` when there's nothing to
     * regenerate (no USER message in the in-memory history yet).
     *
     * **Failure semantics**: the rollback happens *before* the new send.
     * If the underlying [send] fails (network, quota, breaker open, etc.),
     * the previous reply is gone with no replacement and the exception
     * propagates to the caller. The caller can re-type the prompt and try
     * again. This is intentionally simpler than a save-then-restore dance,
     * which would introduce tricky race handling for negligible benefit.
     */
    internal suspend fun regenerate(): String? {
        val lastUserText = rollBackToLastUser() ?: return null
        syncHistoryToState()
        conversationStore?.deleteLastTurn(_state.value.conversationId)
        return send(lastUserText)
    }

    /**
     * Streaming variant of [regenerate]. Emits an empty Flow (completes
     * immediately with no values) when there's nothing to regenerate;
     * otherwise delegates to [sendStreaming] with the rolled-back user
     * text.
     *
     * Same failure semantics as [regenerate]: the rollback is committed
     * before the new send begins.
     */
    internal fun regenerateStreaming(): Flow<StreamChunk> = flow {
        val lastUserText = rollBackToLastUser() ?: return@flow
        syncHistoryToState()
        conversationStore?.deleteLastTurn(_state.value.conversationId)
        emitAll(sendStreaming(lastUserText))
    }

    /**
     * Find the last USER turn in in-memory [history], capture its text,
     * and drop it (plus any subsequent ASSISTANT reply or other entries)
     * from history. Returns the captured text, or null when there's no
     * USER turn to roll back from.
     *
     * Shared by both [regenerate] and [regenerateStreaming] so they can't
     * drift in their rollback semantics.
     */
    private fun rollBackToLastUser(): String? {
        val lastUserIdx = history.indexOfLast { it.role == Role.USER }
        if (lastUserIdx == -1) return null
        val text = history[lastUserIdx].text
        while (history.size > lastUserIdx) {
            history.removeAt(history.size - 1)
        }
        return text
    }

    /**
     * Round-trip a UI event from an LLM-rendered surface back to the
     * agent (per ADR-007 §6). Composes a synthetic user message so the
     * event is honest in chat history and visible in traces, then runs
     * a normal turn so the agent can react.
     *
     * @param action   the `action` string declared on the tapped widget
     * @param sourceLabel optional human-readable label (e.g., the button text)
     * @param fieldValues snapshot of TextField values on the rendered surface,
     *                    so forms work without the agent having to ask.
     */
    internal suspend fun sendEvent(
        action: String,
        sourceLabel: String? = null,
        fieldValues: Map<String, String> = emptyMap(),
    ): String {
        return send(composeEventMessage(action, sourceLabel, fieldValues))
    }

    /**
     * Compose the synthetic "User tapped X" user-message text for a
     * UI-event round-trip. Shared between the deprecated [sendEvent]
     * and the new [dispatch] handler for [AgentIntent.SendEvent] so
     * both wire up the same message shape.
     */
    private fun composeEventMessage(
        action: String,
        sourceLabel: String?,
        fieldValues: Map<String, String>,
    ): String = buildString {
        append("[UI event] User tapped")
        if (!sourceLabel.isNullOrBlank()) append(" '$sourceLabel'")
        append(" (action=$action) on the rendered screen.")
        if (fieldValues.isNotEmpty()) {
            append("\nField values: ")
            append(fieldValues.entries.joinToString { (k, v) -> "$k=\"$v\"" })
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun buildAgentForThisTurn(
        traceId: String,
        input: dev.weft.harness.prompt.multimodal.WeftUserInput,
        modelTier: dev.weft.harness.agents.routing.ModelTier?,
    ): AIAgent<dev.weft.harness.prompt.multimodal.WeftUserInput, String> {
        val systemMsg = baseSystemPromptSupplier()

        val turns: List<Turn> = history.map {
            when (it.role) {
                Role.USER -> Turn.User(it.text)
                Role.ASSISTANT -> Turn.Assistant(it.text)
            }
        }
        val compacted = compactor.compact(turns)

        val initialPrompt: Prompt = prompt(id = "chat", params = LLMParams(maxTokens = maxOutputTokens)) {
            cacheBinder.cachedSystem(
                this,
                systemMsg,
                strategy.cacheTiers["system"] ?: dev.weft.harness.prompt.cache.CacheTier.STATIC,
            )
            emitCompactedHistory(compacted)
        }

        val llmCallMap = mutableMapOf<String, String>()
        val toolCallMap = mutableMapOf<String, String>()

        // Per-turn model routing — picks from `modelPool` based on input
        // shape (length, attachments, coding hints) and conversation
        // depth. Replaces what used to be a single hardcoded `model`.
        val routedModel = modelRouter.route(
            dev.weft.harness.agents.routing.RoutingContext(
                userText = input.text,
                attachments = input.attachments,
                historyTurns = history.size,
                availableTools = toolRegistry.tools.map { it.descriptor },
                pool = modelPool,
                // User's explicit per-turn tier wins; otherwise the
                // strategy gets a chance to pin; otherwise the router's
                // input-shape heuristics decide.
                tierHint = modelTier ?: strategy.pickTier(input, compacted),
            )
        )

        return AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = initialPrompt,
                model = routedModel,
                maxAgentIterations = strategy.maxIterations(input),
            ),
            // WeftUserInput-aware strategy: emits the live user turn as a
            // single Message.User with mixed text + attachment parts.
            // Falls back to a plain text user message when attachments is
            // empty (matches Koog's built-in singleRunStrategy behavior).
            strategy = dev.weft.harness.agents.multimodal.weftSingleRunStrategy(toolProvider),
            toolRegistry = toolRegistry,
            installFeatures = {
                handleEvents {
                    onLLMCallStarting { ctx ->
                        val id = traceStore.recordLlmStart(traceId, ctx.model.id)
                        llmCallMap[ctx.runId] = id
                    }
                    onLLMCallCompleted { ctx ->
                        val id = llmCallMap[ctx.runId] ?: return@onLLMCallCompleted
                        val meta = ctx.response?.metaInfo
                        // Koog 1.0.0's AnthropicLLMClient surfaces cache token
                        // counts through ResponseMetaInfo.metadata as JSON ints
                        // (cacheCreationInputTokens / cacheReadInputTokens). Other
                        // providers either don't report cache tokens or use
                        // different field names — for those, these reads are null
                        // and the binder fell back to NoOpCacheBinder anyway.
                        val cacheReadTokens = meta?.metadata
                            ?.get("cacheReadInputTokens")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                        val cacheWriteTokens = meta?.metadata
                            ?.get("cacheCreationInputTokens")
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                        traceStore.recordLlmComplete(
                            traceId = traceId,
                            llmCallId = id,
                            inputTokens = meta?.inputTokensCount,
                            outputTokens = meta?.outputTokensCount,
                            totalTokens = meta?.totalTokensCount,
                            cacheReadTokens = cacheReadTokens,
                            cacheWriteTokens = cacheWriteTokens,
                        )
                        usageStore.record(
                            modelId = ctx.model.id,
                            inputTokens = meta?.inputTokensCount ?: 0,
                            outputTokens = meta?.outputTokensCount ?: 0,
                            cacheReadTokens = cacheReadTokens ?: 0,
                            cacheWriteTokens = cacheWriteTokens ?: 0,
                            agentName = agentName,
                        )
                    }
                    onToolCallStarting { ctx ->
                        // Redact BEFORE truncating so partial tokens / partial PII
                        // can't slip through unmasked at the cut boundary.
                        val argsPreview = redactor.redact(ctx.toolArgs.toString()).take(ARGS_PREVIEW_MAX)
                        val id = traceStore.recordToolStart(traceId, ctx.toolName, argsPreview)
                        val key = ctx.toolCallId ?: ctx.eventId
                        toolCallMap[key] = id
                        _state.update {
                            it.copy(
                                turnStatus = TurnStatus.ToolRunning,
                                activeToolCalls = it.activeToolCalls + ActiveToolCall(
                                    id = key,
                                    toolName = ctx.toolName,
                                    argsPreview = argsPreview,
                                ),
                            )
                        }
                        _effects.tryEmit(AgentEffect.ToolStarting(toolName = ctx.toolName, argsPreview = argsPreview))
                    }
                    onToolCallCompleted { ctx ->
                        val key = ctx.toolCallId ?: ctx.eventId
                        val id = toolCallMap[key] ?: return@onToolCallCompleted
                        val resultPreview = ctx.toolResult?.toString()
                            ?.let(redactor::redact)
                            ?.take(ARGS_PREVIEW_MAX)
                        traceStore.recordToolComplete(
                            traceId = traceId,
                            toolCallId = id,
                            resultPreview = resultPreview,
                        )
                        removeActiveToolCall(key)
                        _effects.tryEmit(AgentEffect.ToolCompleted(toolName = ctx.toolName))
                    }
                    onToolCallFailed { ctx ->
                        val key = ctx.toolCallId ?: ctx.eventId
                        val id = toolCallMap[key] ?: return@onToolCallFailed
                        val safeMessage = redactor.redact(ctx.message)
                        traceStore.recordToolFailed(traceId, id, errorMessage = safeMessage)
                        removeActiveToolCall(key)
                        _effects.tryEmit(AgentEffect.ToolFailed(toolName = ctx.toolName, message = safeMessage))
                    }
                }
            },
        )
    }

    /**
     * Streaming counterpart to [buildAgentForThisTurn]. Builds an AIAgent
     * with a custom graph strategy that calls `requestLLMStreaming` and
     * forwards text deltas + tool lifecycle events to the supplied
     * [producerScope] (the receiver of the public `sendStreaming` Flow).
     *
     * The same event handler that backs [buildAgentForThisTurn] is
     * installed — tool tracing, redaction, and `_events` emission all
     * work identically. Token accounting fires from the new
     * `onLLMStreamingComplete` callback in the strategy (the standard
     * `onLLMCallCompleted` doesn't fire in streaming mode).
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun buildStreamingAgentForThisTurn(
        traceId: String,
        input: dev.weft.harness.prompt.multimodal.WeftUserInput,
        producerScope: ProducerScope<StreamChunk>,
        replyBuilder: StringBuilder,
        modelTier: dev.weft.harness.agents.routing.ModelTier?,
    ): AIAgent<dev.weft.harness.prompt.multimodal.WeftUserInput, String> {
        val systemMsg = baseSystemPromptSupplier()
        val turns: List<Turn> = history.map {
            when (it.role) {
                Role.USER -> Turn.User(it.text)
                Role.ASSISTANT -> Turn.Assistant(it.text)
            }
        }
        val compacted = compactor.compact(turns)

        val initialPrompt: Prompt = prompt(id = "chat-stream", params = LLMParams(maxTokens = maxOutputTokens)) {
            cacheBinder.cachedSystem(
                this,
                systemMsg,
                strategy.cacheTiers["system"] ?: dev.weft.harness.prompt.cache.CacheTier.STATIC,
            )
            emitCompactedHistory(compacted)
        }

        // Per-turn model routing — same call shape as the non-streaming
        // path. The chosen model is also captured into local val so the
        // trace-store recordLlmStart / usageStore.record calls below
        // know which model is actually in use this turn.
        val routedModel = modelRouter.route(
            dev.weft.harness.agents.routing.RoutingContext(
                userText = input.text,
                attachments = input.attachments,
                historyTurns = history.size,
                availableTools = toolRegistry.tools.map { it.descriptor },
                pool = modelPool,
                // User's explicit per-turn tier wins; otherwise the
                // strategy gets a chance to pin; otherwise the router's
                // input-shape heuristics decide.
                tierHint = modelTier ?: strategy.pickTier(input, compacted),
            )
        )

        val toolCallMap = mutableMapOf<String, String>()
        // Track per-streaming-call LLM trace ids so completion can wire up.
        var pendingLlmId: String? = null

        // Renamed from `strategy` to avoid shadowing the WeftStrategy
        // field. This is the Koog AIAgent's run-strategy (turn-shape
        // semantics), not the weft loop policy.
        val runStrategy = streamingSingleRunStrategy(
            onTextDelta = { delta ->
                replyBuilder.append(delta)
                _state.update {
                    it.copy(
                        turnStatus = TurnStatus.Streaming,
                        pendingAssistantDelta = it.pendingAssistantDelta + delta,
                    )
                }
                producerScope.trySend(StreamChunk.TextDelta(delta))
            },
            onLlmStreamingStart = {
                pendingLlmId = traceStore.recordLlmStart(traceId, routedModel.id)
            },
            onLlmStreamingComplete = { meta ->
                pendingLlmId?.let { id ->
                    traceStore.recordLlmComplete(
                        traceId = traceId,
                        llmCallId = id,
                        inputTokens = meta.inputTokensCount,
                        outputTokens = meta.outputTokensCount,
                        totalTokens = meta.totalTokensCount,
                    )
                    usageStore.record(
                        modelId = routedModel.id,
                        inputTokens = meta.inputTokensCount ?: 0,
                        outputTokens = meta.outputTokensCount ?: 0,
                        agentName = agentName,
                    )
                }
                pendingLlmId = null
            },
        )

        return AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = initialPrompt,
                model = routedModel,
                maxAgentIterations = strategy.maxIterations(input),
            ),
            strategy = runStrategy,
            toolRegistry = toolRegistry,
            installFeatures = {
                handleEvents {
                    // Tool tracing identical to the non-streaming path —
                    // nodeExecuteTool fires these regardless of how the
                    // upstream Message.Tool.Call was produced.
                    onToolCallStarting { ctx ->
                        val argsPreview = redactor.redact(ctx.toolArgs.toString()).take(ARGS_PREVIEW_MAX)
                        val id = traceStore.recordToolStart(traceId, ctx.toolName, argsPreview)
                        val key = ctx.toolCallId ?: ctx.eventId
                        toolCallMap[key] = id
                        _state.update {
                            it.copy(
                                turnStatus = TurnStatus.ToolRunning,
                                activeToolCalls = it.activeToolCalls + ActiveToolCall(
                                    id = key,
                                    toolName = ctx.toolName,
                                    argsPreview = argsPreview,
                                ),
                            )
                        }
                        _effects.tryEmit(AgentEffect.ToolStarting(toolName = ctx.toolName, argsPreview = argsPreview))
                        producerScope.trySend(StreamChunk.ToolStarting(toolName = ctx.toolName, argsPreview = argsPreview))
                    }
                    onToolCallCompleted { ctx ->
                        val key = ctx.toolCallId ?: ctx.eventId
                        val id = toolCallMap[key] ?: return@onToolCallCompleted
                        val resultPreview = ctx.toolResult?.toString()
                            ?.let(redactor::redact)
                            ?.take(ARGS_PREVIEW_MAX)
                        traceStore.recordToolComplete(
                            traceId = traceId,
                            toolCallId = id,
                            resultPreview = resultPreview,
                        )
                        removeActiveToolCall(key)
                        _effects.tryEmit(AgentEffect.ToolCompleted(toolName = ctx.toolName))
                        producerScope.trySend(StreamChunk.ToolCompleted(toolName = ctx.toolName))
                    }
                    onToolCallFailed { ctx ->
                        val key = ctx.toolCallId ?: ctx.eventId
                        val id = toolCallMap[key] ?: return@onToolCallFailed
                        val safeMessage = redactor.redact(ctx.message)
                        traceStore.recordToolFailed(traceId, id, errorMessage = safeMessage)
                        removeActiveToolCall(key)
                        _effects.tryEmit(AgentEffect.ToolFailed(toolName = ctx.toolName, message = safeMessage))
                        producerScope.trySend(StreamChunk.ToolFailed(toolName = ctx.toolName, message = safeMessage))
                    }
                }
            },
        )
    }

    /**
     * Compose the volatile prefix for the upcoming turn. When the
     * session is in [dev.weft.contracts.ApprovalMode.Plan] this prepends
     * a directive so the model knows it can't run writes and should
     * propose a plan via the substrate's `exit_plan_mode` tool. Other
     * modes pass through to the existing supplier untouched.
     */
    private fun composeVolatilePrefixWithMode(): String {
        val basePrefix = volatilePrefixSupplier()
        return when (approvalMode.current()) {
            dev.weft.contracts.ApprovalMode.Plan -> buildString {
                append(PLAN_MODE_DIRECTIVE)
                if (basePrefix.isNotBlank()) {
                    append("\n\n")
                    append(basePrefix)
                }
            }
            else -> basePrefix
        }
    }

    private data class HistoryEntry(val role: Role, val text: String)
    private enum class Role { USER, ASSISTANT }

    companion object {
        private const val MAX_ITERATIONS_DEFAULT = 10
        private const val EVENT_BUFFER_CAPACITY = 64

        /**
         * Default per-LLM-call `max_tokens` budget. 8192 is the practical
         * ceiling for Claude Sonnet 4.x without extended-thinking and well
         * above what any `ui_render` tool call legitimately needs. Bigger
         * than necessary is fine — Anthropic only bills for tokens
         * actually generated.
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 8192
        // 8KB is enough for typical ui_render trees + form payloads.
        // Bigger trees still get truncated; the user can drill into the
        // trace in the viewer and see the args.
        private const val ARGS_PREVIEW_MAX = 8000

        /**
         * Directive prepended to the volatile prefix when the session is
         * in [dev.weft.contracts.ApprovalMode.Plan]. Loud + specific by
         * design — models otherwise tend to "helpfully" proceed with the
         * task and emit write tool calls anyway.
         */
        private val PLAN_MODE_DIRECTIVE: String = """
            |[Plan mode is active.]
            |You may read, search, and ask clarifying questions, but you
            |MUST NOT call any tool that writes, sends, schedules, or
            |otherwise mutates state. When you are ready, call the
            |`exit_plan_mode` tool with a structured plan (title + steps
            |+ optional open questions + assumptions). The user will
            |approve, request refinements, or cancel — only after
            |approval will writes be re-enabled. Do not narrate the plan
            |before calling the tool; the tool's args ARE the plan.
        """.trimMargin()
    }
}

