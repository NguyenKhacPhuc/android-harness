package dev.weft.android

import android.content.Context
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fresh device snapshot the substrate injects as a per-turn prefix on the
 * user message. Saves the LLM from having to ask "what time is it?" or
 * "what's your timezone?" — that information is in every turn's context.
 *
 * Anything that changes during a session (current time, network status,
 * battery) belongs here; static-once values are fine too. The cost is
 * small: ~10 lines per turn.
 *
 * Lives in the user message (not the system prompt) so the substrate's
 * system layer stays cacheable when Koog gains Anthropic cache_control.
 */
public fun deviceSnapshot(context: Context): String {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val nowLocal = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))
    val locale = Locale.getDefault()
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    val deviceClass = inferDeviceClass(context)

    return buildString {
        appendLine("# Device context (refreshed each turn)")
        appendLine("- now: $nowLocal")
        appendLine("- timezone: ${zone.id}")
        appendLine("- locale: ${locale.toLanguageTag()}")
        appendLine("- device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("- device-class: $deviceClass")
        // Available screen size in density-independent pixels (CSS px ≈ dp) —
        // lets a rendered HTML mini-app size itself responsively to the surface.
        appendLine(
            "- screen-dp: ${context.resources.configuration.screenWidthDp}" +
                "x${context.resources.configuration.screenHeightDp}",
        )
        appendLine("- os: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("- app-version: $versionName")
    }
}

private fun inferDeviceClass(context: Context): String {
    val widthDp = context.resources.configuration.smallestScreenWidthDp
    return when {
        widthDp >= TABLET_WIDTH_DP -> "tablet"
        widthDp >= 0 -> "phone"
        else -> "unknown"
    }
}

private const val TABLET_WIDTH_DP = 600
