package dev.weft.osbridge.location

import dev.weft.contracts.GeoResult
import dev.weft.contracts.Location
import dev.weft.contracts.LocationFix

/**
 * iOS stub for [Location]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `CoreLocation` —
 * `CLLocationManager.requestLocation()` (one-shot) feeding a
 * `CLLocationManagerDelegate` for [current];
 * `CLGeocoder.geocodeAddressString(_:completionHandler:)` for [geocode]
 * and `CLGeocoder.reverseGeocodeLocation(_:completionHandler:)` for
 * [reverseGeocode]. Both geocoders are network-backed on iOS.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosLocation : Location {
    override suspend fun current(): LocationFix? =
        TODO("IosLocation.current — wrap CLLocationManager.requestLocation() with a CLLocationManagerDelegate")

    override suspend fun geocode(query: String, maxResults: Int): List<GeoResult> =
        TODO("IosLocation.geocode — wrap CLGeocoder.geocodeAddressString(_:completionHandler:)")

    override suspend fun reverseGeocode(latitude: Double, longitude: Double, maxResults: Int): List<GeoResult> =
        TODO("IosLocation.reverseGeocode — wrap CLGeocoder.reverseGeocodeLocation(_:completionHandler:)")
}
