package dev.weft.osbridge.clipboard

import dev.weft.contracts.Clipboard
import platform.UIKit.UIPasteboard

/**
 * iOS [Clipboard] backed by `UIPasteboard.generalPasteboard`.
 *
 * Empty strings read back as `null` to match the Android impl, which
 * coerces an empty primary clip to `null`. iOS surfaces a transient
 * "Pasted from <app>" banner on read (iOS 14+) — treat [read] as
 * user-observable.
 *
 * Open so hosts can subclass and override individual methods.
 */
public open class IosClipboard : Clipboard {

    private val pasteboard: UIPasteboard
        get() = UIPasteboard.generalPasteboard

    override suspend fun read(): String? =
        pasteboard.string?.takeIf { it.isNotEmpty() }

    override suspend fun write(text: String) {
        pasteboard.string = text
    }

    override suspend fun clear() {
        pasteboard.items = emptyList<Map<Any?, Any?>>()
    }
}
