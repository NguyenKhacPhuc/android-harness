package dev.weft.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.weft.contracts.AskKind
import dev.weft.contracts.ComponentNode
import dev.weft.contracts.OverlaySpec
import dev.weft.contracts.TreeValidationResult
import dev.weft.contracts.UIUpdate
import dev.weft.contracts.UiBridge
import dev.weft.contracts.UserAnswer
import dev.weft.compose.components.WeftComponentRegistry
import kotlinx.coroutines.CompletableDeferred

/**
 * Android [UiBridge] backed by a Compose state holder. When a tool calls
 * `askUser()` or `confirmDestructive()`, this bridge creates a
 * [CompletableDeferred], exposes a [pending] request via Compose state for
 * the UI to render, and suspends until the UI calls back with answer or
 * cancellation.
 *
 * Apps render [pending] via [PendingRequestRenderer], or roll their own
 * surface that reads the same state.
 *
 * Threading: bridge calls come from the agent loop (background dispatcher).
 * The answer / dismiss methods are called from the UI thread when the user
 * taps. Compose state mutations from the UI thread are safe;
 * [CompletableDeferred] is thread-safe.
 */
public class ComposeUiBridge(
    /**
     * Component palette this bridge validates `ui_render` trees against.
     * Typed as the concrete [WeftComponentRegistry] (not the SDK's
     * `ComponentRegistry` interface) because validation invokes the
     * Compose-specific `decode(props)` on each `WeftComponent` —
     * an impl detail the SDK-side contract deliberately doesn't expose.
     * Pass null to skip pre-validation (the `UiBridge` default returns Ok).
     */
    private val componentRegistry: WeftComponentRegistry? = null,
) : UiBridge {

    /** What the bridge currently wants the UI to render, if anything. */
    public var pending: PendingRequest? by mutableStateOf(null)
        private set

    /** Last UI update pushed by [emit]. Apps can observe and react. */
    public var lastUpdate: UIUpdate? by mutableStateOf(null)
        private set

    /**
     * Current ephemeral overlay (toast / banner / etc.) — written when an
     * `UIUpdate.Overlay` is emitted, cleared by [dismissOverlay].
     * Consumed by `WeftOverlayHost` (or app-custom equivalent).
     */
    public var currentOverlay: OverlaySpec? by mutableStateOf(null)
        private set

    override suspend fun askUser(question: String, kind: AskKind, options: List<String>): UserAnswer {
        val deferred = CompletableDeferred<UserAnswer>()
        pending = PendingRequest.Ask(
            question = question,
            kind = kind,
            options = options,
            answer = deferred,
        )
        return try {
            deferred.await()
        } finally {
            pending = null
        }
    }

    override suspend fun confirmDestructive(action: String, body: String?): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending = PendingRequest.Confirm(action = action, body = body, decision = deferred)
        return try {
            deferred.await()
        } finally {
            pending = null
        }
    }

    override suspend fun showInfo(title: String, body: String?) {
        val deferred = CompletableDeferred<Unit>()
        pending = PendingRequest.Info(title = title, body = body, dismissed = deferred)
        try {
            deferred.await()
        } finally {
            pending = null
        }
    }

    override suspend fun emit(update: UIUpdate) {
        // Route overlays to the ephemeral channel so they don't trample
        // the agent's current rendered surface (lastUpdate).
        when (update) {
            is UIUpdate.Overlay -> currentOverlay = update.overlay
            else -> lastUpdate = update
        }
    }

    /**
     * Pre-render validation against the local [componentRegistry]. Catches
     * "unknown component" and "bad prop" before the tree commits, so the
     * LLM gets an actionable tool error and can fix it next turn instead
     * of a runtime render crash. Returns [TreeValidationResult.Ok] when
     * no registry was provided (best-effort mode).
     */
    override suspend fun validateTree(tree: ComponentNode): TreeValidationResult {
        val registry = componentRegistry ?: return TreeValidationResult.Ok
        val unknown = collectUnknownTypes(tree, registry)
        if (unknown.isNotEmpty()) {
            return TreeValidationResult.Invalid(
                "Unknown component type(s): ${unknown.sorted().joinToString()}. " +
                    "Available components: ${registry.names().sorted().joinToString()}. " +
                    "Re-emit ui_render with only registered component types.",
            )
        }
        val badProps = collectBadProps(tree, registry)
        if (badProps.isNotEmpty()) {
            return TreeValidationResult.Invalid(
                "Invalid props on ${badProps.size} node(s):\n" +
                    badProps.joinToString("\n") { "- ${it.first}: ${it.second}" } +
                    "\nCheck the system prompt's component catalog for the correct prop " +
                    "schemas, then re-emit ui_render with valid props.",
            )
        }
        return TreeValidationResult.Ok
    }

    private fun collectUnknownTypes(node: ComponentNode, registry: WeftComponentRegistry): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(n: ComponentNode) {
            if (registry.get(n.type) == null) out += n.type
            n.children.forEach(::walk)
        }
        walk(node)
        return out
    }

    private fun collectBadProps(node: ComponentNode, registry: WeftComponentRegistry): List<Pair<String, String>> {
        val errors = mutableListOf<Pair<String, String>>()
        fun walk(n: ComponentNode) {
            val component = registry.get(n.type)
            if (component != null) {
                // Data bindings: any `$binding` JsonObject inside a prop
                // is a sentinel that resolves to a primitive at render
                // time. The component's typed schema expects that
                // primitive directly, so a strict decode would reject
                // the unresolved sentinel here. Skip strict validation
                // for nodes that contain sentinels — the binding-aware
                // renderer takes care of resolution before the typed
                // deserializer runs. Apps that don't use bindings still
                // get full strict validation.
                if (containsBindingSentinel(n.props)) {
                    n.children.forEach(::walk)
                    return
                }
                try {
                    component.decode(n.props)
                } catch (t: Throwable) {
                    errors += n.type to (t.message?.lines()?.firstOrNull()?.trim()
                        ?: t::class.simpleName.orEmpty())
                }
            }
            n.children.forEach(::walk)
        }
        walk(node)
        return errors
    }

    /**
     * Recursively check whether [value] contains a `$binding` sentinel
     * at any depth. Used by the validation path to detect "this prop
     * subtree resolves at render time, don't validate now."
     */
    private fun containsBindingSentinel(value: kotlinx.serialization.json.JsonElement): Boolean {
        return when (value) {
            is kotlinx.serialization.json.JsonObject -> {
                if ("\$binding" in value) return true
                value.values.any { containsBindingSentinel(it) }
            }
            is kotlinx.serialization.json.JsonArray -> value.any { containsBindingSentinel(it) }
            else -> false
        }
    }

    /**
     * Drop the latest UI update. Used by [AgentRenderedTreePanel]'s
     * dismiss control so a closed render isn't re-shown on recomposition.
     */
    public fun clearLastUpdate() {
        lastUpdate = null
    }

    /** Dismiss the current overlay (toast, banner). */
    public fun dismissOverlay() {
        currentOverlay = null
    }

    // ----- UI-side response API -----

    public fun answer(value: UserAnswer) {
        val p = pending
        if (p is PendingRequest.Ask) p.answer.complete(value)
    }

    public fun confirm(yes: Boolean) {
        val p = pending
        if (p is PendingRequest.Confirm) p.decision.complete(yes)
    }

    public fun acknowledgeInfo() {
        val p = pending
        if (p is PendingRequest.Info) p.dismissed.complete(Unit)
    }

    /** Dismiss the current request as cancelled (back press, swipe). */
    public fun dismiss() {
        when (val p = pending) {
            is PendingRequest.Ask -> p.answer.complete(UserAnswer.Cancelled)
            is PendingRequest.Confirm -> p.decision.complete(false)
            is PendingRequest.Info -> p.dismissed.complete(Unit)
            null -> Unit
        }
    }

    public sealed class PendingRequest {
        public data class Ask(
            val question: String,
            val kind: AskKind,
            val options: List<String>,
            val answer: CompletableDeferred<UserAnswer>,
        ) : PendingRequest()

        public data class Confirm(
            val action: String,
            val body: String?,
            val decision: CompletableDeferred<Boolean>,
        ) : PendingRequest()

        public data class Info(
            val title: String,
            val body: String?,
            val dismissed: CompletableDeferred<Unit>,
        ) : PendingRequest()
    }
}
