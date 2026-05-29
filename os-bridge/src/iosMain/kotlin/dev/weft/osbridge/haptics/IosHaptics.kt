package dev.weft.osbridge.haptics

import dev.weft.contracts.HapticEffect
import dev.weft.contracts.Haptics

/**
 * iOS stub for [Haptics]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIKit.UIFeedbackGenerator` family —
 * `UIImpactFeedbackGenerator(style: .light/.medium/.heavy)` for TICK /
 * CLICK / HEAVY_CLICK, `UISelectionFeedbackGenerator.selectionChanged()`
 * for DOUBLE_CLICK, `UINotificationFeedbackGenerator.notificationOccurred(.success/.warning/.error)`
 * for SUCCESS / WARNING / ERROR. For richer effects on A11+ devices,
 * `CoreHaptics.CHHapticEngine` with composed patterns.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosHaptics : Haptics {
    override suspend fun perform(effect: HapticEffect): Unit =
        TODO("IosHaptics.perform — wrap UIImpactFeedbackGenerator / UINotificationFeedbackGenerator per HapticEffect")
}
