# Architecture diagrams

Visual map of how Weft fits together. Renders natively in GitHub
markdown and in mkdocs (with the mermaid plugin). Each diagram is
followed by a paragraph explaining what to take away from it.

For text-only narratives, pair this with:
- [`architecture-vision.md`](architecture-vision.md) — the SDK/host split rule
- [`02-architecture.md`](02-architecture.md) — module boundaries
- [`architecture/`](architecture/) — per-feature ADRs

---

## 1. The SDK / app boundary

The single rule everything else flows from: **the SDK provides
everything; the app just registers.**

```mermaid
flowchart LR
    subgraph SDK["Weft substrate — generic, reusable"]
        direction TB
        Harness["Harness<br/>(agents, prompt, memory,<br/>conversation, observability,<br/>cost, reliability, behavior)"]
        Tools["Tools<br/>(~50 built-ins<br/>+ gates + ToolProvider)"]
        OsBridge["OS bridge<br/>(32 capabilities,<br/>Android implementations)"]
        UI["UI primitives<br/>(WeftComponent, registry,<br/>tree renderer)"]
        MCP["MCP client<br/>+ OAuth"]
    end

    subgraph APP["Host app — Undercurrent / etc"]
        direction TB
        Identity["Identity<br/>(preamble, persona)"]
        Screens["Screens<br/>(ChatScreen, Settings, …)"]
        Wiring["DI wiring<br/>(WeftRuntime.create)"]
        Extras["Extra registrations<br/>(extraToolsFactory,<br/>extraComponentsFactory,<br/>DataSources,<br/>MCP servers,<br/>AgentDeclarations)"]
    end

    APP -->|"registers into"| SDK
```

**Takeaway.** When you're deciding where new logic belongs, ask
"would another Weft host need this same behavior?" If yes, it goes
on the left. If it's about Undercurrent's identity / branding /
screens, it stays on the right. The arrow only goes one way — the
SDK never imports anything from a host.

---

## 2. Module dependency graph

The compile-time DAG. Each node is a Gradle module; an arrow `A → B`
means `A` depends on `B`.

```mermaid
flowchart TB
    contracts[":contracts<br/>pure interfaces"]
    tools[":tools<br/>WeftTool + gates"]
    security[":security<br/>NetworkPolicy, Redactor"]
    osbridge[":os-bridge<br/>Android impls"]
    mcp[":mcp"]
    oauth[":oauth"]

    subgraph harness["harness"]
        direction LR
        h_prompt[":harness:prompt"]
        h_memory[":harness:memory"]
        h_conv[":harness:conversation"]
        h_obs[":harness:observability"]
        h_cost[":harness:cost"]
        h_rel[":harness:reliability"]
        h_beh[":harness:behavior"]
        h_skills[":harness:skills"]
        h_agents[":harness:agents"]
        h_testing[":harness:testing"]
    end

    runtime[":runtime<br/>composition root"]
    compose[":compose"]
    a_defaults[":compose-defaults"]
    a_devtools[":devtools"]

    tools --> contracts
    security --> contracts
    osbridge --> contracts
    mcp --> contracts
    oauth --> contracts

    h_prompt --> tools
    h_prompt --> contracts
    h_memory --> tools
    h_conv --> contracts
    h_obs --> contracts
    h_cost --> contracts
    h_rel --> contracts
    h_beh --> contracts
    h_skills --> contracts
    h_agents --> h_prompt
    h_agents --> h_memory
    h_agents --> h_conv
    h_agents --> h_obs
    h_agents --> h_cost
    h_agents --> h_rel
    h_agents --> h_beh
    h_testing --> tools

    runtime --> osbridge
    runtime --> security
    runtime --> h_agents
    runtime --> h_skills
    runtime --> mcp
    runtime --> oauth
    compose --> contracts
    compose --> tools
    a_defaults --> compose
    a_devtools --> runtime
```

**Takeaway.** `:contracts` sits at the bottom — pure Kotlin
interfaces, no Android, no Koog. Everything else builds up.
`:runtime` is the composition root that wires the real
implementations. `:compose*` is the UI layer apps depend on
when they want the stock Compose surface; apps with custom UI
depend only on `:compose` (no Material).

---

## 3. The runtime — what `WeftRuntime.create` builds

```mermaid
flowchart TB
    create["WeftRuntime.create(...)"]

    subgraph runtime["WeftRuntime instance"]
        direction TB
        os["AndroidOsCapabilities<br/>(32 sub-impls)"]
        ui["ComposeUiBridge / custom"]
        net["whitelistingHttpClient<br/>(Ktor + NetworkPolicy)"]
        db["WeftDatabase (SQLDelight)<br/>memory · conversation · trace ·<br/>script-storage · scheduled-notif"]
        prebuilt["prebuiltTools<br/>(~50 substrate built-ins<br/>+ extraToolsFactory output)"]
        provider["toolProvider<br/>(EagerToolProvider default<br/>or host-supplied composite)"]
        agents["agentDeclarations<br/>(default + host-registered)"]
        registry["componentMetadata<br/>+ ContextRegistry<br/>+ DataSourceRegistry<br/>+ MemoryRegistry"]
    end

    subgraph perBuild["per buildAgent() call"]
        direction TB
        agent["WeftAgent instance<br/>(toolRegistry, strategy,<br/>baseSystemPromptSupplier,<br/>volatilePrefixSupplier,<br/>cacheBinder, modelRouter)"]
    end

    create --> runtime
    runtime --> perBuild
    runtime -.->|"discover (background)"| mcp_discovery["mcpToolsReady<br/>(awaited on first send)"]
    mcp_discovery -.-> perBuild
```

**Takeaway.** `WeftRuntime` is the *expensive* object — built once
at app startup, holds every persistent store and the full tool
catalog. `WeftAgent` is the *cheap-ish* object — built per provider
× per agent declaration via `runtime.buildAgent(agentName,
provider)`. The agent captures its system prompt, tool registry,
and strategy at build time; per-turn execution is closure-driven
from there.

---

## 4. Turn lifecycle — what happens when the user types a message

The non-streaming path. Streaming is structurally identical with
delta forwarding instead of buffered assembly.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant UI as ChatScreen (host)
    participant WA as WeftAgent
    participant H as Hooks · TraceStore · UsageStore
    participant K as Koog AIAgent<br/>(weftSingleRunStrategy)
    participant LLM as LLM Provider
    participant T as Tool (e.g. calendar_read)

    U->>UI: types message
    UI->>WA: send(text)
    WA->>WA: append to history (in-mem)
    WA->>H: fire HookContext.UserMessage<br/>+ start trace + check quota
    WA->>WA: compact history (compactor)
    WA->>WA: route model (modelRouter.route)
    WA->>WA: build Koog AIAgent<br/>(prompt + tools + registry)
    WA->>K: withContext(ToolActivationSink) { agent.run(input) }

    loop until LLM emits text or iter cap
        K->>LLM: chat() with prompt + tool list
        LLM-->>K: Message.Assistant<br/>(text or tool_use)

        alt Message has text
            K-->>WA: return text
        else Message has tool_use
            K->>T: executeTool(call)
            Note over T: WeftTool.execute runs:<br/>approval gate → permission gate<br/>→ destructive gate → hooks<br/>→ executeWeft
            T-->>K: ReceivedToolResult
            K->>K: nodeApplyActivations<br/>(Stage 2 — drains sink<br/>+ mutates llm.tools<br/>+ toolRegistry.add)
            K->>LLM: send tool result back
        end
    end

    WA->>H: fire HookContext.TurnEnd<br/>+ record cost · close trace
    WA->>WA: persist user + assistant turns<br/>(conversationStore)
    WA-->>UI: assembled response
```

**Takeaway.** A "turn" can take many LLM round-trips internally — the
agent loops nodeCallLLM → nodeExecuteTools → (find_tool activation?)
→ nodeSendToolResult until either the LLM emits free text (done) or
hits `maxAgentIterations` (capped by strategy). The gates around
`executeWeft` mean every tool call goes through approval / permission /
destructive / hook checks before the side-effect ever fires.

---

## 5. Stage 2 — `find_tool` single-turn discovery

The interesting bit of the tool-provider redesign. The LLM searches
the catalog, the activation node mutates the agent state mid-loop,
the LLM uses the surfaced tool in the *same* user-visible turn.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant WA as WeftAgent
    participant Sink as ToolActivationSink<br/>(CoroutineContext element)
    participant LLM as LLM
    participant FT as find_tool
    participant Node as nodeApplyActivations<br/>(strategy graph)
    participant TP as ToolProvider
    participant LLMCtx as llm.tools +<br/>toolRegistry
    participant Cam as camera_capture

    U->>WA: "scan this receipt"
    WA->>Sink: withContext(ToolActivationSink())

    Note over LLM: Initial tools list:<br/>memory_*, find_tool,<br/>system_user_context, ...<br/>(camera_capture NOT visible)

    WA->>LLM: chat()
    LLM-->>WA: tool_use(find_tool, "scan receipt")

    WA->>FT: executeWeft(query="scan receipt")
    FT->>TP: search available
    TP-->>FT: [camera_capture, vision_ocr]
    FT->>Sink: record(["camera_capture", "vision_ocr"])
    FT-->>WA: Result(tools=[...], activated=2)

    WA->>Node: results arrive (post-nodeExecuteTools)
    Node->>Sink: drain()
    Sink-->>Node: ["camera_capture", "vision_ocr"]
    Node->>TP: resolve("camera_capture")<br/>resolve("vision_ocr")
    TP-->>Node: ResolvedWeftTool, ResolvedWeftTool

    Node->>LLMCtx: llm.writeSession {<br/>tools = tools + new descriptors<br/>}
    Node->>LLMCtx: llm.toolRegistry.add(camera_capture)<br/>llm.toolRegistry.add(vision_ocr)

    Note over LLM: Next iteration sees<br/>extended tools list

    WA->>LLM: chat() (sends find_tool result + new tools)
    LLM-->>WA: tool_use(camera_capture)
    WA->>Cam: execute (gates fire, photo captured)
    Cam-->>WA: FileRef
    WA->>LLM: chat() (sends camera result)
    LLM-->>WA: text "I see your receipt for $42.30..."
    WA-->>U: final response
```

**Takeaway.** The sink is the side channel between `find_tool`
(running inside the tool-dispatch coroutine) and the activation
node (sitting between `nodeExecuteTools` and `nodeSendToolResult`
in the strategy graph). Both share the same coroutine context, so
the sink passes between them without explicit plumbing. The two
mutations (`llm.tools = ...` and `toolRegistry.add(...)`) cover the
two surfaces the LLM sees: what's advertised in the prompt, and
what's dispatchable when called.

---

## 6. Tool execution — the gate stack

Every `WeftTool.execute(args)` walks this stack before
`executeWeft` runs. Each gate can short-circuit.

```mermaid
flowchart TB
    start["agent.run() → Koog dispatches tool"]
    --> execute["WeftTool.execute(args)"]
    --> approval{"runApprovalGate<br/>(ApprovalMode)"}

    approval -->|"ReadOnly + tool is Write/Destructive"| denyA["throw ApprovalDeniedException<br/>LLM sees tool failed"]
    approval -->|"ConfirmAllWrites + tool is Write"| confirmW["UiBridge.confirmDestructive"]
    confirmW -->|"user cancels"| cancelA["throw UserCancelledException"]
    confirmW -->|"user confirms"| permission
    approval -->|"Plan + tool not Read/planAware"| denyP["throw ApprovalDeniedException"]
    approval -->|"Default / Yolo / passes"| permission

    permission{"runPermissionGate<br/>(requiredPermissions)"}
    --> permCheck["check Android perms"]
    permCheck -->|"missing"| request["os.permissions.request(each)"]
    permCheck -->|"all granted"| destructive
    request -->|"still missing"| denyPerm["throw PermissionDeniedException"]
    request -->|"all granted"| destructive

    destructive{"runDestructiveGate<br/>(destructive = true?)"}
    destructive -->|"Yolo mode"| hooks
    destructive -->|"prompts UiBridge"| confirmD["UiBridge.confirmDestructive"]
    confirmD -->|"user cancels"| cancelD["throw UserCancelledException"]
    confirmD -->|"user confirms"| hooks
    destructive -->|"non-destructive tool"| hooks

    hooks{"runPreToolHooks<br/>(HookRegistry.onToolStart)"}
    hooks -->|"HookDecision.Deny"| hookDeny["throw HookDeniedException"]
    hooks -->|"HookDecision.Continue"| executeWeft

    executeWeft["executeWeft(args)"] --> result["return TResult to Koog"]
```

**Takeaway.** Every gate is opt-out: a tool that declares no
permissions skips the permission gate, a non-destructive tool skips
the destructive gate, an empty hook registry skips the hook gate.
The agent's current `ApprovalMode` (`Default`, `ReadOnly`, `Plan`,
`ConfirmAllWrites`, `Yolo`) drives the approval gate without
per-tool wiring — flipping the mode mid-session via `ApprovalModeHolder`
changes behavior without rebuilding the agent.

---

## 7. OS capabilities — domain map

The 32 sub-interfaces grouped by what they touch.

```mermaid
flowchart TB
    subgraph storage["Storage & files"]
        Files["Files"]
        KeyVault["KeyVault"]
        Pdf["Pdf"]
        ImageOps["ImageOps"]
    end

    subgraph human["Human interaction"]
        Notifications["Notifications"]
        Biometrics["Biometrics"]
        Haptics["Haptics"]
        Speech["Speech<br/>(TTS + STT)"]
        Audio["Audio (record)"]
        Camera["Camera"]
        MediaPicker["MediaPicker<br/>(Photo Picker)"]
    end

    subgraph sensors["Sensors & state"]
        Location["Location<br/>(fix + geocode)"]
        Sensors_["Sensors<br/>(steps + light)"]
        SystemInfo["SystemInfo<br/>(battery + network<br/>+ device + display)"]
        Wifi["Wifi"]
        Telephony["Telephony"]
    end

    subgraph content["Content access"]
        Calendar["Calendar"]
        Contacts["Contacts"]
        MediaLibrary["MediaLibrary<br/>(MediaStore queries)"]
        Apps["Apps<br/>(installed + launchable)"]
        Bluetooth["Bluetooth<br/>(paired devices)"]
        Clipboard["Clipboard"]
    end

    subgraph ml["On-device ML"]
        Vision["Vision<br/>(OCR + barcode)"]
        Translation["Translation<br/>(text + language ID)"]
    end

    subgraph control["Device control"]
        Volume["Volume<br/>(per-stream)"]
        Power["Power<br/>(wake-lock + brightness)"]
        SystemSettings["SystemSettings<br/>(deep-link panels)"]
        AppShortcuts["AppShortcuts<br/>(dynamic launcher)"]
    end

    subgraph integration["Integration"]
        Intents["Intents<br/>(launchApp / openUrl / maps / alarm)"]
        Sharing["Sharing<br/>(text / url / file)"]
        Permissions["Permissions<br/>(runtime gate)"]
        UserContext["UserContext<br/>(snapshot for prompt)"]
    end
```

**Takeaway.** "Sensitive" capabilities (contacts, calendar, media,
location, microphone, camera) all flow through runtime-prompted
permissions. Integration capabilities (Intents, Sharing) hand off
to other apps and need no permission. ML capabilities run on-device
via ML Kit (~30MB model per language pair for translation; OCR /
barcode / language-ID models < 5MB total). Device-control
capabilities prefer per-window / Intent paths so the substrate
doesn't need WRITE_SETTINGS or similar broad permissions.

For Play-Store-shaped concerns about each of these, see
[`PLAY-POLICY.md`](PLAY-POLICY.md).

---

## 8. The lazy tool catalog (Stage 2) — what `find_tool` operates on

How `ToolProvider`, `ToolMetadata`, and the catalog assembly fit
together.

```mermaid
flowchart LR
    subgraph providers["compositeToolProvider(...)"]
        direction TB
        sub["SubstrateToolProvider<br/>(future — explicit always-on<br/>tagging of memory_*,<br/>system_user_context, ui_ask, etc.)"]
        app["AppToolProvider<br/>(host-supplied, lazy)"]
        mcp_p["McpToolProvider<br/>(future — MCP tools<br/>on-demand)"]
    end

    providers --> available["provider.available:<br/>List&lt;ToolMetadata&gt;<br/>(name, description,<br/>category, alwaysOn)"]

    available --> promptCat["System prompt catalog<br/>(only alwaysOn entries)"]
    available --> findToolSearch["find_tool ranks +<br/>returns matches"]

    findToolSearch --> sink["ToolActivationSink.record(names)"]
    sink --> node["nodeApplyActivations.drain()"]
    node --> resolve["provider.resolve(name)"]
    resolve --> resolved["ResolvedWeftTool"]
    resolved --> mutate["llm.tools += descriptor<br/>+ toolRegistry.add(tool)"]
```

**Takeaway.** `available` is the cheap, side-effect-free index —
read by both the system-prompt assembler (filtered to `alwaysOn`)
and `find_tool` (full set, ranked by query). `resolve` is the
materialization point, called only when something actually
activates. The default `EagerToolProvider` tags everything
`alwaysOn = true`, which means the catalog assembly behaves
identically to pre-Stage-2 for hosts that don't opt in. The win
materializes when the host passes a `compositeToolProvider` with
some `alwaysOn = false` items — typically MCP tools or app-domain
tools the LLM might never need this session.

---

## 9. Multi-agent — `@writer hello`

How the agent registry, declaration, and `delegate_to_agent` tool
interact.

```mermaid
flowchart TB
    create["WeftRuntime.create(<br/>agents = listOf(<br/>  AgentDeclaration('writer', ...),<br/>  AgentDeclaration('researcher', ...)<br/>))"]
    --> reg["agentDeclarations: Map<br/>{default, writer, researcher}"]

    userInput["user types '@writer draft a haiku'"]
    --> parser["AgentMentionParser.parse"]
    --> selectAgent["agentName = 'writer'<br/>cleanText = 'draft a haiku'"]
    selectAgent --> build["runtime.buildAgent(agentName='writer', provider)"]

    build --> filter["filter resolvedTools<br/>by declaration.allowedTools"]
    filter --> delegate["construct DelegateToAgentTool<br/>(routes to other declarations)"]
    delegate --> prompt["systemPromptFor(allTools)<br/>(Stage 1 — agent's catalog only)"]
    prompt --> fragment["append declaration.systemFragment<br/>as '## Role' section"]
    fragment --> agent["WeftAgent<br/>(agentName='writer',<br/>limited toolRegistry,<br/>role-scoped prompt)"]

    agent --> turn["agent.send(cleanText)"]

    turn -.->|"if writer calls delegate_to_agent"| build2["runtime.buildAgent('researcher', ...)<br/>(depth-capped via DelegationContext)"]
```

**Takeaway.** Multi-agent is *not* multiple processes — each
addressable agent is a separate `WeftAgent` instance constructed
from the same `WeftRuntime`. They share persistence (memory, traces,
conversations), differ in tool catalog + system fragment + strategy.
`delegate_to_agent` is the substrate-supplied handoff tool; it's
only registered when the runtime has more than the default agent.
Depth is bounded via a `DelegationContext` coroutine-context element
that increments on each delegate call.

---

## 10. Per-agent prompt scoping (Stage 1) — the token-cost win

Why a writer agent with two allowed tools now pays for two
descriptions, not fifty.

```mermaid
flowchart LR
    subgraph before["Before Stage 1"]
        before_writer["Writer agent<br/>(allowedTools = [memory_*])"]
        before_prompt["System prompt<br/>(~3.5KB catalog<br/>describing all 50 tools)"]
        before_writer --> before_prompt
    end

    subgraph after["After Stage 1"]
        after_writer["Writer agent<br/>(allowedTools = [memory_*])"]
        after_prompt["System prompt<br/>(~0.2KB catalog<br/>memory_* only)"]
        after_writer --> after_prompt
    end

    waste["~3.3KB / turn wasted<br/>(~700 input tokens)"]
    before_prompt --> waste

    saved["~3.3KB / turn saved<br/>(scales by agent count)"]
    after_prompt --> saved
```

**Takeaway.** Stage 1 is a 50-line change in
`buildAgentForDeclaration` that branches on `declaration.allowedTools`:
empty allowlist reuses the cached full-catalog prompt (default
agents see zero change); non-empty rebuilds against the filtered
list. Per-agent prompts don't share Anthropic cache prefix with the
default agent — for multi-agent hosts the catalog savings dominate
the cache loss.

---

## What's NOT diagrammed

Things either too detailed for a diagram, too in-flux, or both:

- The Koog graph DSL internals — see
  [`harness/agents/.../WeftStrategies.kt`](../harness/agents/src/main/kotlin/dev/weft/harness/agents/multimodal/WeftStrategies.kt)
  and the streaming variant alongside it.
- The full prompt-cache layering (system / tool catalog / older
  history / volatile) — see `CacheBinder` and
  [`07-harness.md`](07-harness.md).
- The exact SQLDelight schema — generated, owned by the migration
  files under `android/src/main/sqldelight/`.
- The MCP on-demand migration (the missing Stage 2 piece) — design
  documented but not yet implemented.

---

## Updating these diagrams

Mermaid renders in:
- GitHub markdown (web UI + many IDE plugins)
- mkdocs with `mkdocs-mermaid2-plugin` (already wired in
  [`mkdocs.yml`](../mkdocs.yml))
- Most preview tools (IntelliJ, VS Code with extensions)

When the architecture shifts, edit the diagram block in-place. Keep
the "Takeaway" paragraph paired with the diagram — the words are
what reviewers will actually read; the diagrams are what jogs the
spatial memory.
