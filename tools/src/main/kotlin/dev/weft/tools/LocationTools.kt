package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.Permission
import kotlinx.serialization.Serializable

/**
 * Get a fresh location fix. Times out at 5s; returns hasFix=false when
 * the user denied permission, location services are off, or no fix
 * arrived in time. Requires LOCATION at runtime.
 */
class LocationCurrentTool(ctx: WeftContext) :
    WeftTool<LocationCurrentTool.Args, LocationCurrentTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "location_current",
            description = "Get the device's current location. Returns latitude/longitude with " +
                "accuracy in meters. Use for 'find <thing> near me', 'remind me when I'm at <place>', " +
                "weather-by-current-location. Returns hasFix=false if permission denied, services " +
                "off, or no fix within 5s.",
            // Required placeholder String — Anthropic emits "" for empty
            // or Boolean-only schemas, which crashes JSON parsing. A
            // required String forces the model to emit a real value.
            // See SystemInfoTools.kt for the full rationale.
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "context",
                    "Why you're calling this tool, e.g. 'user asked'. Any short string; ignored by the tool.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
        requiredPermissions = setOf(Permission.LOCATION),
    ) {

    @Serializable
    data class Args(val context: String = "")

    @Serializable
    data class Result(
        val hasFix: Boolean,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracyMeters: Float? = null,
        val timestampEpochMs: Long? = null,
    )

    override suspend fun executeWeft(args: Args): Result {
        val fix = os.location.current() ?: return Result(hasFix = false)
        return Result(
            hasFix = true,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            timestampEpochMs = fix.timestampEpochMs,
        )
    }
}

/**
 * Forward geocoding: address text → coordinates + structured address.
 * No location permission required. Empty list on no matches.
 */
class LocationGeocodeTool(ctx: WeftContext) :
    WeftTool<LocationGeocodeTool.Args, LocationGeocodeTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "location_geocode",
            description = "Convert an address or place name to coordinates. Returns up to maxResults " +
                "candidate matches with latitude/longitude and structured fields (locality, region, " +
                "country, postalCode). Empty list when no matches found.",
            requiredParameters = listOf(
                ToolParameterDescriptor("query", "Address or place name to look up.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("maxResults", "Maximum candidates to return (default 5).", ToolParameterType.Integer),
            ),
        ),
    ) {

    @Serializable
    data class Args(val query: String, val maxResults: Int = DEFAULT_MAX)

    @Serializable
    data class Place(
        val latitude: Double,
        val longitude: Double,
        val addressLine: String? = null,
        val locality: String? = null,
        val region: String? = null,
        val country: String? = null,
        val postalCode: String? = null,
    )

    @Serializable
    data class Result(val places: List<Place>)

    override suspend fun executeWeft(args: Args): Result {
        val capped = args.maxResults.coerceIn(1, MAX_ALLOWED)
        val out = os.location.geocode(args.query, capped).map {
            Place(
                latitude = it.latitude,
                longitude = it.longitude,
                addressLine = it.addressLine,
                locality = it.locality,
                region = it.region,
                country = it.country,
                postalCode = it.postalCode,
            )
        }
        return Result(places = out)
    }

    private companion object {
        const val DEFAULT_MAX = 5
        const val MAX_ALLOWED = 20
    }
}

/** Reverse geocoding: coordinates → structured address. */
class LocationReverseGeocodeTool(ctx: WeftContext) :
    WeftTool<LocationReverseGeocodeTool.Args, LocationGeocodeTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<LocationGeocodeTool.Result>(),
        descriptor = ToolDescriptor(
            name = "location_reverse_geocode",
            description = "Convert latitude/longitude to a human-readable address. Returns up to " +
                "maxResults candidates with structured fields. Useful for describing a location " +
                "back to the user after location_current.",
            requiredParameters = listOf(
                ToolParameterDescriptor("latitude", "Latitude in decimal degrees.", ToolParameterType.Float),
                ToolParameterDescriptor("longitude", "Longitude in decimal degrees.", ToolParameterType.Float),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("maxResults", "Maximum candidates (default 1).", ToolParameterType.Integer),
            ),
        ),
    ) {

    @Serializable
    data class Args(
        val latitude: Double,
        val longitude: Double,
        val maxResults: Int = 1,
    )

    override suspend fun executeWeft(args: Args): LocationGeocodeTool.Result {
        val out = os.location.reverseGeocode(args.latitude, args.longitude, args.maxResults.coerceIn(1, 10))
            .map {
                LocationGeocodeTool.Place(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    addressLine = it.addressLine,
                    locality = it.locality,
                    region = it.region,
                    country = it.country,
                    postalCode = it.postalCode,
                )
            }
        return LocationGeocodeTool.Result(places = out)
    }
}
