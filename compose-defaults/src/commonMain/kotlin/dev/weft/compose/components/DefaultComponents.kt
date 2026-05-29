package dev.weft.compose.components
import dev.weft.contracts.ComponentCategory

import coil3.ImageLoader

/**
 * The full substrate component palette, merged across every
 * [ComponentCategory]. The only entry point a Weft host needs;
 * apps add their own via the `extraComponents` constructor arg.
 *
 * Categories the substrate ships out of the box:
 *   - **Display** (7): Text, Icon, Image, Badge, Alert, Divider, ProgressBar
 *   - **Layout** (4): Column, Row, Card, Spacer
 *   - **Action** (6): Button, IconButton, Fab, Chip, ListItem, Countdown
 *   - **Input** (10): TextField, Switch, Checkbox, RadioGroup, Slider,
 *     RangeSlider, DatePicker, TimePicker, DateRangePicker, SegmentedButton
 *   - **Macro** (5): Timer, Stopwatch, Form, Picker, DateCountdown
 *   - **Embed** (2 on Android, 0 on iOS): WebView, Html — backed by
 *     `android.webkit.WebView`. iOS leaves the embed slot empty until
 *     a WKWebView wrapper ships.
 *
 * @param imageLoader Coil 3 [ImageLoader] used by the Image (and any
 *   other) component that loads remote bitmaps. Built by the host via
 *   `buildWeftImageLoader`.
 */
public fun defaultWeftComponents(imageLoader: ImageLoader): List<WeftComponent<*>> =
    LayoutComponents +
        displayComponents(imageLoader) +
        ActionComponents +
        InputComponents +
        MacroComponents +
        EmbedComponents

/**
 * The substrate's embed-category palette. Android supplies
 * [WebViewComponent] + [HtmlComponent] backed by `android.webkit.WebView`;
 * iOS supplies an empty list until a WKWebView wrapper lands. Hosts that
 * need rich-text rendering on iOS can register their own component (e.g.
 * markdown-rendered Text) via `WeftUi.extraComponents`.
 */
public expect val EmbedComponents: List<WeftComponent<*>>
