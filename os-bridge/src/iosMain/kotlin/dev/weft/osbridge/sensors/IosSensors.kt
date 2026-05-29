package dev.weft.osbridge.sensors

import dev.weft.contracts.Sensors

/**
 * iOS stub for [Sensors]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `CoreMotion.CMPedometer` —
 * `queryPedometerData(from:to:withHandler:)` with start-of-day for
 * stepsToday (no equivalent of Android's TYPE_STEP_COUNTER baseline
 * trick needed — CMPedometer reports daily-bucketed counts directly).
 * iOS has no public ambient light sensor API for third-party apps;
 * [ambientLightLux] should return null. Closest analogue is
 * `UIScreen.main.brightness` which reflects user setting, not lux.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosSensors : Sensors {
    override suspend fun stepsToday(): Int? =
        TODO("IosSensors.stepsToday — wrap CMPedometer.queryPedometerData(from: startOfDay, to: Date(), withHandler:)")

    override suspend fun ambientLightLux(): Float? =
        TODO("IosSensors.ambientLightLux — no public iOS ambient-light API; return null")
}
