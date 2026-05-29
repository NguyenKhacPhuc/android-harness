package dev.weft.osbridge.permissions

import dev.weft.contracts.Permission
import dev.weft.contracts.PermissionState
import dev.weft.contracts.Permissions

/**
 * iOS stub for [Permissions]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: per-permission iOS authorization APIs —
 * `CLLocationManager.authorizationStatus`, `EKEventStore.authorizationStatus(for:)`,
 * `CNContactStore.authorizationStatus(for:)`, `AVCaptureDevice.authorizationStatus(for:)`,
 * `PHPhotoLibrary.authorizationStatus(for:)`, `UNUserNotificationCenter.getNotificationSettings`,
 * and the matching request* methods. The check vs. request split maps onto
 * iOS's "status read" vs. "prompt user" pattern.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosPermissions : Permissions {
    override suspend fun check(permission: Permission): PermissionState =
        TODO("IosPermissions.check — wrap the matching iOS authorizationStatus(for:) per Permission")

    override suspend fun request(permission: Permission): PermissionState =
        TODO("IosPermissions.request — wrap the matching iOS requestAuthorization / requestAccess per Permission")
}
