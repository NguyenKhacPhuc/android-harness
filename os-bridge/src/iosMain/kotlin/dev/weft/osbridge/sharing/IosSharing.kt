package dev.weft.osbridge.sharing

import dev.weft.contracts.ShareContent
import dev.weft.contracts.ShareTarget
import dev.weft.contracts.Sharing

/**
 * iOS stub for [Sharing]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIKit.UIActivityViewController` —
 * pass `activityItems: [Any]` containing the text / URL / file URL, then
 * present from the foreground `UIViewController`. For "specific app"
 * targets there's no public iOS API — the closest analogue is opening a
 * deep link via `UIApplication.shared.open(_:)` with the target app's
 * URL scheme.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosSharing : Sharing {
    override suspend fun share(content: ShareContent, target: ShareTarget): Boolean =
        TODO("IosSharing.share — wrap UIActivityViewController with activityItems and present from the foreground UIViewController")
}
