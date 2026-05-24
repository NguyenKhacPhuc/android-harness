package dev.weft.tools.context

import dev.weft.contracts.ContextProvider
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.UserContextField
import kotlinx.serialization.json.JsonObject

/**
 * Default [ContextProvider] for device-level state — time, timezone, locale,
 * device class. Implemented on top of [OsCapabilities.userContext] so it
 * benefits from any platform-specific extensions to that interface.
 *
 * Apps that want richer context (user profile, subscription tier, etc.)
 * register additional providers alongside this one.
 */
public class DeviceContextProvider(
    private val os: OsCapabilities,
    private val fields: Set<UserContextField> = DEFAULT_FIELDS,
) : ContextProvider {
    override val name: String = "device"
    override val description: String =
        "Device snapshot: current time, timezone, locale, device class. " +
            "Auto-refreshed every call."

    override suspend fun snapshot(): JsonObject = os.userContext.snapshot(fields)

    public companion object {
        public val DEFAULT_FIELDS: Set<UserContextField> = setOf(
            UserContextField.TIME,
            UserContextField.TIMEZONE,
            UserContextField.LOCALE,
            UserContextField.DEVICE_CLASS,
        )
    }
}
