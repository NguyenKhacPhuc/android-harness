@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.weft.android

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIDevice
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceIdiomPhone
import kotlin.time.Clock

/**
 * Fresh device snapshot the substrate injects as a per-turn prefix on
 * the user message — iOS variant. Saves the LLM from having to ask
 * "what time is it?" or "what's your timezone?" — that information is
 * in every turn's context.
 *
 * Anything that changes during a session (current time, locale,
 * appearance mode) belongs here; static-once values are fine too. The
 * cost is small: ~10 lines per turn.
 *
 * Lives in the user message (not the system prompt) so the substrate's
 * system layer stays cacheable when Koog gains Anthropic cache_control.
 *
 * Matches the LLM-facing format of the Android [deviceSnapshot] so
 * cross-platform prompt tuning stays consistent. The only fields that
 * differ in *value* are `device` (UIDevice instead of Build.MODEL),
 * `os` (iOS instead of Android), and `device-class` (mapped from
 * UIUserInterfaceIdiom).
 */
public fun iosDeviceSnapshot(): String {
    val now = Clock.System.now()
    val zone = TimeZone.currentSystemDefault()
    // kotlinx-datetime's LocalDateTime.toString() emits ISO without
    // offset; the zone is reported separately on the next line.
    val nowLocal = now.toLocalDateTime(zone).toString()
    val locale = NSLocale.currentLocale.localeIdentifier
    val device = UIDevice.currentDevice
    val deviceClass = inferDeviceClass()
    val appVersion = NSBundle.mainBundle.infoDictionary
        ?.get("CFBundleShortVersionString") as? String
        ?: "unknown"

    return buildString {
        appendLine("# Device context (refreshed each turn)")
        appendLine("- now: $nowLocal")
        appendLine("- timezone: ${zone.id}")
        appendLine("- locale: ${locale.replace('_', '-')}")
        appendLine("- device: Apple ${device.model}")
        appendLine("- device-class: $deviceClass")
        appendLine("- os: ${device.systemName} ${device.systemVersion}")
        appendLine("- app-version: $appVersion")
    }
}

private fun inferDeviceClass(): String =
    when (UIDevice.currentDevice.userInterfaceIdiom) {
        UIUserInterfaceIdiomPad -> "tablet"
        UIUserInterfaceIdiomPhone -> "phone"
        else -> "unknown"
    }
