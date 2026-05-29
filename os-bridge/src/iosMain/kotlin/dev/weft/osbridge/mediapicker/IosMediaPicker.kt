package dev.weft.osbridge.mediapicker

import dev.weft.contracts.MediaPicker
import dev.weft.contracts.MediaPickerKind

/**
 * iOS stub for [MediaPicker]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `PhotosUI.PHPickerViewController` configured with
 * `PHPickerConfiguration` (`selectionLimit = maxItems`,
 * `filter = .images / .videos / .any(of: [.images, .videos])`), presented
 * from the foreground `UIViewController` and resolved through a
 * `PHPickerViewControllerDelegate`. No `Photos` framework permission
 * required — the picker mediates access. Persist selected
 * `PHPickerResult.itemProvider` payloads to the Caches directory and
 * return their file URIs.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosMediaPicker : MediaPicker {
    override suspend fun pick(kind: MediaPickerKind, maxItems: Int): List<String> =
        TODO("IosMediaPicker.pick — wrap PHPickerViewController(configuration:) presented from the foreground UIViewController")
}
