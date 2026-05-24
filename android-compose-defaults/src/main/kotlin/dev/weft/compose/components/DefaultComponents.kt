package dev.weft.compose.components
import dev.weft.contracts.ComponentCategory

import coil.ImageLoader

/**
 * The full substrate component palette, merged across every
 * [ComponentCategory]. The only entry point [WeftRuntime] needs;
 * apps add their own via the `extraComponents` constructor arg.
 *
 * Categories the substrate ships out of the box:
 *   - **Display** (7): Text, Icon, Image, Badge, Alert, Divider, ProgressBar
 *   - **Layout** (4): Column, Row, Card, Spacer
 *   - **Action** (6): Button, IconButton, Fab, Chip, ListItem, Countdown
 *   - **Input** (10): TextField, Switch, Checkbox, RadioGroup, Slider,
 *     RangeSlider, DatePicker, TimePicker, DateRangePicker, SegmentedButton
 *   - **Macro** (5): Timer, Stopwatch, Form, Picker, DateCountdown
 *   - **Embed** (1): WebView
 *
 * @param imageLoader Coil [ImageLoader] used by the Image (and any
 *   other) component that loads remote bitmaps. Built by
 *   `WeftRuntime` via `buildWeftImageLoader`.
 */
public fun defaultWeftComponents(imageLoader: ImageLoader): List<WeftComponent<*>> =
    LayoutComponents +
        displayComponents(imageLoader) +
        ActionComponents +
        InputComponents +
        MacroComponents +
        EmbedComponents
