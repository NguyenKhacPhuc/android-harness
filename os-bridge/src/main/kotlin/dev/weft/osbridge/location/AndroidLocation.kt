package dev.weft.osbridge.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.weft.contracts.GeoResult
import dev.weft.contracts.Location
import dev.weft.contracts.LocationFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android implementation of [Location].
 *
 * **current()**: uses [com.google.android.gms.location.FusedLocationProviderClient]'s
 * `getCurrentLocation` which forces a fresh fix (rather than returning a
 * stale cached one). Times out at 5s; returns null if permission isn't
 * granted, location services are off, or no fix lands in time.
 *
 * **geocode() / reverseGeocode()**: use the platform [Geocoder]. On
 * Android 13+ (API 33+) we use the new async `getFromLocationName` /
 * `getFromLocation` overloads; on older devices we fall back to the
 * synchronous calls on Dispatchers.IO. The legacy calls may go to a
 * network service the OS owns; modern devices ship an on-device geocoder.
 */
public class AndroidLocation(context: Context) : Location {

    private val appContext: Context = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)
    private val geocoder by lazy { Geocoder(appContext, Locale.getDefault()) }

    override suspend fun current(): LocationFix? {
        if (!hasLocationPermission()) return null

        // 5s upper bound — beyond that, the call is probably going to be
        // useless (GPS cold-start scenarios are rare for an assistant
        // running in the user's hand). Return null instead of hanging.
        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<LocationFix?> { cont ->
                val req = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(MAX_FIX_AGE_MS)
                    .build()
                val task = fused.getCurrentLocation(req, /* cancellationToken = */ null)
                task.addOnSuccessListener { loc ->
                    if (!cont.isActive) return@addOnSuccessListener
                    cont.resume(
                        loc?.let {
                            LocationFix(
                                latitude = it.latitude,
                                longitude = it.longitude,
                                accuracyMeters = if (it.hasAccuracy()) it.accuracy else null,
                                altitudeMeters = if (it.hasAltitude()) it.altitude else null,
                                timestampEpochMs = it.time,
                            )
                        },
                    )
                }
                task.addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    override suspend fun geocode(query: String, maxResults: Int): List<GeoResult> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<GeoResult>> { cont ->
                    geocoder.getFromLocationName(query, maxResults) { list ->
                        if (cont.isActive) cont.resume(list.toGeoResults())
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, maxResults).orEmpty().toGeoResults()
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun reverseGeocode(latitude: Double, longitude: Double, maxResults: Int): List<GeoResult> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<GeoResult>> { cont ->
                    geocoder.getFromLocation(latitude, longitude, maxResults) { list ->
                        if (cont.isActive) cont.resume(list.toGeoResults())
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, maxResults).orEmpty().toGeoResults()
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun List<android.location.Address>.toGeoResults(): List<GeoResult> = map { a ->
        GeoResult(
            latitude = a.latitude,
            longitude = a.longitude,
            addressLine = (0..a.maxAddressLineIndex.coerceAtLeast(0))
                .joinToString(", ") { i -> a.getAddressLine(i).orEmpty() }
                .takeIf { it.isNotBlank() },
            locality = a.locality,
            region = a.adminArea,
            country = a.countryName,
            postalCode = a.postalCode,
        )
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 5_000L
        // Accept a cached fix up to 30s old — fresh enough for "where am I"
        // assistant queries, avoids burning battery for a cold GPS fix.
        const val MAX_FIX_AGE_MS = 30_000L
    }
}
