package dev.weft.osbridge.files

import dev.weft.contracts.FileContent
import dev.weft.contracts.FileRef
import dev.weft.contracts.FileSaveSpec
import dev.weft.contracts.Files
import dev.weft.contracts.ShareTarget

/**
 * iOS stub for [Files]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `Foundation.FileManager` for sandboxed I/O
 * (`urls(for: .documentDirectory, in: .userDomainMask)`,
 * `createFile(atPath:contents:attributes:)`), `Data(contentsOf:)` /
 * `data.write(to:)` for read/write, and `UIActivityViewController` for the
 * share sheet hand-off (must be presented from a `UIViewController`).
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosFiles : Files {
    override suspend fun save(spec: FileSaveSpec): FileRef =
        TODO("IosFiles.save — wrap FileManager + Data.write(to:) under the app's Documents/Caches directory")

    override suspend fun read(uri: String, asBase64: Boolean): FileContent =
        TODO("IosFiles.read — wrap Data(contentsOf: URL) and optionally base64EncodedString()")

    override suspend fun share(uri: String, target: ShareTarget): Boolean =
        TODO("IosFiles.share — wrap UIActivityViewController presented from the foreground UIViewController")
}
