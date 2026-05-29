package dev.weft.compose.components
import dev.weft.contracts.ComponentEvent
import dev.weft.contracts.ComponentCategory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * [ComponentCategory.ACTION] components — fire a semantic event when
 * the user interacts. Each declares an `action` (or similar) prop the
 * agent receives via [ComponentEvent.Action] on the next turn.
 */

// ============================================================================
// Button — primary / secondary / text
// ============================================================================

@Serializable
public data class ButtonProps(
    val text: String,
    val action: String,
    /** One of: primary, secondary, text. */
    val variant: String = "primary",
    val fillWidth: Boolean = false,
)

public class ButtonComponent : WeftComponent<ButtonProps>(
    name = "Button",
    description = "A tappable button. Required: text, action (string key fired back to the agent). variant: 'primary' (default), 'secondary', 'text'. fillWidth: true to stretch (good inside a Row to make buttons equal-width).",
    category = ComponentCategory.ACTION,
    propsSerializer = ButtonProps.serializer(),
) {
    @Composable
    override fun Render(props: ButtonProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val onClick = {
            onEvent(ComponentEvent.Action(action = props.action, sourceType = "Button", sourceLabel = props.text))
        }
        val modifier = if (props.fillWidth) Modifier.fillMaxWidth() else Modifier
        when (props.variant.lowercase()) {
            "secondary" -> OutlinedButton(onClick = onClick, modifier = modifier) { Text(props.text) }
            "text" -> TextButton(onClick = onClick, modifier = modifier) { Text(props.text) }
            else -> Button(onClick = onClick, modifier = modifier) { Text(props.text) }
        }
    }
}

// ============================================================================
// IconButton
// ============================================================================

@Serializable
public data class IconButtonProps(
    val icon: String,
    val action: String,
    /** 'default' (no fill), 'filled', 'tonal'. */
    val variant: String = "default",
    val label: String = "",
)

public class IconButtonComponent : WeftComponent<IconButtonProps>(
    name = "IconButton",
    description = "An icon-only tappable button. Required: icon (see Icon component for names), action. Optional: variant ('default'/'filled'/'tonal'), label.",
    category = ComponentCategory.ACTION,
    propsSerializer = IconButtonProps.serializer(),
    example = """{"type": "IconButton", "props": {"icon": "refresh", "action": "refresh", "variant": "tonal"}}""",
) {
    @Composable
    override fun Render(props: IconButtonProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val onClick = {
            onEvent(
                ComponentEvent.Action(
                    action = props.action,
                    sourceType = "IconButton",
                    sourceLabel = props.label.ifBlank { props.icon },
                ),
            )
        }
        val content = @Composable {
            Icon(lookupIcon(props.icon), contentDescription = props.label.ifBlank { props.icon })
        }
        when (props.variant.lowercase()) {
            "filled" -> FilledIconButton(onClick = onClick) { content() }
            "tonal" -> FilledTonalIconButton(onClick = onClick) { content() }
            else -> IconButton(onClick = onClick) { content() }
        }
    }
}

// ============================================================================
// Fab — floating-action-button style (rendered inline in trees)
// ============================================================================

@Serializable
public data class FabProps(
    val icon: String,
    val action: String,
    /** If non-empty, renders the Extended FAB with label beside the icon. */
    val label: String = "",
)

public class FabComponent : WeftComponent<FabProps>(
    name = "Fab",
    description = "A prominent rounded primary-action button. Required: icon, action. Optional: label (presence promotes to the Extended FAB variant with text beside the icon).",
    category = ComponentCategory.ACTION,
    propsSerializer = FabProps.serializer(),
    example = """{"type": "Fab", "props": {"icon": "add", "action": "create_entry", "label": "New entry"}}""",
) {
    @Composable
    override fun Render(props: FabProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val vector = lookupIcon(props.icon)
        val onClick = {
            onEvent(
                ComponentEvent.Action(
                    action = props.action,
                    sourceType = "Fab",
                    sourceLabel = props.label.ifBlank { props.icon },
                ),
            )
        }
        if (props.label.isNotBlank()) {
            ExtendedFloatingActionButton(
                onClick = onClick,
                icon = { Icon(vector, contentDescription = props.label) },
                text = { Text(props.label) },
            )
        } else {
            FloatingActionButton(onClick = onClick) {
                Icon(vector, contentDescription = props.icon)
            }
        }
    }
}

// ============================================================================
// Chip — assist / filter / suggestion / input
// ============================================================================

@Serializable
public data class ChipProps(
    val text: String,
    /** 'assist' (default, non-stateful), 'filter' (toggleable), 'suggestion', 'input' (with delete). */
    val variant: String = "assist",
    val action: String = "",
    /** For filter chips: current selection state. */
    val selected: Boolean = false,
    /** Optional leading icon name. */
    val icon: String = "",
)

public class ChipComponent : WeftComponent<ChipProps>(
    name = "Chip",
    description = "A compact selectable label. variant: 'assist' (default), 'filter' (toggleable selection), 'suggestion', 'input' (with delete affordance). Required: text. Optional: action, selected (for filter), icon. Filter variant fires Action with sourceLabel='text' and the selected state is supplied by the agent on re-render.",
    category = ComponentCategory.ACTION,
    propsSerializer = ChipProps.serializer(),
    example = """{"type": "Chip", "props": {"text": "Today", "variant": "filter", "selected": true, "action": "filter_today"}}""",
) {
    @Composable
    override fun Render(props: ChipProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val onClick = {
            if (props.action.isNotBlank()) {
                onEvent(
                    ComponentEvent.Action(
                        action = props.action,
                        sourceType = "Chip",
                        sourceLabel = props.text,
                    ),
                )
            }
        }
        val leadingIcon: (@Composable () -> Unit)? = if (props.icon.isNotBlank()) {
            { Icon(lookupIcon(props.icon), contentDescription = null) }
        } else null

        when (props.variant.lowercase()) {
            "filter" -> FilterChip(
                selected = props.selected,
                onClick = onClick,
                label = { Text(props.text) },
                leadingIcon = if (props.selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else leadingIcon,
            )
            "suggestion" -> SuggestionChip(onClick = onClick, label = { Text(props.text) })
            "input" -> InputChip(
                selected = props.selected,
                onClick = onClick,
                label = { Text(props.text) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
            )
            else -> AssistChip(
                onClick = onClick,
                label = { Text(props.text) },
                leadingIcon = leadingIcon,
            )
        }
    }
}

// ============================================================================
// ListItem — a row, optionally tappable
// ============================================================================

@Serializable
public data class ListItemProps(
    val headline: String,
    val supporting: String = "",
    val overline: String = "",
    val trailing: String = "",
    /** Optional action key. If set, the row is tappable and fires this action. */
    val action: String = "",
)

public class ListItemComponent : WeftComponent<ListItemProps>(
    name = "ListItem",
    description = "A single row in a list. Required: headline. Optional: supporting (subtitle), overline (small text above), trailing (right-side text), action (makes the row tappable). When action is set, fires Action on tap.",
    category = ComponentCategory.ACTION,
    propsSerializer = ListItemProps.serializer(),
) {
    @Composable
    override fun Render(props: ListItemProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val modifier = if (props.action.isNotBlank()) {
            Modifier.fillMaxWidth().clickable {
                onEvent(ComponentEvent.Action(props.action, sourceType = "ListItem", sourceLabel = props.headline))
            }
        } else {
            Modifier.fillMaxWidth()
        }
        ListItem(
            modifier = modifier,
            headlineContent = { Text(props.headline) },
            supportingContent = if (props.supporting.isNotBlank()) ({ Text(props.supporting) }) else null,
            overlineContent = if (props.overline.isNotBlank()) ({ Text(props.overline) }) else null,
            trailingContent = if (props.trailing.isNotBlank()) ({ Text(props.trailing, style = MaterialTheme.typography.labelMedium) }) else null,
            colors = ListItemDefaults.colors(),
        )
    }
}

// ============================================================================
// Countdown — ticking counter that fires on complete (no controls)
// ============================================================================

@Serializable
public data class CountdownProps(
    val durationMs: Long,
    val onCompleteAction: String = "timer_completed",
    /** "display" (default), "headline", "title". */
    val variant: String = "display",
)

public class CountdownComponent : WeftComponent<CountdownProps>(
    name = "Countdown",
    description = "A bare ticking countdown — display + onComplete event, no Pause/Reset controls. Required: durationMs (e.g. 1500000 for 25 min). Optional: onCompleteAction (default 'timer_completed'), variant. Prefer the Timer macro for timers that need user controls; use Countdown when you want a counter only.",
    category = ComponentCategory.ACTION,
    propsSerializer = CountdownProps.serializer(),
) {
    @OptIn(ExperimentalTime::class)
    @Composable
    override fun Render(props: CountdownProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val endsAt = remember(props.durationMs) { Clock.System.now().toEpochMilliseconds() + props.durationMs }
        var remainingMs by remember(props.durationMs) { mutableLongStateOf(props.durationMs) }

        LaunchedEffect(endsAt) {
            while (true) {
                val r = (endsAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L)
                remainingMs = r
                if (r == 0L) {
                    onEvent(ComponentEvent.Action(action = props.onCompleteAction, sourceType = "Countdown", sourceLabel = "timer"))
                    break
                }
                delay(COUNTDOWN_TICK_MS)
            }
        }

        Text(
            text = formatCountdown(remainingMs),
            style = when (props.variant.lowercase()) {
                "headline" -> MaterialTheme.typography.headlineLarge
                "title" -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.displayLarge
            },
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    public companion object {
        private const val COUNTDOWN_TICK_MS = 250L
        private fun formatCountdown(ms: Long): String {
            val totalSeconds = (ms + 500) / 1000
            val secs = totalSeconds % 60
            val secsStr = if (secs < 10) "0$secs" else secs.toString()
            return "${totalSeconds / 60}:$secsStr"
        }
    }
}

/** All [ComponentCategory.ACTION] components shipped with the substrate. */
public val ActionComponents: List<WeftComponent<*>> = listOf(
    ButtonComponent(),
    IconButtonComponent(),
    FabComponent(),
    ChipComponent(),
    ListItemComponent(),
    CountdownComponent(),
)
