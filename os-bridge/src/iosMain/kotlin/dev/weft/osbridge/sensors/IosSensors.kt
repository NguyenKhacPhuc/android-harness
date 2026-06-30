package dev.weft.osbridge.sensors

import dev.weft.contracts.Sensors
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreMotion.CMPedometer
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import kotlin.coroutines.resume

/**
 * iOS [Sensors]. [stepsToday] reads CoreMotion's `CMPedometer`, querying
 * from the start of the current day to now — iOS buckets step counts
 * itself, so no Android-style midnight-baseline trick is needed. Returns
 * null when step counting isn't available (Simulator, devices without a
 * motion coprocessor).
 *
 * [ambientLightLux] is an honest null: iOS exposes no public ambient-light
 * sensor API to third-party apps. (`UIScreen.brightness` reflects the user
 * setting, not lux, so it isn't a substitute.)
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosSensors : Sensors {

    override suspend fun stepsToday(): Int? {
        if (!CMPedometer.isStepCountingAvailable()) return null
        val now = NSDate()
        val startOfDay = NSCalendar.currentCalendar.startOfDayForDate(now)
        return suspendCancellableCoroutine { cont ->
            CMPedometer().queryPedometerDataFromDate(startOfDay, toDate = now) { data, _ ->
                if (cont.isActive) cont.resume(data?.numberOfSteps?.intValue)
            }
        }
    }

    /** Honest null — iOS has no public ambient-light sensor API. */
    override suspend fun ambientLightLux(): Float? = null
}
