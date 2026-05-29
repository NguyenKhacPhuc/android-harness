package dev.weft.compose.components
import dev.weft.contracts.ComponentEvent
import dev.weft.contracts.ComponentCategory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * [ComponentCategory.MACRO] components — behaviorally-complete widgets.
 * Each owns all its local state and fires only **semantic** events back
 * to the agent (timer complete, form submitted, choice picked).
 */

// ============================================================================
// Timer
// ============================================================================

@Serializable
public data class TimerProps(
    val durationMs: Long,
    val title: String = "",
    val onComplete: String = "timer_completed",
    val showReset: Boolean = true,
    val showPause: Boolean = true,
    val extendByMs: Long = 300_000L,
)

public class TimerComponent : WeftComponent<TimerProps>(
    name = "Timer",
    description = "A countdown timer with built-in Reset / Pause / Extend controls. Required: durationMs (e.g. 1500000 for 25 min). Optional: title, onComplete, showReset, showPause, extendByMs. All controls are local — the agent only hears onComplete when the timer hits zero.",
    category = ComponentCategory.MACRO,
    propsSerializer = TimerProps.serializer(),
    example = """{"type": "Timer", "props": {"durationMs": 1500000, "title": "Focus", "onComplete": "focus_done"}}""",
) {
    @Composable
    override fun Render(props: TimerProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        var endsAt by remember(props.durationMs) { mutableLongStateOf(nowMs() + props.durationMs) }
        var remainingMs by remember(props.durationMs) { mutableLongStateOf(props.durationMs) }
        var isPaused by remember(props.durationMs) { mutableStateOf(false) }
        var hasCompleted by remember(props.durationMs) { mutableStateOf(false) }

        LaunchedEffect(endsAt, isPaused, hasCompleted) {
            if (isPaused || hasCompleted) return@LaunchedEffect
            while (true) {
                val r = (endsAt - nowMs()).coerceAtLeast(0L)
                remainingMs = r
                if (r == 0L) {
                    hasCompleted = true
                    onEvent(ComponentEvent.Action(
                        action = props.onComplete,
                        sourceType = "Timer",
                        sourceLabel = props.title.ifBlank { "timer" },
                    ))
                    break
                }
                delay(TIMER_TICK_MS)
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (props.title.isNotBlank()) {
                Text(props.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = formatTimer(remainingMs),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            val showAnyControl = props.showReset || (props.showPause && !hasCompleted) || props.extendByMs > 0
            if (showAnyControl) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (props.showPause && !hasCompleted) {
                        OutlinedButton(onClick = {
                            if (isPaused) {
                                endsAt = nowMs() + remainingMs
                                isPaused = false
                            } else {
                                isPaused = true
                            }
                        }) { Text(if (isPaused) "Resume" else "Pause") }
                    }
                    if (props.showReset) {
                        OutlinedButton(onClick = {
                            endsAt = nowMs() + props.durationMs
                            remainingMs = props.durationMs
                            isPaused = false
                            hasCompleted = false
                        }) { Text("Reset") }
                    }
                    if (props.extendByMs > 0) {
                        OutlinedButton(onClick = {
                            val newRemaining = remainingMs + props.extendByMs
                            remainingMs = newRemaining
                            if (!isPaused) endsAt = nowMs() + newRemaining
                            if (hasCompleted) hasCompleted = false
                        }) { Text("+${props.extendByMs / 60_000}m") }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Stopwatch
// ============================================================================

@Serializable
public data class StopwatchProps(
    val title: String = "",
    val onDone: String = "stopwatch_done",
    val showLaps: Boolean = true,
)

public class StopwatchComponent : WeftComponent<StopwatchProps>(
    name = "Stopwatch",
    description = "A counting-up stopwatch with Start / Pause / Lap / Reset / Done controls. All buttons work locally. Done fires onDone — the agent only hears that one event.",
    category = ComponentCategory.MACRO,
    propsSerializer = StopwatchProps.serializer(),
    example = """{"type": "Stopwatch", "props": {"title": "Workout", "onDone": "workout_logged"}}""",
) {
    @Composable
    override fun Render(props: StopwatchProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        var elapsedMs by remember { mutableLongStateOf(0L) }
        var startedAt by remember { mutableLongStateOf(0L) }
        var isRunning by remember { mutableStateOf(false) }
        val laps = remember { mutableStateListOf<Long>() }

        LaunchedEffect(isRunning, startedAt) {
            if (!isRunning) return@LaunchedEffect
            val base = elapsedMs
            while (isRunning) {
                elapsedMs = base + (nowMs() - startedAt)
                delay(STOPWATCH_TICK_MS)
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (props.title.isNotBlank()) {
                Text(props.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = formatStopwatch(elapsedMs),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRunning) {
                    OutlinedButton(onClick = { isRunning = false }) { Text("Pause") }
                    OutlinedButton(onClick = { laps.add(elapsedMs) }) { Text("Lap") }
                } else {
                    Button(onClick = {
                        startedAt = nowMs()
                        isRunning = true
                    }) { Text(if (elapsedMs == 0L) "Start" else "Resume") }
                    OutlinedButton(onClick = {
                        elapsedMs = 0L
                        laps.clear()
                    }) { Text("Reset") }
                }
                Button(onClick = {
                    onEvent(ComponentEvent.Action(
                        action = props.onDone,
                        sourceType = "Stopwatch",
                        sourceLabel = props.title.ifBlank { "stopwatch" },
                    ))
                }) { Text("Done") }
            }
            if (props.showLaps && laps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                    laps.forEachIndexed { i, ms ->
                        Text(
                            text = "Lap ${i + 1}: ${formatStopwatch(ms)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Form
// ============================================================================

@Serializable
public data class FormField(
    val id: String,
    val label: String,
    /** "text" (default), "email", "number", "multiline". */
    val type: String = "text",
    val placeholder: String = "",
    val initialValue: String = "",
    val required: Boolean = false,
)

@Serializable
public data class FormProps(
    val title: String = "",
    val fields: List<FormField>,
    val submitLabel: String = "Submit",
    val onSubmit: String = "form_submitted",
    val cancelLabel: String = "",
    val onCancel: String = "form_cancelled",
)

public class FormComponent : WeftComponent<FormProps>(
    name = "Form",
    description = "A complete form macro. Required: fields (list of {id, label, type, placeholder, initialValue, required}). Optional: title, submitLabel, onSubmit, cancelLabel, onCancel. All typing is local. Submit validates required fields and fires onSubmit with every field's value bundled.",
    category = ComponentCategory.MACRO,
    propsSerializer = FormProps.serializer(),
    example = """{"type": "Form", "props": {"title": "Quick entry", "fields": [{"id": "mood", "label": "Mood", "required": true}, {"id": "note", "label": "Notes", "type": "multiline"}], "submitLabel": "Save", "onSubmit": "entry_saved"}}""",
) {
    @Composable
    override fun Render(props: FormProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val values = remember(props.fields) {
            mutableStateMapOf<String, String>().apply { props.fields.forEach { put(it.id, it.initialValue) } }
        }
        var errorMessage by remember(props.fields) { mutableStateOf<String?>(null) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (props.title.isNotBlank()) {
                Text(props.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            props.fields.forEach { field ->
                OutlinedTextField(
                    value = values[field.id] ?: "",
                    onValueChange = { values[field.id] = it },
                    label = { Text(field.label + if (field.required) " *" else "") },
                    placeholder = if (field.placeholder.isNotBlank()) ({ Text(field.placeholder) }) else null,
                    singleLine = field.type != "multiline",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.type) {
                            "email" -> KeyboardType.Email
                            "number" -> KeyboardType.Number
                            else -> KeyboardType.Text
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            errorMessage?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                if (props.cancelLabel.isNotBlank()) {
                    TextButton(onClick = {
                        onEvent(ComponentEvent.Action(
                            action = props.onCancel,
                            sourceType = "Form",
                            sourceLabel = props.title.ifBlank { "form" },
                        ))
                    }) { Text(props.cancelLabel) }
                }
                Button(onClick = {
                    val missing = props.fields.filter { it.required && values[it.id].isNullOrBlank() }
                    if (missing.isNotEmpty()) {
                        errorMessage = "Please fill in: " + missing.joinToString { it.label }
                        return@Button
                    }
                    errorMessage = null
                    props.fields.forEach { field ->
                        onEvent(ComponentEvent.TextChanged(sourceId = field.id, value = values[field.id] ?: ""))
                    }
                    onEvent(ComponentEvent.Action(
                        action = props.onSubmit,
                        sourceType = "Form",
                        sourceLabel = props.title.ifBlank { "form" },
                    ))
                }) { Text(props.submitLabel) }
            }
        }
    }
}

// ============================================================================
// Picker
// ============================================================================

@Serializable
public data class PickerProps(
    val title: String = "",
    val options: List<String>,
    val initial: String = "",
    /** "radio" (default — pick then confirm) or "buttons" (one-tap each option). */
    val style: String = "radio",
    val confirmLabel: String = "Confirm",
    val onPicked: String = "picked",
)

public class PickerComponent : WeftComponent<PickerProps>(
    name = "Picker",
    description = "A pick-one-from-list macro. Required: options. Optional: title, initial, style ('radio' = radio buttons + confirm; 'buttons' = each option a button, fires immediately on tap). Fires onPicked with the chosen option in sourceLabel.",
    category = ComponentCategory.MACRO,
    propsSerializer = PickerProps.serializer(),
    example = """{"type": "Picker", "props": {"title": "Pick a flavor", "options": ["Vanilla", "Chocolate", "Strawberry"], "style": "buttons", "onPicked": "flavor_picked"}}""",
) {
    @Composable
    override fun Render(props: PickerProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (props.title.isNotBlank()) {
                Text(props.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            when (props.style.lowercase()) {
                "buttons" -> {
                    props.options.forEach { option ->
                        Button(
                            onClick = {
                                onEvent(ComponentEvent.Action(
                                    action = props.onPicked,
                                    sourceType = "Picker",
                                    sourceLabel = option,
                                ))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(option) }
                    }
                }
                else -> {
                    var selected by remember(props.options) {
                        mutableStateOf(props.initial.ifBlank { props.options.firstOrNull().orEmpty() })
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                        props.options.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { selected = option },
                            ) {
                                RadioButton(selected = selected == option, onClick = { selected = option })
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(option, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            onEvent(ComponentEvent.Action(
                                action = props.onPicked,
                                sourceType = "Picker",
                                sourceLabel = selected,
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(props.confirmLabel) }
                }
            }
        }
    }
}

// ============================================================================
// DateCountdown
// ============================================================================

@Serializable
public data class DateCountdownProps(
    val title: String = "",
    val initialEpochMs: Long? = null,
    val onArrived: String = "date_arrived",
)

public class DateCountdownComponent : WeftComponent<DateCountdownProps>(
    name = "DateCountdown",
    description = "Calendar date picker + live countdown to the picked date. Optional: title, initialEpochMs (default: today + 30 days), onArrived. User can change the date freely; the countdown updates live. All controls local; the agent only hears onArrived when the date is reached. " +
        "Always pass `initialEpochMs` as a FUTURE date — compute relative to the `now` in the device-context preamble. For named events (Lunar New Year, Easter, anniversaries) look up the actual calendar date rather than guessing; if unsure, set initialEpochMs to today and let the user pick. Past dates show a 'days ago' label with an error indicator.",
    category = ComponentCategory.MACRO,
    propsSerializer = DateCountdownProps.serializer(),
    layoutNotes = "Render at top level OR inside a Card with padding='none' — the embedded calendar needs ~360dp horizontal width.",
    example = """{"type": "DateCountdown", "props": {"title": "Days until launch", "onArrived": "launched"}}""",
) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Render(props: DateCountdownProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val defaultStart = remember { nowMs() + DEFAULT_TARGET_OFFSET_MS }
        // SelectableDates: allow the user to tap only today + future on the
        // calendar. The agent's `initialEpochMs` can still be in the past
        // (it's shown for transparency so the user sees what Claude picked),
        // but they can only correct it to a future date.
        val futureOnly = remember {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val todayStartMs = nowMs() - (nowMs() % MILLIS_PER_DAY)
                    return utcTimeMillis >= todayStartMs - MILLIS_PER_DAY
                }
                override fun isSelectableYear(year: Int): Boolean {
                    val nowYear = currentYear()
                    return year >= nowYear
                }
            }
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = props.initialEpochMs ?: defaultStart,
            selectableDates = futureOnly,
        )
        // Initialize synchronously so the first frame shows the right value
        // (avoids a "0 days" flash before the LaunchedEffect ticks).
        val initialTarget = props.initialEpochMs ?: defaultStart
        var remainingMs by remember { mutableLongStateOf(initialTarget - nowMs()) }
        var hasArrived by remember { mutableStateOf(false) }

        // collectLatest: when the user changes the selected date, cancel
        // the running tick loop and restart with the new target. Otherwise
        // a ticking countdown blocks all subsequent selection changes.
        LaunchedEffect(state) {
            snapshotFlow { state.selectedDateMillis }.collectLatest { target ->
                hasArrived = false
                if (target == null) return@collectLatest
                val initialDiff = target - nowMs()
                if (initialDiff < 0) {
                    // Past date — don't tick, don't fire onArrived; just show the negative value.
                    remainingMs = initialDiff
                    return@collectLatest
                }
                while (true) {
                    val r = target - nowMs()
                    remainingMs = r
                    if (r <= 0L) {
                        if (!hasArrived) {
                            hasArrived = true
                            onEvent(ComponentEvent.Action(
                                action = props.onArrived,
                                sourceType = "DateCountdown",
                                sourceLabel = props.title.ifBlank { "date" },
                            ))
                        }
                        break
                    }
                    delay(DATE_COUNTDOWN_TICK_MS)
                }
            }
        }

        val isPast = remainingMs < 0L

        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (props.title.isNotBlank()) {
                Text(props.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            DatePicker(state = state, modifier = Modifier.fillMaxWidth())
            Text(
                text = formatDateCountdown(remainingMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                isPast -> Text(
                    text = "⚠ Past date — pick a future date on the calendar above.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                hasArrived -> Text(
                    text = "🎉 The date is here!",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ============================================================================
// Helpers
// ============================================================================

private const val TIMER_TICK_MS = 250L
private const val STOPWATCH_TICK_MS = 50L
private const val DATE_COUNTDOWN_TICK_MS = 1000L
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
private const val DEFAULT_TARGET_OFFSET_MS = 30L * MILLIS_PER_DAY

// commonMain has no `System.currentTimeMillis` / `java.time.LocalDate`, so
// these tiny wrappers stand in for the wall-clock + format helpers the
// timers use.
@OptIn(ExperimentalTime::class)
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
private fun currentYear(): Int =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year

private fun pad2(value: Long): String = if (value < 10) "0$value" else value.toString()

private fun formatTimer(ms: Long): String {
    val totalSeconds = (ms + 500) / 1000
    return "${totalSeconds / 60}:${pad2(totalSeconds % 60)}"
}

private fun formatStopwatch(ms: Long): String {
    val totalSeconds = ms / 1000
    val tenths = (ms % 1000) / 100
    return "${totalSeconds / 60}:${pad2(totalSeconds % 60)}.$tenths"
}

private fun formatDateCountdown(ms: Long): String {
    if (ms == 0L) return "Now"
    val isPast = ms < 0L
    val absMs = if (isPast) -ms else ms
    val totalSeconds = absMs / 1000
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val body = when {
        days > 0 -> "$days days"
        hours > 0 -> "$hours:${pad2(minutes)}:${pad2(seconds)}"
        else -> "$minutes:${pad2(seconds)}"
    }
    return if (isPast) "$body ago" else body
}

// ============================================================================
// Wizard — multi-step navigation with Back / Next / Done
// ============================================================================

@Serializable
public data class WizardProps(
    /** Optional label per step ("Account info", "Address", "Confirm"). Aligns positionally with children. */
    val stepLabels: List<String> = emptyList(),
    /** Action key fired when user taps Done on the last step. */
    val onComplete: String = "wizard_completed",
    val finishLabel: String = "Done",
)

public class WizardComponent : WeftComponent<WizardProps>(
    name = "Wizard",
    description = "A multi-step flow with Back / Next / Done controls. Each CHILD is one step's content (positional). Step navigation is local — no agent round-trip per step. Optional: stepLabels (per-step header), onComplete (fired when user taps Done on final step), finishLabel.",
    category = ComponentCategory.MACRO,
    propsSerializer = WizardProps.serializer(),
    example = """{"type": "Wizard", "props": {"stepLabels": ["Your info", "Preferences", "Confirm"], "onComplete": "signup_done"}, "children": [{"type": "Form", "props": {"fields": [{"id": "name", "label": "Name", "required": true}]}}, {"type": "Checkbox", "props": {"id": "newsletter", "label": "Subscribe"}}, {"type": "Text", "props": {"text": "Tap Done to finish."}}]}""",
) {
    @Composable
    override fun Render(props: WizardProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val node = LocalCurrentNode.current
        val renderNode = LocalNodeRenderer.current
        val stepCount = node.children.size
        if (stepCount == 0) {
            Text(
                text = "Empty wizard — emit at least one child step.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
            return
        }
        var currentStep by remember { mutableIntStateOf(0) }
        val safeStep = currentStep.coerceIn(0, stepCount - 1)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Step indicator
            val label = props.stepLabels.getOrNull(safeStep).orEmpty()
            Text(
                text = "Step ${safeStep + 1} of $stepCount" + if (label.isNotBlank()) " · $label" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            // Current step content
            Column(modifier = Modifier.fillMaxWidth()) {
                node.children.getOrNull(safeStep)?.let { renderNode(it) }
            }
            // Nav row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (safeStep > 0) {
                    OutlinedButton(onClick = { currentStep = safeStep - 1 }) { Text("Back") }
                }
                if (safeStep < stepCount - 1) {
                    Button(onClick = { currentStep = safeStep + 1 }) { Text("Next") }
                } else {
                    Button(onClick = {
                        onEvent(ComponentEvent.Action(
                            action = props.onComplete,
                            sourceType = "Wizard",
                            sourceLabel = label.ifBlank { "wizard" },
                        ))
                    }) { Text(props.finishLabel) }
                }
            }
        }
    }
}

/** All [ComponentCategory.MACRO] components shipped with the substrate. */
public val MacroComponents: List<WeftComponent<*>> = listOf(
    TimerComponent(),
    StopwatchComponent(),
    FormComponent(),
    PickerComponent(),
    DateCountdownComponent(),
    WizardComponent(),
)
