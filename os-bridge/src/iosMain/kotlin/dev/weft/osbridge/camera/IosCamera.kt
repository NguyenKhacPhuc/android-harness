package dev.weft.osbridge.camera

import dev.weft.contracts.Camera
import dev.weft.contracts.FileRef

/**
 * iOS stub for [Camera]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIKit.UIImagePickerController` configured with
 * `sourceType = .camera`, presented from the foreground
 * `UIViewController` and resolved through a
 * `UIImagePickerControllerDelegate`. Write the resulting `UIImage` /
 * `info[.imageURL]` to the app Caches directory and return a [FileRef].
 * For a more modern surface, `AVFoundation.AVCaptureSession` is the
 * Camera2/CameraX equivalent. Requires `NSCameraUsageDescription`.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosCamera : Camera {
    override suspend fun captureImage(namePrefix: String): FileRef? =
        TODO("IosCamera.captureImage — wrap UIImagePickerController(sourceType: .camera) presented from the foreground UIViewController")
}
