package dev.weft.compose.components
import dev.weft.contracts.ComponentEvent
import dev.weft.contracts.ComponentCategory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

/**
 * [ComponentCategory.LAYOUT] components — arrange children. No content
 * of their own; they're scaffolding for the rest of the tree.
 */

// ============================================================================
// Column — vertical stack of children
// ============================================================================

@Serializable
public data class ColumnProps(
    /** Token: none, xs, sm, md, lg, xl, xxl. */
    val spacing: String = "sm",
    val padding: String = "none",
    /** Horizontal alignment of children: start, center, end. */
    val align: String = "start",
)

public class ColumnComponent : WeftComponent<ColumnProps>(
    name = "Column",
    description = "Vertical stack of children. spacing: token between children (default 'sm'). padding: token around outside (default 'none'). align: 'start' (default), 'center', 'end' — for hero content use 'center'.",
    category = ComponentCategory.LAYOUT,
    propsSerializer = ColumnProps.serializer(),
) {
    @Composable
    override fun Render(props: ColumnProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        Column(
            verticalArrangement = Arrangement.spacedBy(spacingDp(props.spacing)),
            horizontalAlignment = when (props.align.lowercase()) {
                "center" -> Alignment.CenterHorizontally
                "end" -> Alignment.End
                else -> Alignment.Start
            },
            modifier = Modifier.padding(spacingDp(props.padding)).fillMaxWidth(),
        ) { children() }
    }
}

// ============================================================================
// Row — horizontal stack of children
// ============================================================================

@Serializable
public data class RowProps(
    val spacing: String = "sm",
    val padding: String = "none",
    /** Horizontal arrangement: start, center, end, space_between, space_evenly. */
    val align: String = "start",
)

public class RowComponent : WeftComponent<RowProps>(
    name = "Row",
    description = "Horizontal stack of children. spacing: token between children (default 'sm'). align: 'start' (default), 'center', 'end', 'space_between', 'space_evenly'.",
    category = ComponentCategory.LAYOUT,
    propsSerializer = RowProps.serializer(),
) {
    @Composable
    override fun Render(props: RowProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val arrangement = when (props.align.lowercase()) {
            "center" -> Arrangement.spacedBy(spacingDp(props.spacing), Alignment.CenterHorizontally)
            "end" -> Arrangement.spacedBy(spacingDp(props.spacing), Alignment.End)
            "space_between" -> Arrangement.SpaceBetween
            "space_evenly" -> Arrangement.SpaceEvenly
            else -> Arrangement.spacedBy(spacingDp(props.spacing))
        }
        Row(
            horizontalArrangement = arrangement,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(spacingDp(props.padding)).fillMaxWidth(),
        ) { children() }
    }
}

// ============================================================================
// Card — surface container with rounded corners + padding
// ============================================================================

@Serializable
public data class CardProps(
    /** One of: default, elevated, outlined. */
    val variant: String = "elevated",
    /** Padding inside the card. Token. */
    val padding: String = "md",
    /** Spacing between child elements. Token. */
    val spacing: String = "sm",
)

public class CardComponent : WeftComponent<CardProps>(
    name = "Card",
    description = "Container with subtle surface and rounded corners — use to group related content. variant: 'elevated' (default), 'default', 'outlined'. padding: token (default 'md'). spacing: token between children (default 'sm').",
    category = ComponentCategory.LAYOUT,
    propsSerializer = CardProps.serializer(),
) {
    @Composable
    override fun Render(props: CardProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val colors = when (props.variant.lowercase()) {
            "outlined" -> CardDefaults.outlinedCardColors()
            "default" -> CardDefaults.cardColors()
            else -> CardDefaults.elevatedCardColors()
        }
        val elevation = if (props.variant.lowercase() == "elevated") {
            CardDefaults.elevatedCardElevation()
        } else {
            CardDefaults.cardElevation()
        }
        Card(
            colors = colors,
            elevation = elevation,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(spacingDp(props.spacing)),
                modifier = Modifier.padding(spacingDp(props.padding)).fillMaxWidth(),
            ) { children() }
        }
    }
}

// ============================================================================
// Spacer — empty gap
// ============================================================================

@Serializable
public data class SpacerProps(
    val size: String = "md",
    /** "vertical" or "horizontal". */
    val direction: String = "vertical",
)

public class SpacerComponent : WeftComponent<SpacerProps>(
    name = "Spacer",
    description = "Empty space between siblings. size: token (default 'md'). direction: 'vertical' (default) or 'horizontal'.",
    category = ComponentCategory.LAYOUT,
    propsSerializer = SpacerProps.serializer(),
) {
    @Composable
    override fun Render(props: SpacerProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val dp = spacingDp(props.size)
        Spacer(
            modifier = if (props.direction.lowercase() == "horizontal") {
                Modifier.width(dp)
            } else {
                Modifier.height(dp)
            },
        )
    }
}

// ============================================================================
// Tabs — tabbed content with positional children (only selected renders)
// ============================================================================

@Serializable
public data class TabsProps(
    /** Tab labels, in order. The Nth tab corresponds to the Nth child of this node. */
    val tabs: List<String>,
    val initial: Int = 0,
)

public class TabsComponent : WeftComponent<TabsProps>(
    name = "Tabs",
    description = "Tabbed content. Required: tabs (list of tab labels). Children align POSITIONALLY — emit one child per tab (in the same order). Only the selected tab's child renders at a time. Tab switching is local — no agent round-trip per tap.",
    category = ComponentCategory.LAYOUT,
    propsSerializer = TabsProps.serializer(),
    example = """{"type": "Tabs", "props": {"tabs": ["Today", "Week", "Month"], "initial": 0}, "children": [{"type": "Text", "props": {"text": "Today's stuff"}}, {"type": "Text", "props": {"text": "This week"}}, {"type": "Text", "props": {"text": "This month"}}]}""",
) {
    @Composable
    override fun Render(props: TabsProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val node = LocalCurrentNode.current
        val renderNode = LocalNodeRenderer.current
        val tabCount = props.tabs.size.coerceAtLeast(1)
        var selected by remember(props.tabs) {
            mutableIntStateOf(props.initial.coerceIn(0, tabCount - 1))
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            TabRow(selectedTabIndex = selected.coerceIn(0, tabCount - 1)) {
                props.tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = selected == i,
                        onClick = { selected = i },
                        text = { Text(label) },
                    )
                }
            }
            val content = node.children.getOrNull(selected)
            if (content != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    renderNode(content)
                }
            } else {
                Text(
                    text = "No content for tab \"${props.tabs.getOrNull(selected).orEmpty()}\" (missing child #$selected).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

// ============================================================================
// BottomSheet — modal sheet that slides up from the bottom
// ============================================================================

@Serializable
public data class BottomSheetProps(
    val title: String = "",
    /** Action key fired when the sheet is dismissed (swipe / backdrop tap). */
    val onDismiss: String = "sheet_dismissed",
)

public class BottomSheetComponent : WeftComponent<BottomSheetProps>(
    name = "BottomSheet",
    description = "Modal bottom sheet that slides up over the screen. Renders ONE child as the sheet's content; any additional children are ignored. Fires onDismiss when the user swipes down or taps the backdrop. Use for overlays the user should focus on briefly.",
    category = ComponentCategory.LAYOUT,
    propsSerializer = BottomSheetProps.serializer(),
    example = """{"type": "BottomSheet", "props": {"title": "Quick action"}, "children": [{"type": "Picker", "props": {"title": "Choose one", "options": ["A", "B", "C"], "onPicked": "picked"}}]}""",
) {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Render(props: BottomSheetProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val node = LocalCurrentNode.current
        val renderNode = LocalNodeRenderer.current
        val sheetState = rememberModalBottomSheetState()
        var visible by remember { mutableStateOf(true) }
        if (!visible) return

        ModalBottomSheet(
            onDismissRequest = {
                visible = false
                onEvent(ComponentEvent.Action(
                    action = props.onDismiss,
                    sourceType = "BottomSheet",
                    sourceLabel = props.title.ifBlank { "sheet" },
                ))
            },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                if (props.title.isNotBlank()) {
                    Text(
                        text = props.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                val content = node.children.firstOrNull()
                if (content != null) {
                    renderNode(content)
                } else {
                    Text(
                        text = "Empty sheet — emit a child for the content.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ============================================================================
// Carousel — horizontal swipeable pager
// ============================================================================

@Serializable
public data class CarouselProps(
    val initial: Int = 0,
    /** Show dot indicators below the pager when there's more than one slide. */
    val showIndicators: Boolean = true,
    /** Height in dp for the slide area (defaults to wrap-content if 0). */
    val heightDp: Int = 0,
)

public class CarouselComponent : WeftComponent<CarouselProps>(
    name = "Carousel",
    description = "Horizontal swipeable pager. Each CHILD is a slide; swipe left/right to navigate. Optional: initial (default 0), showIndicators (default true), heightDp (0 = wrap-content).",
    category = ComponentCategory.LAYOUT,
    propsSerializer = CarouselProps.serializer(),
    example = """{"type": "Carousel", "props": {"showIndicators": true}, "children": [{"type": "Image", "props": {"url": "...", "size": "fill"}}, {"type": "Image", "props": {"url": "...", "size": "fill"}}]}""",
) {
    @Composable
    override fun Render(props: CarouselProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val node = LocalCurrentNode.current
        val renderNode = LocalNodeRenderer.current
        val pageCount = node.children.size
        if (pageCount == 0) {
            Text(
                text = "Empty carousel — emit at least one child slide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
            return
        }
        val pagerState = rememberPagerState(
            initialPage = props.initial.coerceIn(0, pageCount - 1),
        ) { pageCount }

        Column(modifier = Modifier.fillMaxWidth()) {
            val pagerModifier = if (props.heightDp > 0) {
                Modifier.fillMaxWidth().height(props.heightDp.dp)
            } else {
                Modifier.fillMaxWidth()
            }
            HorizontalPager(
                state = pagerState,
                modifier = pagerModifier,
            ) { page ->
                node.children.getOrNull(page)?.let { renderNode(it) }
            }
            if (props.showIndicators && pageCount > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    repeat(pageCount) { i ->
                        val active = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(if (active) 10.dp else 8.dp)
                                .background(
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Accordion — collapsible sections; each child = body for one section
// ============================================================================

@Serializable
public data class AccordionProps(
    /** Section header labels. Children align positionally — child N is the body of section N. */
    val sections: List<String>,
    /** If true, only one section can be expanded at a time (auto-collapses siblings). */
    val singleExpand: Boolean = false,
    /** Index of section to expand initially (-1 = none open). */
    val initial: Int = -1,
)

public class AccordionComponent : WeftComponent<AccordionProps>(
    name = "Accordion",
    description = "Collapsible sections. Required: sections (header labels). CHILDREN align positionally — emit one child per section. Optional: singleExpand (default false; if true, opening one collapses others), initial (index initially open; -1 = all closed). Expansion is local; no agent round-trip per tap.",
    category = ComponentCategory.LAYOUT,
    propsSerializer = AccordionProps.serializer(),
    example = """{"type": "Accordion", "props": {"sections": ["Details", "Reviews", "Specs"], "initial": 0}, "children": [{"type": "Text", "props": {"text": "Description here..."}}, {"type": "Text", "props": {"text": "4.5 stars"}}, {"type": "Text", "props": {"text": "Specs list"}}]}""",
) {
    @Composable
    override fun Render(props: AccordionProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val node = LocalCurrentNode.current
        val renderNode = LocalNodeRenderer.current
        val expanded = remember(props.sections) {
            mutableStateMapOf<Int, Boolean>().apply {
                if (props.initial in props.sections.indices) put(props.initial, true)
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            props.sections.forEachIndexed { i, label ->
                val isOpen = expanded[i] == true
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (props.singleExpand && !isOpen) {
                                expanded.clear()
                            }
                            expanded[i] = !isOpen
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (isOpen) "Collapse" else "Expand",
                        )
                    }
                }
                if (isOpen) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        node.children.getOrNull(i)?.let { renderNode(it) } ?: Text(
                            text = "(no content)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** All [ComponentCategory.LAYOUT] components shipped with the substrate. */
public val LayoutComponents: List<WeftComponent<*>> = listOf(
    ColumnComponent(),
    RowComponent(),
    CardComponent(),
    SpacerComponent(),
    TabsComponent(),
    BottomSheetComponent(),
    CarouselComponent(),
    AccordionComponent(),
)
