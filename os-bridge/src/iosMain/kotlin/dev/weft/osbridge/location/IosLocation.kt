package dev.weft.osbridge.location

import dev.weft.contracts.GeoResult
import dev.weft.contracts.Location
import dev.weft.contracts.LocationFix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.Foundation.timeIntervalSince1970
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLPlacemark
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS [Location] via CoreLocation. [current] drives a one-shot
 * `CLLocationManager.requestLocation()` through a retained delegate,
 * bounded at ~5s; [geocode] / [reverseGeocode] bridge `CLGeocoder`'s
 * completion handlers. All three return null/empty on denial, timeout,
 * or failure rather than throwing.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosLocation : Location {

    override suspend fun current(): LocationFix? = withContext(Dispatchers.Main) {
        // Hold the manager + delegate in locals captured by the coroutine so
        // CoreLocation doesn't deallocate them before the callback fires.
        var manager: CLLocationManager? = null
        var delegate: CLLocationManagerDelegateProtocol? = null
        withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<LocationFix?> { cont ->
                val mgr = CLLocationManager()
                val del = object : NSObject(), CLLocationManagerDelegateProtocol {
                    override fun locationManager(
                        manager: CLLocationManager,
                        didUpdateLocations: List<*>,
                    ) {
                        if (!cont.isActive) return
                        val first = didUpdateLocations.firstOrNull() as? CLLocation
                        cont.resume(first?.toFix())
                    }

                    override fun locationManager(
                        manager: CLLocationManager,
                        didFailWithError: NSError,
                    ) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                manager = mgr
                delegate = del
                mgr.delegate = del
                mgr.requestWhenInUseAuthorization()
                mgr.requestLocation()
            }
        }.also {
            // Drop references now the operation is done.
            manager?.delegate = null
            manager = null
            delegate = null
        }
    }

    override suspend fun geocode(query: String, maxResults: Int): List<GeoResult> {
        if (query.isBlank()) return emptyList()
        return suspendCancellableCoroutine { cont ->
            CLGeocoder().geocodeAddressString(query) { placemarks, _ ->
                if (cont.isActive) cont.resume(placemarks.toGeoResults(maxResults))
            }
        }
    }

    override suspend fun reverseGeocode(latitude: Double, longitude: Double, maxResults: Int): List<GeoResult> {
        return suspendCancellableCoroutine { cont ->
            val location = CLLocation(latitude = latitude, longitude = longitude)
            CLGeocoder().reverseGeocodeLocation(location) { placemarks, _ ->
                if (cont.isActive) cont.resume(placemarks.toGeoResults(maxResults))
            }
        }
    }

    private fun CLLocation.toFix(): LocationFix {
        val acc = horizontalAccuracy.takeIf { it >= 0 }?.toFloat()
        val alt = altitude
        val ts = (timestamp.timeIntervalSince1970 * MS_PER_SECOND).toLong()
        return coordinate.useContents {
            LocationFix(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = acc,
                altitudeMeters = alt,
                timestampEpochMs = ts,
            )
        }
    }

    private fun List<*>?.toGeoResults(maxResults: Int): List<GeoResult> =
        this.orEmpty()
            .filterIsInstance<CLPlacemark>()
            .take(maxResults.coerceAtLeast(0))
            .map { it.toGeoResult() }

    private fun CLPlacemark.toGeoResult(): GeoResult {
        val coord = location?.coordinate
        val (lat, lng) = coord?.useContents { latitude to longitude } ?: (0.0 to 0.0)
        val line = listOfNotNull(name ?: thoroughfare, locality, administrativeArea, postalCode, country)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
        return GeoResult(
            latitude = lat,
            longitude = lng,
            addressLine = line,
            locality = locality,
            region = administrativeArea,
            country = country,
            postalCode = postalCode,
        )
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 5_000L
        const val MS_PER_SECOND = 1_000.0
    }
}
