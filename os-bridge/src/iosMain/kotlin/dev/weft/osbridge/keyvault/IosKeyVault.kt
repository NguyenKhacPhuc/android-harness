package dev.weft.osbridge.keyvault

import dev.weft.contracts.KeyVault

/**
 * iOS stub for [KeyVault]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `Security.framework` Keychain Services —
 * `SecItemAdd` / `SecItemUpdate` for put, `SecItemCopyMatching` for get,
 * `SecItemDelete` for remove, and a `SecItemCopyMatching` with
 * `kSecReturnAttributes` for exists. Use `kSecClassGenericPassword` items
 * keyed by `kSecAttrAccount = alias`, `kSecAttrService = <bundle id>`.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosKeyVault : KeyVault {
    override suspend fun put(alias: String, secret: String): Unit =
        TODO("IosKeyVault.put — wrap SecItemAdd / SecItemUpdate on a kSecClassGenericPassword item")

    override suspend fun get(alias: String): String? =
        TODO("IosKeyVault.get — wrap SecItemCopyMatching with kSecReturnData")

    override suspend fun remove(alias: String): Boolean =
        TODO("IosKeyVault.remove — wrap SecItemDelete")

    override suspend fun exists(alias: String): Boolean =
        TODO("IosKeyVault.exists — wrap SecItemCopyMatching with kSecReturnAttributes")
}
