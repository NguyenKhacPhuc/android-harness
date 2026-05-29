package dev.weft.osbridge.clipboard

import dev.weft.contracts.Clipboard

/**
 * iOS stub for [Clipboard]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIKit.UIPasteboard.general` —
 * `.string` getter for read, `.string =` setter for write, and
 * `.items = []` (or `setItems([], options: [...])`) for clear. iOS 14+
 * shows a transient "Pasted from <app>" banner on read, equivalent to
 * Android's API 29+ toast.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosClipboard : Clipboard {
    override suspend fun read(): String? =
        TODO("IosClipboard.read — wrap UIPasteboard.general.string")

    override suspend fun write(text: String): Unit =
        TODO("IosClipboard.write — wrap UIPasteboard.general.string = text")

    override suspend fun clear(): Unit =
        TODO("IosClipboard.clear — wrap UIPasteboard.general.items = []")
}
