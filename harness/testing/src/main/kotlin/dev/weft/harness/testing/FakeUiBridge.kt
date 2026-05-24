package dev.weft.harness.testing

import dev.weft.contracts.AskKind
import dev.weft.contracts.ComponentNode
import dev.weft.contracts.OverlaySpec
import dev.weft.contracts.TreeValidationResult
import dev.weft.contracts.UIUpdate
import dev.weft.contracts.UiBridge
import dev.weft.contracts.UserAnswer
import kotlinx.coroutines.CompletableDeferred

/**
 * Test double for [UiBridge] that captures pending requests and lets the
 * test programmatically answer them. Designed for use inside `runTest { }`
 * coroutines.
 *
 * The default behaviour is **non-blocking** in the most useful way: any
 * suspending call (`askUser`, `confirmDestructive`, `showInfo`) suspends
 * until the test answers it via the matching method below. Tests typically
 * pre-arm answers using [autoYesNo], [autoChoice], etc. so a tool's
 * `confirmDestructive` returns without explicit human action mid-test.
 *
 * For tools that just emit (`UiBridge.emit(...)`) without expecting input,
 * the calls are recorded in [emittedUpdates] for assertion.
 */
public class FakeUiBridge : UiBridge {

    /** Every UIUpdate the agent emitted, in order. */
    public val emittedUpdates: MutableList<UIUpdate> = mutableListOf()

    /** Every overlay emitted via UIUpdate.Overlay (extracted for convenience). */
    public val emittedOverlays: List<OverlaySpec>
        get() = emittedUpdates.filterIsInstance<UIUpdate.Overlay>().map { it.overlay }

    /** Every askUser call: (question, kind, options). */
    public val askedQuestions: MutableList<Triple<String, AskKind, List<String>>> = mutableListOf()

    /** Every confirmDestructive call: (action, body). */
    public val confirmRequests: MutableList<Pair<String, String?>> = mutableListOf()

    /** Every showInfo call: (title, body). */
    public val infoDialogs: MutableList<Pair<String, String?>> = mutableListOf()

    /**
     * Auto-answer policy: if non-null, [askUser] returns this immediately
     * instead of suspending. Set per-test for tools that always go through
     * `askUser` (e.g. UiAskTool).
     */
    public var autoAnswer: UserAnswer? = null

    /**
     * Auto-confirm policy: if non-null, [confirmDestructive] returns this
     * immediately. `true` = approve everything, `false` = block everything,
     * `null` = suspend and wait for explicit [respondToConfirm].
     */
    public var autoConfirm: Boolean? = null

    /**
     * Auto-validation override. Default Ok — tests can flip to Invalid to
     * simulate "registry rejects this tree."
     */
    public var validationResult: TreeValidationResult = TreeValidationResult.Ok

    // Pending suspensions waiting for [respondTo*]. Keyed by call order;
    // tests usually only have one in flight at a time.
    private val pendingAsk: ArrayDeque<CompletableDeferred<UserAnswer>> = ArrayDeque()
    private val pendingConfirm: ArrayDeque<CompletableDeferred<Boolean>> = ArrayDeque()
    private val pendingInfo: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

    override suspend fun askUser(question: String, kind: AskKind, options: List<String>): UserAnswer {
        askedQuestions += Triple(question, kind, options)
        autoAnswer?.let { return it }
        val d = CompletableDeferred<UserAnswer>()
        pendingAsk += d
        return d.await()
    }

    override suspend fun confirmDestructive(action: String, body: String?): Boolean {
        confirmRequests += action to body
        autoConfirm?.let { return it }
        val d = CompletableDeferred<Boolean>()
        pendingConfirm += d
        return d.await()
    }

    override suspend fun showInfo(title: String, body: String?) {
        infoDialogs += title to body
        val d = CompletableDeferred<Unit>()
        pendingInfo += d
        d.await()
    }

    override suspend fun emit(update: UIUpdate) {
        emittedUpdates += update
    }

    override suspend fun validateTree(tree: ComponentNode): TreeValidationResult = validationResult

    // ----- Test-facing response API ----------------------------------------

    /** Resolve the next pending [askUser] with the given answer. */
    public fun respondToAsk(answer: UserAnswer) {
        check(pendingAsk.isNotEmpty()) { "No pending askUser to respond to" }
        pendingAsk.removeFirst().complete(answer)
    }

    /** Resolve the next pending [confirmDestructive] with the given decision. */
    public fun respondToConfirm(decision: Boolean) {
        check(pendingConfirm.isNotEmpty()) { "No pending confirmDestructive to respond to" }
        pendingConfirm.removeFirst().complete(decision)
    }

    /** Dismiss the next pending [showInfo] dialog. */
    public fun dismissInfo() {
        check(pendingInfo.isNotEmpty()) { "No pending showInfo to dismiss" }
        pendingInfo.removeFirst().complete(Unit)
    }
}
