package dev.weft.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Compact agent picker — renders as an [AssistChip] showing the
 * currently-active agent's display name, expandable into a dropdown of
 * every user-addressable agent the host registered.
 *
 * **What this is for.** Multi-agent hosts (one runtime with > 1
 * registered [`AgentDeclaration`][1]) need a way for users to choose
 * which agent handles the next turn. Apps that want the substrate's
 * default UX wire this composable above the chat input and route
 * [onSelect] into `AppStore` (or whatever holds chat state).
 *
 * **What this is NOT for.** Single-agent hosts. The default
 * `AgentDeclaration.DEFAULT_AGENT_NAME` is auto-synthesized so chat
 * works without any selector at all; rendering this composable with
 * one option is wasted screen space.
 *
 * The composable is decoupled from `:harness:agents` types so this
 * module doesn't grow a dep on the agent runtime. Host apps adapt
 * `runtime.agentDeclarations.values` into the [options] list with a
 * one-line map: `decl -> AgentOption(decl.name, decl.displayName,
 * decl.description)`.
 *
 * [1]: see :harness:agents/AgentDeclaration.kt
 */
@Composable
public fun AgentSelector(
    options: List<AgentOption>,
    selectedName: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.size <= 1) {
        // Single-agent or empty list: render nothing. The host doesn't
        // need to wrap this composable in its own visibility check.
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.name == selectedName } ?: options.first()
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(selected.displayName) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (opt in options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = opt.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (opt.name != selectedName) onSelect(opt.name)
                    },
                )
            }
        }
    }
}

/**
 * UI-side projection of an [`AgentDeclaration`][1]. Host apps map
 * declarations to this shape when feeding [AgentSelector]; the
 * composable doesn't need the full declaration (tools, strategy, etc.)
 * to render a picker.
 *
 * [1]: :harness:agents/AgentDeclaration.kt
 */
public data class AgentOption(
    public val name: String,
    public val displayName: String,
    public val description: String,
)
