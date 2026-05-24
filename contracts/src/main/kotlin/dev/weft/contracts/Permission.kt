package dev.weft.contracts

import kotlinx.serialization.Serializable

/**
 * Platform-agnostic permission identifier. Mapped to platform-specific
 * permissions inside :os-bridge.
 */
@Serializable
enum class Permission {
    NOTIFICATIONS,
    NOTIFICATIONS_READ,
    CALENDAR_READ,
    CALENDAR_WRITE,
    CONTACTS_READ,
    LOCATION,
    READ_MEDIA_IMAGES,
    READ_MEDIA_VIDEO,
    READ_MEDIA_AUDIO,
    CAMERA,
    MICROPHONE,
    /**
     * Android 12+ `BLUETOOTH_CONNECT` runtime permission — needed to
     * list paired devices and query their metadata. Pre-12 the legacy
     * install-time `BLUETOOTH` permission covers this; the os-bridge
     * mapper resolves the right one per SDK level.
     */
    BLUETOOTH_CONNECT,
}
