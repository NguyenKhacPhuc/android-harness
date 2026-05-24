package dev.weft.compose.components
import dev.weft.contracts.ComponentEvent
import dev.weft.contracts.ComponentCategory

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import kotlinx.serialization.Serializable

/**
 * [ComponentCategory.DISPLAY] components — render content with no
 * interaction events. Stateless except for internal animations
 * (loading spinners, indeterminate progress).
 */

// ============================================================================
// Text
// ============================================================================

@Serializable
public data class TextProps(
    val text: String,
    /** One of: body, label, title, headline, display. */
    val variant: String = "body",
    /** One of: start, center, end. */
    val align: String = "start",
)

public class TextComponent : WeftComponent<TextProps>(
    name = "Text",
    description = "A piece of text. REQUIRED: `text` (string). Optional: `variant` ('body' default, 'label', 'title', 'headline', 'display'), `align` ('start' default, 'center', 'end'). For spacing/gaps use Spacer or Divider — do not emit empty Text.",
    category = ComponentCategory.DISPLAY,
    propsSerializer = TextProps.serializer(),
) {
    @Composable
    override fun Render(props: TextProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        Text(
            text = props.text,
            style = when (props.variant.lowercase()) {
                "label" -> MaterialTheme.typography.labelMedium
                "title" -> MaterialTheme.typography.titleMedium
                "headline" -> MaterialTheme.typography.headlineSmall
                "display" -> MaterialTheme.typography.displaySmall
                else -> MaterialTheme.typography.bodyMedium
            },
            textAlign = when (props.align.lowercase()) {
                "center" -> TextAlign.Center
                "end" -> TextAlign.End
                else -> TextAlign.Start
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================================
// Icon
// ============================================================================

@Serializable
public data class IconProps(
    val name: String,
    /** Token: xs (16dp), sm (20dp), md (24dp), lg (32dp), xl (48dp). */
    val size: String = "md",
)

public class IconComponent : WeftComponent<IconProps>(
    name = "Icon",
    description = "A material icon. name: one of 'add', 'arrow_back', 'check', 'close', 'delete', 'done', 'info', 'refresh', 'settings', 'star', 'warning'. size: token (default 'md').",
    category = ComponentCategory.DISPLAY,
    propsSerializer = IconProps.serializer(),
) {
    @Composable
    override fun Render(props: IconProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val sizeDp = when (props.size.lowercase()) {
            "xs" -> 16.dp
            "sm" -> 20.dp
            "lg" -> 32.dp
            "xl" -> 48.dp
            else -> 24.dp
        }
        Icon(
            painter = rememberVectorPainter(lookupIcon(props.name)),
            contentDescription = props.name,
            modifier = Modifier.size(sizeDp),
        )
    }
}

// ============================================================================
// Image
// ============================================================================

@Serializable
public data class ImageProps(
    val url: String,
    /** Token: xs (32dp), sm (64dp), md (96dp), lg (160dp), xl (240dp), fill. */
    val size: String = "md",
    /** One of: fit, crop, fill. */
    val contentScale: String = "fit",
    val contentDescription: String = "",
)

public class ImageComponent(
    private val imageLoader: ImageLoader,
) : WeftComponent<ImageProps>(
    name = "Image",
    description = "A remote or local image. Required: url. size: xs/sm/md/lg/xl/fill (default md). contentScale: fit (default), crop, fill.",
    category = ComponentCategory.DISPLAY,
    propsSerializer = ImageProps.serializer(),
) {
    @Composable
    override fun Render(props: ImageProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val scale = when (props.contentScale.lowercase()) {
            "crop" -> ContentScale.Crop
            "fill" -> ContentScale.FillBounds
            else -> ContentScale.Fit
        }
        val sizeMod = when (props.size.lowercase()) {
            "xs" -> Modifier.size(32.dp)
            "sm" -> Modifier.size(64.dp)
            "lg" -> Modifier.size(160.dp)
            "xl" -> Modifier.size(240.dp)
            "fill" -> Modifier.fillMaxWidth().height(200.dp)
            else -> Modifier.size(96.dp)
        }
        SubcomposeAsyncImage(
            model = props.url,
            imageLoader = imageLoader,
            contentDescription = props.contentDescription.ifBlank { null },
            contentScale = scale,
            modifier = sizeMod.clip(RoundedCornerShape(8.dp)),
            loading = {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            },
            error = {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "⚠ Image unavailable",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = props.url.take(60) + if (props.url.length > 60) "…" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
        )
    }
}

// ============================================================================
// Badge
// ============================================================================

@Serializable
public data class BadgeProps(
    val text: String,
    val variant: String = "default",
)

public class BadgeComponent : WeftComponent<BadgeProps>(
    name = "Badge",
    description = "A small labeled chip — for tags, counts, status indicators. Required: text. Optional: variant ('default' neutral, 'primary', 'success', 'warning', 'error').",
    category = ComponentCategory.DISPLAY,
    propsSerializer = BadgeProps.serializer(),
) {
    @Composable
    override fun Render(props: BadgeProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val (bg, fg) = badgeColors(props.variant)
        Surface(shape = RoundedCornerShape(8.dp), color = bg) {
            Text(
                text = props.text,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ============================================================================
// Alert
// ============================================================================

@Serializable
public data class AlertProps(
    val text: String,
    val title: String = "",
    val variant: String = "info",
)

public class AlertComponent : WeftComponent<AlertProps>(
    name = "Alert",
    description = "An inline banner for info/warning/error messages. Required: text. Optional: title, variant ('info' default, 'success', 'warning', 'error').",
    category = ComponentCategory.DISPLAY,
    propsSerializer = AlertProps.serializer(),
) {
    @Composable
    override fun Render(props: AlertProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val (bg, fg, icon) = alertVisuals(props.variant)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = bg,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(12.dp),
            ) {
                Icon(imageVector = icon, contentDescription = props.variant, tint = fg)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    if (props.title.isNotBlank()) {
                        Text(
                            text = props.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = fg,
                        )
                    }
                    Text(text = props.text, style = MaterialTheme.typography.bodyMedium, color = fg)
                }
            }
        }
    }
}

// ============================================================================
// Divider
// ============================================================================

@Serializable
public data class DividerProps(
    val padding: String = "none",
)

public class DividerComponent : WeftComponent<DividerProps>(
    name = "Divider",
    description = "A thin horizontal line for grouping. Optional: padding (vertical space above/below, default 'none').",
    category = ComponentCategory.DISPLAY,
    propsSerializer = DividerProps.serializer(),
) {
    @Composable
    override fun Render(props: DividerProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        HorizontalDivider(modifier = Modifier.padding(vertical = spacingDp(props.padding)))
    }
}

// ============================================================================
// ProgressBar
// ============================================================================

@Serializable
public data class ProgressBarProps(
    /** 0..1. If null, renders an indeterminate animation. */
    val value: Float? = null,
    /** "linear" (default) or "circular". */
    val variant: String = "linear",
    val label: String = "",
)

public class ProgressBarComponent : WeftComponent<ProgressBarProps>(
    name = "ProgressBar",
    description = "A progress indicator. Optional: value (0..1; null = indeterminate looping animation), variant ('linear' default, 'circular'), label.",
    category = ComponentCategory.DISPLAY,
    propsSerializer = ProgressBarProps.serializer(),
) {
    @Composable
    override fun Render(props: ProgressBarProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (props.label.isNotBlank()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(props.label, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    if (props.value != null) {
                        Text(
                            text = "${(props.value.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (props.variant.lowercase()) {
                "circular" -> {
                    if (props.value != null) CircularProgressIndicator(progress = { props.value.coerceIn(0f, 1f) })
                    else CircularProgressIndicator()
                }
                else -> {
                    if (props.value != null) {
                        LinearProgressIndicator(
                            progress = { props.value.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// ============================================================================
// Helpers — variant → colors / icon (Badge + Alert)
// ============================================================================

@Composable
private fun badgeColors(variant: String): Pair<Color, Color> = when (variant.lowercase()) {
    "primary" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    "success" -> Color(0xFFC8E6C9) to Color(0xFF1B5E20)
    "warning" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    "error" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun alertVisuals(variant: String): Triple<Color, Color, ImageVector> = when (variant.lowercase()) {
    "success" -> Triple(Color(0xFFC8E6C9), Color(0xFF1B5E20), Icons.Filled.Check)
    "warning" -> Triple(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
        Icons.Filled.Warning,
    )
    "error" -> Triple(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
        Icons.Filled.Close,
    )
    else -> Triple(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
        Icons.Filled.Info,
    )
}

/**
 * All [ComponentCategory.DISPLAY] components shipped with the substrate.
 *
 * Image needs an [ImageLoader] so this is a function.
 */
public fun displayComponents(imageLoader: ImageLoader): List<WeftComponent<*>> = listOf(
    TextComponent(),
    IconComponent(),
    ImageComponent(imageLoader),
    BadgeComponent(),
    AlertComponent(),
    DividerComponent(),
    ProgressBarComponent(),
)
