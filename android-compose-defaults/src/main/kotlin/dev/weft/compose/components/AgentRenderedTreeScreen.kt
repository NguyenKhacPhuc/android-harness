package dev.weft.compose.components
import dev.weft.contracts.ComponentRegistry
import dev.weft.contracts.ComponentEvent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.weft.contracts.UIUpdate
import dev.weft.compose.ComposeUiBridge
import kotlinx.coroutines.launch

/**
 * Full-screen surface for LLM-rendered component trees. Weft's
 * default v1 surface — replaces the chat view entirely while the agent
 * has a tree on screen.
 *
 * **Lifecycle:**
 *   - The host (typically MainActivity) navigates here when
 *     `ComposeUiBridge.lastUpdate` becomes a [UIUpdate.RenderTree].
 *   - Text-field / toggle values from the rendered surface are
 *     snapshotted locally and ride along on the next [onAction] call.
 *   - Tapping a Button calls [onAction] (suspending) — the screen
 *     shows a loading state while the agent thinks. If the agent
 *     responds with another `ui_render`, the bridge's `lastUpdate`
 *     flips and this screen recomposes with the new tree.
 *   - Back button calls [onBack] — typically the host clears
 *     `lastUpdate` and routes back to chat.
 *
 * For apps that prefer the inline-overlay model (a small card above
 * chat instead of taking over the surface), use
 * [AgentRenderedTreePanel] instead. Both read the same `UiBridge`
 * state — apps pick one based on the screen budget of the use case.
 */
@Composable
public fun AgentRenderedTreeScreen(
    uiBridge: ComposeUiBridge,
    registry: ComponentRegistry,
    onAction: suspend (action: String, sourceLabel: String?, fieldValues: Map<String, String>) -> Unit,
    onBack: () -> Unit,
    /**
     * Optional [DataSourceRegistry] for resolving `$binding` sentinels
     * in component props against live data. When non-null, the screen
     * uses [BindingAwareRenderer] (which subscribes to source-change
     * flows for reactive updates); when null, falls back to the plain
     * [TreeRenderer]. Apps that ship `data_*` tools should pass the
     * same registry they handed to `WeftRuntime.create`.
     */
    dataSources: dev.weft.contracts.DataSourceRegistry? = null,
    /**
     * Optional callback for the "Save" affordance in the top bar.
     * When non-null, the screen renders a Save TextButton next to
     * Back; tapping it lets the host capture the current rendered
     * tree (plus whatever it associates with this view — typically
     * the user prompt that produced it) for later re-invocation.
     *
     * Without this, mini-apps the agent renders are transient: the
     * user sees them once and has no in-screen way to persist them.
     * Setting it on adds a save path the user can hit without
     * backing out to chat first.
     */
    onSaveAsFeature: (() -> Unit)? = null,
) {
    val update = uiBridge.lastUpdate
    if (update !is UIUpdate.RenderTree) {
        // Nothing to render — host should already have routed away, but
        // be defensive: show a minimal "no tree" message rather than crash.
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No rendered UI", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.padding(4.dp))
                    TextButton(onClick = onBack) { Text("Back to chat") }
                }
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val fieldValues = remember(update.tree) { mutableStateMapOf<String, String>() }
    var inFlight by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar — Back, label, optional thinking spinner.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, enabled = !inFlight) { Text("← Back") }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Rendered by Claude",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (onSaveAsFeature != null) {
                    // Save lives next to Back so the user can persist
                    // the mini-app without first backing out to chat
                    // and hunting for the assistant reply's "Save as
                    // feature" link. The host opens its dialog +
                    // captures whatever metadata it needs.
                    TextButton(onClick = onSaveAsFeature) { Text("Save") }
                } else {
                    Spacer(modifier = Modifier.padding(8.dp))
                }
            }
            HorizontalDivider()

            // Scrolling content — trees may exceed screen height.
            // Horizontal padding is minimal (8dp) so wide components like
            // DatePicker fit; Cards in the tree already supply their own
            // breathing room via their `padding` prop.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val onEvent: (ComponentEvent) -> Unit = handler@{ event ->
                    when (event) {
                        is ComponentEvent.TextChanged -> {
                            fieldValues[event.sourceId] = event.value
                        }
                        is ComponentEvent.ToggleChanged -> {
                            fieldValues[event.sourceId] = event.value.toString()
                        }
                        is ComponentEvent.Action -> {
                            if (inFlight) return@handler
                            inFlight = true
                            scope.launch {
                                try {
                                    // Substrate-level $exec fast-path. When the
                                    // agent emitted a direct-execute action, the
                                    // ActionExecutor runs the named data tool
                                    // against the registry — no LLM round-trip,
                                    // sub-100ms tap-to-result. Only forwards to
                                    // the host's onAction for legacy
                                    // LLM-driven actions (opaque strings).
                                    val handled = dataSources?.let { sources ->
                                        val isExec = dev.weft.harness.bindings.ActionExecutor
                                            .isExecAction(event.action)
                                        if (isExec) {
                                            android.util.Log.d(
                                                "WeftBindings",
                                                "\$exec intercepted: sourceLabel=${event.sourceLabel}",
                                            )
                                        }
                                        dev.weft.harness.bindings.ActionExecutor
                                            .tryExecuteAction(event.action, sources)
                                            .handled
                                    } ?: false
                                    if (!handled) {
                                        onAction(event.action, event.sourceLabel, fieldValues.toMap())
                                    }
                                } finally {
                                    inFlight = false
                                }
                            }
                        }
                    }
                }
                // Pick the renderer based on whether the host supplied a
                // data-source registry. With one we get reactive $binding
                // resolution; without one we fall back to the static tree
                // renderer (existing behavior for apps that haven't
                // adopted bindings yet).
                if (dataSources != null) {
                    BindingAwareRenderer(
                        tree = update.tree,
                        registry = registry,
                        sources = dataSources,
                        onEvent = onEvent,
                    )
                } else {
                    TreeRenderer(
                        tree = update.tree,
                        registry = registry,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}
