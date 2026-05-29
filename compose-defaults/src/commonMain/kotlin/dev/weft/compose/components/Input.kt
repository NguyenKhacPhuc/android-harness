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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * [ComponentCategory.INPUT] components — capture user data. Maintain
 * local state (typed text, selected option, slider value, picked date).
 * Each declares an `id` prop so values can ride along in the next
 * sibling [ComponentEvent.Action] event's field-values snapshot.
 */

// ============================================================================
// TextField
// ============================================================================

@Serializable
public data class TextFieldProps(
    val id: String,
    val label: String = "",
    val initialValue: String = "",
    val placeholder: String = "",
    val singleLine: Boolean = true,
)

public class TextFieldComponent : WeftComponent<TextFieldProps>(
    name = "TextField",
    description = "Single- or multi-line text input. Required: id (stable key for events). Fires TextChanged on every keystroke.",
    category = ComponentCategory.INPUT,
    propsSerializer = TextFieldProps.serializer(),
) {
    @Composable
    override fun Render(props: TextFieldProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        var value by remember(props.id) { mutableStateOf(props.initialValue) }
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                onEvent(ComponentEvent.TextChanged(sourceId = props.id, value = it))
            },
            label = if (props.label.isNotBlank()) ({ Text(props.label) }) else null,
            placeholder = if (props.placeholder.isNotBlank()) ({ Text(props.placeholder) }) else null,
            singleLine = props.singleLine,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================================
// Switch
// ============================================================================

@Serializable
public data class SwitchProps(
    val id: String,
    val label: String = "",
    val initial: Boolean = false,
)

public class SwitchComponent : WeftComponent<SwitchProps>(
    name = "Switch",
    description = "A binary toggle. Required: id (stable key). Optional: label, initial (default false). Fires ToggleChanged when flipped.",
    category = ComponentCategory.INPUT,
    propsSerializer = SwitchProps.serializer(),
) {
    @Composable
    override fun Render(props: SwitchProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        var checked by remember(props.id) { mutableStateOf(props.initial) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (props.label.isNotBlank()) {
                Text(props.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    onEvent(ComponentEvent.ToggleChanged(sourceId = props.id, value = it))
                },
            )
        }
    }
}

// ============================================================================
// Checkbox
// ============================================================================

@Serializable
public data class CheckboxProps(
    val id: String,
    val label: String = "",
    val initial: Boolean = false,
)

public class CheckboxComponent : WeftComponent<CheckboxProps>(
    name = "Checkbox",
    description = "A checkable box. Required: id. Optional: label, initial (default false). Fires ToggleChanged.",
    category = ComponentCategory.INPUT,
    propsSerializer = CheckboxProps.serializer(),
) {
    @Composable
    override fun Render(props: CheckboxProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        var checked by remember(props.id) { mutableStateOf(props.initial) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    onEvent(ComponentEvent.ToggleChanged(sourceId = props.id, value = it))
                },
            )
            if (props.label.isNotBlank()) {
                Text(props.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ============================================================================
// RadioGroup
// ============================================================================

@Serializable
public data class RadioGroupProps(
    val id: String,
    val options: List<String>,
    val initial: String = "",
    val label: String = "",
)

public class RadioGroupComponent : WeftComponent<RadioGroupProps>(
    name = "RadioGroup",
    description = "Single-select from a list. Required: id, options. Optional: initial, label. Fires TextChanged with the selected option's text.",
    category = ComponentCategory.INPUT,
    propsSerializer = RadioGroupProps.serializer(),
) {
    @Composable
    override fun Render(props: RadioGroupProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        var selected by remember(props.id) {
            mutableStateOf(props.initial.ifBlank { props.options.firstOrNull().orEmpty() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
            if (props.label.isNotBlank()) {
                Text(props.label, style = MaterialTheme.typography.labelMedium)
            }
            props.options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        selected = option
                        onEvent(ComponentEvent.TextChanged(sourceId = props.id, value = option))
                    },
                ) {
                    RadioButton(
                        selected = selected == option,
                        onClick = {
                            selected = option
                            onEvent(ComponentEvent.TextChanged(sourceId = props.id, value = option))
                        },
                    )
                    Text(option, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ============================================================================
// Slider
// ============================================================================

@Serializable
public data class SliderProps(
    val id: String,
    val label: String = "",
    val min: Float = 0f,
    val max: Float = 1f,
    val initial: Float = 0f,
    val steps: Int = 0,
)

public class SliderComponent : WeftComponent<SliderProps>(
    name = "Slider",
    description = "A numeric slider. Required: id. Optional: label, min/max (default 0..1), initial, steps (0 = continuous). Fires TextChanged with the value as a string.",
    category = ComponentCategory.INPUT,
    propsSerializer = SliderProps.serializer(),
) {
    @Composable
    override fun Render(props: SliderProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        var value by remember(props.id) { mutableStateOf(props.initial.coerceIn(props.min, props.max)) }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (props.label.isNotBlank()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(props.label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatFloat2(value), style = MaterialTheme.typography.labelMedium)
                }
            }
            Slider(
                value = value,
                onValueChange = {
                    value = it
                    onEvent(ComponentEvent.TextChanged(sourceId = props.id, value = it.toString()))
                },
                valueRange = props.min..props.max,
                steps = props.steps,
            )
        }
    }
}

// ============================================================================
// RangeSlider
// ============================================================================

@Serializable
public data class RangeSliderProps(
    val id: String,
    val label: String = "",
    val min: Float = 0f,
    val max: Float = 1f,
    val initialStart: Float = 0f,
    val initialEnd: Float = 1f,
    val steps: Int = 0,
)

public class RangeSliderComponent : WeftComponent<RangeSliderProps>(
    name = "RangeSlider",
    description = "A numeric slider with two handles for selecting a range. Required: id. Optional: label, min/max (default 0..1), initialStart/initialEnd, steps. Fires TextChanged with 'start,end' as a comma-separated pair on every drag.",
    category = ComponentCategory.INPUT,
    propsSerializer = RangeSliderProps.serializer(),
    example = """{"type": "RangeSlider", "props": {"id": "price", "label": "Price range", "min": 0, "max": 100, "initialStart": 20, "initialEnd": 60}}""",
) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Render(props: RangeSliderProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val safeMin = props.min
        val safeMax = props.max.coerceAtLeast(safeMin + 0.001f)
        var range by remember(props.id) {
            mutableStateOf(
                props.initialStart.coerceIn(safeMin, safeMax)..props.initialEnd.coerceIn(safeMin, safeMax),
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (props.label.isNotBlank()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(props.label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${formatFloat2(range.start)} – ${formatFloat2(range.endInclusive)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            RangeSlider(
                value = range,
                onValueChange = {
                    range = it
                    onEvent(
                        ComponentEvent.TextChanged(
                            sourceId = props.id,
                            value = "${it.start},${it.endInclusive}",
                        ),
                    )
                },
                valueRange = safeMin..safeMax,
                steps = props.steps,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ============================================================================
// DatePicker
// ============================================================================

@Serializable
public data class DatePickerProps(
    val id: String,
    /** Optional initial selection (epoch milliseconds). Default: today. */
    val initialEpochMs: Long? = null,
    val label: String = "",
)

public class DatePickerComponent : WeftComponent<DatePickerProps>(
    name = "DatePicker",
    description = "Inline calendar date picker. Required: id. Optional: initialEpochMs (default today), label. Fires TextChanged with the selected date as ISO 'YYYY-MM-DD'.",
    category = ComponentCategory.INPUT,
    propsSerializer = DatePickerProps.serializer(),
    layoutNotes = "Needs ~360dp horizontal — render at top level OR inside a Card with padding='none'. A normal padded Card squeezes the calendar grid until days overlap.",
    example = """{"type": "DatePicker", "props": {"id": "due_date", "label": "Due date"}}""",
) {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
    @Composable
    override fun Render(props: DatePickerProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = props.initialEpochMs ?: Clock.System.now().toEpochMilliseconds(),
        )
        LaunchedEffect(state) {
            snapshotFlow { state.selectedDateMillis }.collect { ms ->
                if (ms != null) {
                    val iso = isoDateFromEpochMs(ms)
                    onEvent(ComponentEvent.TextChanged(sourceId = props.id, value = iso))
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (props.label.isNotBlank()) {
                Text(
                    text = props.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            DatePicker(state = state, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ============================================================================
// TimePicker
// ============================================================================

@Serializable
public data class TimePickerProps(
    val id: String,
    val initialHour: Int = 12,
    val initialMinute: Int = 0,
    val is24Hour: Boolean = false,
    val label: String = "",
)

public class TimePickerComponent : WeftComponent<TimePickerProps>(
    name = "TimePicker",
    description = "Inline clock-face time picker. Required: id. Optional: initialHour (default 12), initialMinute (0), is24Hour (false), label. Fires TextChanged with 'HH:mm' format.",
    category = ComponentCategory.INPUT,
    propsSerializer = TimePickerProps.serializer(),
) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Render(props: TimePickerProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val state = rememberTimePickerState(
            initialHour = props.initialHour.coerceIn(0, 23),
            initialMinute = props.initialMinute.coerceIn(0, 59),
            is24Hour = props.is24Hour,
        )
        LaunchedEffect(state) {
            snapshotFlow { state.hour to state.minute }.collect { (h, m) ->
                onEvent(
                    ComponentEvent.TextChanged(
                        sourceId = props.id,
                        value = "${pad2(h)}:${pad2(m)}",
                    ),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (props.label.isNotBlank()) {
                Text(
                    text = props.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            TimePicker(state = state)
        }
    }
}

// ============================================================================
// DateRangePicker
// ============================================================================

@Serializable
public data class DateRangePickerProps(
    val id: String,
    val label: String = "",
)

public class DateRangePickerComponent : WeftComponent<DateRangePickerProps>(
    name = "DateRangePicker",
    description = "Inline calendar for selecting a start AND end date. Required: id. Fires TextChanged with 'YYYY-MM-DD to YYYY-MM-DD' once both ends are picked.",
    category = ComponentCategory.INPUT,
    propsSerializer = DateRangePickerProps.serializer(),
    layoutNotes = "Needs ~360dp horizontal — render at top level or in a Card with padding='none'. Same constraint as DatePicker.",
    example = """{"type": "DateRangePicker", "props": {"id": "trip_dates", "label": "Trip dates"}}""",
) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Render(props: DateRangePickerProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val state = rememberDateRangePickerState()
        LaunchedEffect(state) {
            snapshotFlow { state.selectedStartDateMillis to state.selectedEndDateMillis }
                .collect { (start, end) ->
                    if (start != null && end != null) {
                        val startIso = isoDateFromEpochMs(start)
                        val endIso = isoDateFromEpochMs(end)
                        onEvent(
                            ComponentEvent.TextChanged(
                                sourceId = props.id,
                                value = "$startIso to $endIso",
                            ),
                        )
                    }
                }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (props.label.isNotBlank()) {
                Text(
                    text = props.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            DateRangePicker(state = state, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ============================================================================
// SegmentedButton
// ============================================================================

@Serializable
public data class SegmentedButtonProps(
    val id: String,
    val options: List<String>,
    val initial: String = "",
    val label: String = "",
)

public class SegmentedButtonComponent : WeftComponent<SegmentedButtonProps>(
    name = "SegmentedButton",
    description = "A connected row of mutually-exclusive selection buttons (single-choice). Required: id, options. Optional: initial, label. Fires TextChanged with the selected option's text on every change.",
    category = ComponentCategory.INPUT,
    propsSerializer = SegmentedButtonProps.serializer(),
    example = """{"type": "SegmentedButton", "props": {"id": "view_mode", "options": ["Today", "Week", "Month"], "initial": "Week"}}""",
) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Render(props: SegmentedButtonProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        var selected by remember(props.options) {
            mutableStateOf(props.initial.ifBlank { props.options.firstOrNull().orEmpty() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (props.label.isNotBlank()) {
                Text(props.label, style = MaterialTheme.typography.labelMedium)
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                props.options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = selected == option,
                        onClick = {
                            selected = option
                            onEvent(ComponentEvent.TextChanged(sourceId = props.id, value = option))
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = props.options.size),
                        label = { Text(option) },
                    )
                }
            }
        }
    }
}

/** All [ComponentCategory.INPUT] components shipped with the substrate. */
public val InputComponents: List<WeftComponent<*>> = listOf(
    TextFieldComponent(),
    SwitchComponent(),
    CheckboxComponent(),
    RadioGroupComponent(),
    SliderComponent(),
    RangeSliderComponent(),
    DatePickerComponent(),
    TimePickerComponent(),
    DateRangePickerComponent(),
    SegmentedButtonComponent(),
)

// ----- KMP helpers -----------------------------------------------------------
// commonMain has no String.format / java.time, so we hand-roll the tiny
// formatting + ISO-date helpers the date/time pickers need.

/** "12.34" — 2-decimal float without depending on JVM's `String.format`. */
private fun formatFloat2(value: Float): String {
    val scaled = kotlin.math.round(value * 100f).toInt()
    val sign = if (scaled < 0) "-" else ""
    val abs = kotlin.math.abs(scaled)
    val whole = abs / 100
    val frac = abs % 100
    val fracStr = if (frac < 10) "0$frac" else frac.toString()
    return "$sign$whole.$fracStr"
}

/** "07", "12" — zero-padded two-digit int (clamped to 0..99). */
private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

/** "2026-05-29" — ISO local-date for the device's default time zone. */
@OptIn(ExperimentalTime::class)
private fun isoDateFromEpochMs(epochMs: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    // .monthNumber is deprecated; .month.number isn't available in
    // kotlinx-datetime 0.7.1, so we hand-derive via the enum ordinal.
    return "${local.year}-${pad2(local.month.ordinal + 1)}-${pad2(local.day)}"
}
