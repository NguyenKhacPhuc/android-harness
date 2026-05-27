package dev.weft.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.TypeToken
import dev.weft.contracts.ApprovalMode
import dev.weft.contracts.ApprovalModeHolder
import dev.weft.contracts.HookContext
import dev.weft.contracts.HookDecision
import dev.weft.contracts.HookDeniedException
import dev.weft.contracts.HookRegistry
import dev.weft.contracts.InMemoryPlanStore
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.Permission
import dev.weft.contracts.PermissionState
import dev.weft.contracts.PlanStore
import dev.weft.contracts.ScriptStorage
import dev.weft.contracts.ToolRisk
import dev.weft.contracts.UiBridge

/**
 * Base class for every substrate tool. Wraps Koog's [Tool] with:
 *
 *   - **Permission gate** — refuses to execute if any [requiredPermissions]
 *     isn't currently GRANTED on the platform. The LLM sees the resulting
 *     error and typically calls ui_ask / a separate permission script to recover.
 *   - **Destructive gate** — for [destructive] tools, runs
 *     [UiBridge.confirmDestructive] before execution. User cancellation
 *     raises an exception that surfaces to the LLM as a tool error.
 *   - **Weft context** — each tool has access to [OsCapabilities],
 *     [UiBridge], and per-tool [ScriptStorage] without weaving them through
 *     Koog's tool signature (which only carries Args).
 *
 * Subclasses implement [executeWeft]. Don't override [execute] —
 * the gates won't run.
 *
 * ### Authoring rules — read before writing a new tool
 *
 * Tool selection is a soft attention process: the LLM scans the catalog
 * looking for the best match for the user's request. Names + descriptions
 * are *load-bearing*, not cosmetic. See `docs/writing-a-custom-tool.md`
 * for the full guide; the headline rules:
 *
 *   - **Name: `<verb>_<noun>`, ≤3 words, lowercase_snake_case.**
 *     `open_map`, `send_email`, `set_theme_palette`. Compound names with
 *     prepositions (`show_location_on_map`) get skipped by the model.
 *   - **Description: lead with the action.** "Open the map app pinned
 *     at…" beats "This tool is used when…". Include 2–4 user-phrasing
 *     examples so the model knows when to fire.
 *   - **Cap descriptions at ~3 sentences.** Longer descriptions are a
 *     known cause of tool-skip behavior.
 *   - **Disambiguate from neighbors.** When two tools sound similar,
 *     add a sentence like "NOT for X — use `other_tool` for X."
 */
public abstract class WeftTool<TArgs, TResult>(
    public val ctx: WeftContext,
    argsType: TypeToken,
    resultType: TypeToken,
    descriptor: ToolDescriptor,
    public val destructive: Boolean = false,
    public val sideEffecting: Boolean = false,
    public val requiredPermissions: Set<Permission> = emptySet(),
    /**
     * Coarse risk classification. Defaults derive from [destructive] /
     * [sideEffecting] so existing tools classify correctly without
     * editing:
     *   - [destructive] = true → [ToolRisk.Destructive]
     *   - [sideEffecting] = true → [ToolRisk.Write]
     *   - else → [ToolRisk.Read]
     *
     * Override explicitly when the defaults mis-classify — e.g. a
     * sideEffecting `log_event` that's safe to run in [ApprovalMode.ReadOnly]
     * should declare `risk = ToolRisk.Read`.
     */
    public val risk: ToolRisk = when {
        destructive -> ToolRisk.Destructive
        sideEffecting -> ToolRisk.Write
        else -> ToolRisk.Read
    },
    /**
     * Whether this tool is part of the plan-mode workflow. In
     * [ApprovalMode.Plan], only [ToolRisk.Read] tools and tools with
     * `planAware = true` may execute — everything else is blocked
     * until the user approves the plan and the mode flips back to
     * [ApprovalMode.Default].
     *
     * Substrate built-ins set this for `exit_plan_mode` and `ui_ask`.
     * Apps that ship their own plan-mode helpers (e.g. a custom
     * `propose_design`) set it on those tools too.
     */
    public val planAware: Boolean = false,
) : Tool<TArgs, TResult>(
    argsType = argsType,
    resultType = resultType,
    descriptor = descriptor,
) {

    /** Per-tool namespaced storage. Created lazily on first access. */
    public val storage: ScriptStorage by lazy { ctx.storageFactory(name) }

    /** The OS capability umbrella. Tools call into this for platform behavior. */
    public val os: OsCapabilities get() = ctx.os

    /** UI host. Use [UiBridge.askUser], [UiBridge.confirmDestructive], [UiBridge.emit]. */
    public val ui: UiBridge get() = ctx.ui

    public abstract suspend fun executeWeft(args: TArgs): TResult

    final override suspend fun execute(args: TArgs): TResult {
        runApprovalGate()
        runPermissionGate()
        runDestructiveGate()
        runPreToolHooks(args)
        return executeWeft(args)
    }

    /**
     * Enforce the current [ApprovalMode]. [ApprovalMode.ReadOnly] rejects
     * [ToolRisk.Write] / [ToolRisk.Destructive] tools; [ApprovalMode.ConfirmAllWrites]
     * routes Write tools through [UiBridge.confirmDestructive] just like
     * Destructive tools normally would. [ApprovalMode.Yolo] is honored
     * downstream by [runDestructiveGate]; this gate is purely about
     * blocking and confirming additional risk classes.
     */
    private suspend fun runApprovalGate() {
        when (ctx.approvalMode.current()) {
            ApprovalMode.ReadOnly -> {
                if (risk != ToolRisk.Read) {
                    throw ApprovalDeniedException(
                        toolName = name,
                        risk = risk,
                        mode = ApprovalMode.ReadOnly,
                    )
                }
            }
            ApprovalMode.ConfirmAllWrites -> {
                if (risk == ToolRisk.Write) {
                    val confirmed = ui.confirmDestructive(
                        action = "Confirm $name",
                        body = "The agent wants to run $name which will make a change.",
                    )
                    if (!confirmed) throw UserCancelledException(name)
                }
            }
            ApprovalMode.Plan -> {
                // Plan mode: read-only by default, with a carve-out for
                // tools that ARE the plan workflow (exit_plan_mode, ui_ask).
                // Once the user approves and the host flips the holder
                // back to Default, normal gates resume.
                if (risk != ToolRisk.Read && !planAware) {
                    throw ApprovalDeniedException(
                        toolName = name,
                        risk = risk,
                        mode = ApprovalMode.Plan,
                    )
                }
            }
            ApprovalMode.Default, ApprovalMode.Yolo -> Unit
        }
    }

    /**
     * Run the registered [HookRegistry]'s pre-tool callbacks. A
     * [HookDecision.Deny] aborts execution and surfaces to the LLM via
     * [HookDeniedException]. Empty registries skip the work entirely.
     */
    private suspend fun runPreToolHooks(args: TArgs) {
        val hooks = ctx.hooks
        if (hooks.isEmpty) return
        val argsPreview = args?.toString()?.take(HOOK_ARGS_PREVIEW_MAX).orEmpty()
        val hookCtx = HookContext.ToolStart(
            traceId = "",
            conversationId = "",
            toolName = name,
            argsPreview = argsPreview,
            risk = risk,
        )
        when (val decision = hooks.onToolStart(hookCtx)) {
            is HookDecision.Continue -> Unit
            is HookDecision.Deny -> throw HookDeniedException(name, decision.reason)
        }
    }

    private suspend fun runPermissionGate() {
        if (requiredPermissions.isEmpty()) return

        // First pass: what's missing right now?
        val initiallyMissing = requiredPermissions.filter {
            os.permissions.check(it) != PermissionState.GRANTED
        }
        if (initiallyMissing.isEmpty()) return

        // Try to request each missing permission. On Android this shows
        // the system runtime-permission dialog. Suspends until the user
        // responds (or the host activity dies / app backgrounds, in
        // which case [Permissions.request] returns NOT_DETERMINED).
        //
        // We intentionally don't surface the per-permission outcome to
        // the LLM — the agent sees either "tool ran" or "permission
        // denied". A future enhancement could distinguish "user
        // denied for now" from "user denied forever" (DENIED_FOREVER
        // → settings deep-link) but that's a UX layer above the tool.
        for (permission in initiallyMissing) {
            os.permissions.request(permission)
        }

        // Second pass: re-check after the prompts. Anything still
        // missing → throw, the agent reports failure to the LLM.
        val stillMissing = requiredPermissions.filter {
            os.permissions.check(it) != PermissionState.GRANTED
        }
        if (stillMissing.isNotEmpty()) {
            throw PermissionDeniedException(stillMissing.toSet(), name)
        }
    }

    private suspend fun runDestructiveGate() {
        if (!destructive) return
        // Yolo mode skips the destructive prompt — power-user opt-in.
        // Read-only / Plan already rejected in runApprovalGate.
        if (ctx.approvalMode.current() == ApprovalMode.Yolo) return
        val confirmed = ui.confirmDestructive(
            action = "Confirm $name",
            body = "The agent wants to call $name which may make a destructive change.",
        )
        if (!confirmed) throw UserCancelledException(name)
    }

    private companion object {
        const val HOOK_ARGS_PREVIEW_MAX = 512
    }
}

/**
 * Shared context every [WeftTool] needs. Built once at app startup and
 * passed to every tool's constructor.
 *
 * [hooks] and [approvalMode] default to no-op / [ApprovalMode.Default]
 * so existing host code that constructs `WeftContext(os, ui, storageFactory)`
 * compiles unchanged. WeftRuntime passes the same [HookRegistry] instance
 * here that it passes to the agent so tool-level and turn-level hooks
 * fire from a unified set.
 */
public data class WeftContext(
    val os: OsCapabilities,
    val ui: UiBridge,
    val storageFactory: (toolName: String) -> ScriptStorage,
    /**
     * Lifecycle interception. Tool gates consult this BEFORE
     * [WeftTool.executeWeft]; the agent loop consults it for turn-level
     * events. Default empty registry = zero overhead.
     */
    val hooks: HookRegistry = HookRegistry.EMPTY,
    /**
     * Session-wide approval policy holder. Mutable so the host UI can
     * flip modes mid-session (user toggles Yolo, plan gets approved).
     * Pass the SAME instance to [dev.weft.harness.agents.WeftAgent] so
     * the prompt directive + gate stay in sync. Default holder starts
     * in [ApprovalMode.Default].
     */
    val approvalMode: ApprovalModeHolder = ApprovalModeHolder(),
    /**
     * Where the `exit_plan_mode` tool writes proposed plans. The app's
     * plan-review screen subscribes to [PlanStore.state] to render the
     * Approve / Refine / Cancel UI. Defaults to an in-memory store —
     * apps that want plans to survive process death implement against
     * SQLDelight (similar to the conversation store).
     */
    val planStore: PlanStore = InMemoryPlanStore(),
)

public class PermissionDeniedException(
    public val missing: Set<Permission>,
    public val toolName: String,
) : RuntimeException(
    "Permission denied for $toolName: ${missing.joinToString { it.name }}.",
)

public class UserCancelledException(public val toolName: String) :
    RuntimeException("User declined to run $toolName.")

/**
 * Raised when a tool's [ToolRisk] is incompatible with the active
 * [ApprovalMode] — [ApprovalMode.ReadOnly] rejecting a Write tool, for
 * example. The LLM sees this as a tool failure and can react.
 */
public class ApprovalDeniedException(
    public val toolName: String,
    public val risk: ToolRisk,
    public val mode: ApprovalMode,
) : RuntimeException(
    "Tool '$toolName' (risk=$risk) blocked by approval mode $mode.",
)
