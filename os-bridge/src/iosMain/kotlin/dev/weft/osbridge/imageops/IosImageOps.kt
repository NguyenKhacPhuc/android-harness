package dev.weft.osbridge.imageops

import dev.weft.contracts.FileRef
import dev.weft.contracts.ImageOps
import dev.weft.contracts.RectPx

/**
 * iOS stub for [ImageOps]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIKit.UIImage` + `CoreGraphics.CGImage` —
 * `UIGraphicsImageRenderer` driven by a scaled draw rect for resize,
 * `CGImage.cropping(to:)` (or `UIGraphicsImageRenderer.image { ctx in
 * img.draw(at:) }` masked to the rect) for crop, and
 * `UIImage(cgImage:scale:orientation:)` with a rotated orientation /
 * `CGAffineTransform(rotationAngle:)` + render for rotate. Write
 * `UIImage.pngData()` / `jpegData(compressionQuality:)` to the Caches
 * directory and return a [FileRef].
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosImageOps : ImageOps {
    override suspend fun resize(uri: String, maxEdgePx: Int, namePrefix: String): FileRef? =
        TODO("IosImageOps.resize — wrap UIGraphicsImageRenderer drawing the source UIImage at the scaled rect")

    override suspend fun crop(uri: String, rect: RectPx, namePrefix: String): FileRef? =
        TODO("IosImageOps.crop — wrap CGImage.cropping(to: CGRect) then UIImage(cgImage:) and write to Caches/")

    override suspend fun rotate(uri: String, degrees: Int, namePrefix: String): FileRef? =
        TODO("IosImageOps.rotate — wrap UIGraphicsImageRenderer with CGAffineTransform(rotationAngle: degrees * .pi / 180)")
}
