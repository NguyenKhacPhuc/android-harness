package dev.weft.osbridge.sensors

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.weft.contracts.Sensors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * Android implementation of [Sensors]. Reads cumulative step counter
 * (TYPE_STEP_COUNTER, monotonic across reboots until reboot itself
 * resets it to 0) and one-shot ambient light.
 *
 * **Step counter baseline.** We don't get a "steps today" reading from
 * the framework. The sensor gives us cumulative steps since the last
 * reboot. To synthesize today's count we:
 *
 *   1. Register the sensor and grab one reading.
 *   2. Persist that reading paired with today's date as a baseline,
 *      unless one already exists for today.
 *   3. Return `currentCumulative - todaysBaseline`.
 *
 * Reboots zero the cumulative count. We detect this by storing the
 * last-seen cumulative value; if today's reading is less than the
 * previous one we treat it as a reboot and reset the baseline to 0
 * (a conservative undercount that recovers within a day).
 */
public class AndroidSensors(context: Context) : Sensors {
    private val appContext: Context = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun stepsToday(): Int? {
        val mgr = sensorManager ?: return null
        val sensor = mgr.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val cumulative = readOnce(mgr, sensor)?.toLong() ?: return null

        val todayKey = todayDateKey()
        val storedDate = prefs.getString(KEY_BASELINE_DATE, null)
        val storedBaseline = prefs.getLong(KEY_BASELINE_VALUE, -1L)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, -1L)

        // Reboot detection: cumulative dropped → reset baseline.
        val rebooted = lastSeen >= 0 && cumulative < lastSeen

        val baseline = when {
            rebooted -> 0L.also {
                prefs.edit()
                    .putString(KEY_BASELINE_DATE, todayKey)
                    .putLong(KEY_BASELINE_VALUE, 0L)
                    .apply()
            }
            storedDate != todayKey -> cumulative.also {
                prefs.edit()
                    .putString(KEY_BASELINE_DATE, todayKey)
                    .putLong(KEY_BASELINE_VALUE, it)
                    .apply()
            }
            else -> storedBaseline
        }
        prefs.edit().putLong(KEY_LAST_SEEN, cumulative).apply()

        return (cumulative - baseline).coerceAtLeast(0L).toInt()
    }

    override suspend fun ambientLightLux(): Float? {
        val mgr = sensorManager ?: return null
        val sensor = mgr.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return null
        return readOnce(mgr, sensor)
    }

    private suspend fun readOnce(mgr: SensorManager, sensor: Sensor): Float? {
        val deferred = CompletableDeferred<Float?>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val v = event?.values?.firstOrNull()
                if (v != null && !deferred.isCompleted) deferred.complete(v)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val registered = mgr.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        if (!registered) return null
        return try {
            withTimeoutOrNull(READ_TIMEOUT_MS) { deferred.await() }
        } finally {
            runCatching { mgr.unregisterListener(listener) }
        }
    }

    private fun todayDateKey(): String {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(year, month, day)
    }

    private companion object {
        const val PREFS_NAME = "weft_sensors"
        const val KEY_BASELINE_DATE = "step_baseline_date"
        const val KEY_BASELINE_VALUE = "step_baseline_value"
        const val KEY_LAST_SEEN = "step_last_seen"
        const val READ_TIMEOUT_MS = 1_500L
    }
}
