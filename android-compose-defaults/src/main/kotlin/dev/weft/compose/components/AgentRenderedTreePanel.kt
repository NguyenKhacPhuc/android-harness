package dev.weft.compose.components
import dev.weft.contracts.ComponentRegistry
import dev.weft.contracts.ComponentEvent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.weft.contracts.UIUpdate
import dev.weft.compose.ComposeUiBridge

/**
 * Renders the latest LLM-composed component tree (if any) above the
 * app's chat surface. Weft provides this as a default; apps that
 * want bespoke styling skip it and render against
 * `ComposeUiBridge.lastUpdate` themselves (the same escape hatch as
 * `PendingRequestRenderer`).
 *
 * **Lifecycle:**
 *   - The panel observes `uiBridge.lastUpdate` reactively.
 *   - When a `UIUpdate.RenderTree` lands, the panel renders the tree.
 *   - Text-field values entered on the rendered surface are snapshotted
 *     locally so they can ride along on the next [onAction] call.
 *   - Tapping a Button fires [onAction] with the action key, the
 *     button's label, and the current field-value snapshot. The host
 *     typically calls `agent.sendEvent(...)` to round-trip to the LLM.
 *   - The user can dismiss via the X button, which clears the bridge
 *     state so the panel disappears.
 *
 * The panel is intentionally small and unobtrusive — a `Card` above the
 * chat input. Apps wanting a full-screen template path should use
 * `UIUpdate.Navigate` instead.
 */
@Composable
public fun AgentRenderedTreePanel(
    uiBridge: ComposeUiBridge,
    registry: ComponentRegistry,
    onAction: (action: String, sourceLabel: String?, fieldValues: Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val update = uiBridge.lastUpdate
    if (update !is UIUpdate.RenderTree) return

    // Snapshot of text-field values from the current tree, keyed by the
    // TextField component's `id` prop. Re-keyed by the tree itself so a
    // fresh render replaces (not merges) prior values.
    val fieldValues = remember(update.tree) { mutableStateMapOf<String, String>() }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Rendered by Claude",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { uiBridge.clearLastUpdate() }) { Text("Dismiss") }
            }
            Spacer(modifier = Modifier.padding(2.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                TreeRenderer(
                    tree = update.tree,
                    registry = registry,
                    onEvent = { event ->
                        when (event) {
                            is ComponentEvent.TextChanged -> {
                                fieldValues[event.sourceId] = event.value
                            }
                            is ComponentEvent.ToggleChanged -> {
                                fieldValues[event.sourceId] = event.value.toString()
                            }
                            is ComponentEvent.Action -> {
                                onAction(event.action, event.sourceLabel, fieldValues.toMap())
                            }
                        }
                    },
                )
            }
        }
    }
}
