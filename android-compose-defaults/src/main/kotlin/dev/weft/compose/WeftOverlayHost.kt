package dev.weft.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.weft.contracts.OverlayKind

/**
 * Weft's default Compose renderer for ephemeral overlays (toast,
 * banner). Place at the root of your composition tree (typically a
 * `Box` wrapping the rest of the app so banners can overlay).
 *
 * Apps that want bespoke overlay styling skip this and render against
 * [ComposeUiBridge.currentOverlay] themselves — same escape hatch as
 * [PendingRequestRenderer].
 *
 * Lifecycle:
 *   - **Toast**: shown via M3 `SnackbarHostState`, auto-dismisses after
 *     `OverlaySpec.durationMs` (defaults to 3s). When the snackbar
 *     disappears, [ComposeUiBridge.dismissOverlay] is called automatically.
 *   - **Banner**: rendered as a persistent Card at the top of the screen
 *     until the user taps Dismiss.
 *   - **Dialog / Sheet**: not handled here (Dialog is handled by
 *     [PendingRequestRenderer]; Sheet is deferred).
 */
@Composable
public fun WeftOverlayHost(uiBridge: ComposeUiBridge) {
    val overlay = uiBridge.currentOverlay
    val snackbarHostState = remember { SnackbarHostState() }

    // Drive the snackbar from overlay state. When the overlay changes (or
    // becomes null), the LaunchedEffect re-keys and shows/dismisses.
    LaunchedEffect(overlay) {
        if (overlay == null) return@LaunchedEffect
        if (overlay.kind != OverlayKind.TOAST) return@LaunchedEffect
        val effectiveDuration = if (overlay.durationMs > 0) overlay.durationMs else 3000L
        val durationToken = when {
            effectiveDuration <= 4_000L -> SnackbarDuration.Short
            effectiveDuration <= 10_000L -> SnackbarDuration.Long
            else -> SnackbarDuration.Indefinite
        }
        snackbarHostState.showSnackbar(
            message = overlay.title,
            duration = durationToken,
        )
        // Snackbar dismissed (auto or by swipe) → clear bridge state.
        uiBridge.dismissOverlay()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Banner sits at the top of the screen, on top of everything.
        if (overlay?.kind == OverlayKind.BANNER) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = overlay.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!overlay.body.isNullOrBlank()) {
                            Text(
                                text = overlay.body!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { uiBridge.dismissOverlay() }) { Text("Dismiss") }
                }
            }
        }

        // Snackbar host is anchored at the bottom (M3 default).
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
            snackbar = { data ->
                Snackbar(snackbarData = data)
            },
        )
    }
}
