package dev.weft.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.TypeToken
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.Permission
import dev.weft.contracts.PermissionState
import dev.weft.contracts.ScriptStorage
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
 */
public abstract class WeftTool<TArgs, TResult>(
    public val ctx: WeftContext,
    argsType: TypeToken,
    resultType: TypeToken,
    descriptor: ToolDescriptor,
    public val destructive: Boolean = false,
    public val sideEffecting: Boolean = false,
    public val requiredPermissions: Set<Permission> = emptySet(),
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
        runPermissionGate()
        runDestructiveGate()
        return executeWeft(args)
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
        val confirmed = ui.confirmDestructive(
            action = "Confirm $name",
            body = "The agent wants to call $name which may make a destructive change.",
        )
        if (!confirmed) throw UserCancelledException(name)
    }
}

/**
 * Shared context every [WeftTool] needs. Built once at app startup and
 * passed to every tool's constructor.
 */
public data class WeftContext(
    val os: OsCapabilities,
    val ui: UiBridge,
    val storageFactory: (toolName: String) -> ScriptStorage,
)

public class PermissionDeniedException(
    public val missing: Set<Permission>,
    public val toolName: String,
) : RuntimeException(
    "Permission denied for $toolName: ${missing.joinToString { it.name }}.",
)

public class UserCancelledException(public val toolName: String) :
    RuntimeException("User declined to run $toolName.")
