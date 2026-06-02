package dev.weft.osbridge.haptics

import dev.weft.contracts.HapticEffect
import dev.weft.contracts.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

/**
 * iOS [Haptics] via the `UIFeedbackGenerator` family. Impact styles
 * cover the click effects, selection feedback the double-click, and
 * notification feedback the success / warning / error effects.
 *
 * Feedback generators are main-thread UIKit objects, so `perform` runs
 * on the main dispatcher. Devices without a Taptic Engine simply
 * produce nothing.
 *
 * Open so hosts can subclass and override individual methods.
 */
public open class IosHaptics : Haptics {

    override suspend fun perform(effect: HapticEffect): Unit = withContext(Dispatchers.Main) {
        when (effect) {
            HapticEffect.TICK -> impact(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
            HapticEffect.CLICK -> impact(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
            HapticEffect.HEAVY_CLICK -> impact(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
            HapticEffect.DOUBLE_CLICK -> UISelectionFeedbackGenerator().selectionChanged()
            HapticEffect.SUCCESS -> notify(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            HapticEffect.WARNING -> notify(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
            HapticEffect.ERROR -> notify(UINotificationFeedbackType.UINotificationFeedbackTypeError)
        }
    }

    private fun impact(style: UIImpactFeedbackStyle) {
        val generator = UIImpactFeedbackGenerator(style)
        generator.prepare()
        generator.impactOccurred()
    }

    private fun notify(type: UINotificationFeedbackType) {
        val generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(type)
    }
}
