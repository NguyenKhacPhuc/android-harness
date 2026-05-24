package dev.weft.compose.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared substrate tokens — spacing scale + icon name → ImageVector map.
 *
 * Components reference these instead of raw `.dp` values or raw M3 icon
 * imports so the LLM can use a small consistent vocabulary
 * (`spacing: "md"`, `icon: "refresh"`) and we can swap implementations
 * without touching every component.
 */

/**
 * Spacing scale token → [Dp]. Used by all layout / padding props.
 * Unknown tokens fall back to `md` (12dp).
 */
internal fun spacingDp(token: String): Dp = when (token.lowercase()) {
    "none" -> 0.dp
    "xs" -> 4.dp
    "sm" -> 8.dp
    "md" -> 12.dp
    "lg" -> 16.dp
    "xl" -> 24.dp
    "xxl" -> 32.dp
    else -> 12.dp
}

/**
 * Weft-curated icon vocabulary. Components that accept an icon
 * name as a string prop (Icon, IconButton, Fab, Chip…) all resolve
 * through this map. Unknown names fall back to `info`.
 *
 * To add a new icon: add it here once; every component that uses
 * named icons picks it up automatically.
 */
internal fun lookupIcon(name: String): ImageVector = when (name.lowercase()) {
    "add" -> Icons.Filled.Add
    "arrow_back" -> Icons.AutoMirrored.Filled.ArrowBack
    "check" -> Icons.Filled.Check
    "close" -> Icons.Filled.Close
    "delete" -> Icons.Filled.Delete
    "done" -> Icons.Filled.Done
    "info" -> Icons.Filled.Info
    "refresh" -> Icons.Filled.Refresh
    "settings" -> Icons.Filled.Settings
    "star" -> Icons.Filled.Star
    "warning" -> Icons.Filled.Warning
    else -> Icons.Filled.Info
}
