package dev.weft.devtools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.weft.android.WeftRuntime
import dev.weft.devtools.internal.DevToolsSheet

/**
 * Opt-in debug overlay for a live [WeftRuntime].
 *
 * Wrap your app's root composable with this; in debug builds it adds a
 * floating action button that opens a bottom sheet with inspection tabs
 * (live tool calls, system prompt, tool catalog + playground, usage).
 *
 * ```kotlin
 * setContent {
 *     WeftDevTools(runtime = substrate, enabled = BuildConfig.DEBUG) {
 *         MyApp()
 *     }
 * }
 * ```
 *
 * **Production builds:** pass `enabled = false` (or guard with BuildConfig)
 * so the overlay isn't part of the shipped UI. The dependency itself is
 * safe to ship — it's just a Compose UI surface — but the FAB shouldn't
 * leak into production.
 *
 * @param runtime live WeftRuntime to inspect. The overlay subscribes to
 *   its `traceStore.traces`, `usageStore.totals`, etc., so it updates in
 *   real time as the agent runs.
 * @param enabled whether to render the overlay at all. Defaults to true;
 *   apps usually wire `BuildConfig.DEBUG` here.
 * @param fabPadding distance from screen edges for the FAB. Defaults to
 *   16.dp on all sides; override if your app's bottom bar would overlap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun WeftDevTools(
    runtime: WeftRuntime,
    enabled: Boolean = true,
    fabPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        if (enabled) {
            var sheetOpen by remember { mutableStateOf(false) }

            FloatingActionButton(
                onClick = { sheetOpen = true },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(fabPadding),
            ) {
                Icon(Icons.Default.Build, contentDescription = "Weft DevTools")
            }

            if (sheetOpen) {
                DevToolsSheet(
                    runtime = runtime,
                    onDismiss = { sheetOpen = false },
                )
            }
        }
    }
}
