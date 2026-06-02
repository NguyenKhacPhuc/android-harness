package dev.weft.osbridge.permissions

import dev.weft.contracts.Permission
import dev.weft.contracts.PermissionState
import dev.weft.contracts.Permissions
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import kotlin.coroutines.resume

/**
 * iOS [Permissions]. [check] reads the current authorization without
 * prompting; [request] shows the system prompt only when the state is
 * still undetermined (iOS never re-prompts once decided).
 *
 * Wired today: [Permission.MICROPHONE] and [Permission.CAMERA], both via
 * `AVCaptureDevice` — the microphone domain is what voice input needs.
 * Other permissions return [PermissionState.NOT_DETERMINED] until their
 * owning capability lands; each adds a branch here when it does.
 *
 * iOS has no "denied but askable again" state — a denial is permanent
 * (the user must change it in Settings), so `Denied` / `Restricted` map
 * to [PermissionState.DENIED_FOREVER].
 *
 * Open so hosts can subclass and override individual permissions.
 */
public open class IosPermissions : Permissions {

    override suspend fun check(permission: Permission): PermissionState =
        when (permission) {
            Permission.MICROPHONE -> mediaStatus(AVMediaTypeAudio)
            Permission.CAMERA -> mediaStatus(AVMediaTypeVideo)
            else -> PermissionState.NOT_DETERMINED
        }

    override suspend fun request(permission: Permission): PermissionState =
        when (permission) {
            Permission.MICROPHONE -> requestMedia(AVMediaTypeAudio)
            Permission.CAMERA -> requestMedia(AVMediaTypeVideo)
            else -> PermissionState.NOT_DETERMINED
        }

    private fun mediaStatus(mediaType: String?): PermissionState =
        when (AVCaptureDevice.authorizationStatusForMediaType(mediaType)) {
            AVAuthorizationStatusAuthorized -> PermissionState.GRANTED
            AVAuthorizationStatusNotDetermined -> PermissionState.NOT_DETERMINED
            AVAuthorizationStatusDenied -> PermissionState.DENIED_FOREVER
            AVAuthorizationStatusRestricted -> PermissionState.DENIED_FOREVER
            else -> PermissionState.NOT_DETERMINED
        }

    private suspend fun requestMedia(mediaType: String?): PermissionState {
        val current = mediaStatus(mediaType)
        if (current != PermissionState.NOT_DETERMINED) return current
        return suspendCancellableCoroutine { cont ->
            AVCaptureDevice.requestAccessForMediaType(mediaType) { granted ->
                cont.resume(if (granted) PermissionState.GRANTED else PermissionState.DENIED_FOREVER)
            }
        }
    }
}
