package dev.weft.osbridge.keyvault

import dev.weft.contracts.KeyVault
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * iOS [KeyVault] backed by the system Keychain via
 * `kSecClassGenericPassword` items.
 *
 * Each secret is an item keyed by `kSecAttrAccount = alias` under a
 * shared `kSecAttrService` (the app's bundle id by default, overridable
 * for tests or multi-vault hosts). Items use
 * `kSecAttrAccessibleAfterFirstUnlock` — readable once the device has
 * been unlocked since boot, then while locked — the right balance for an
 * assistant the user may fire from a notification.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public open class IosKeyVault(
    private val service: String = defaultService(),
) : KeyVault {

    override suspend fun put(alias: String, secret: String) {
        // Delete-then-add keeps put idempotent: avoids errSecDuplicateItem
        // and the more elaborate SecItemUpdate path.
        remove(alias)
        val data = (secret as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("IosKeyVault.put: failed to encode secret as UTF-8")
        memScoped {
            val query = CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                5,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, service.cf())
            CFDictionaryAddValue(query, kSecAttrAccount, alias.cf())
            CFDictionaryAddValue(query, kSecValueData, data.cf())
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            val status = SecItemAdd(query, null)
            if (status != errSecSuccess) {
                error("IosKeyVault.put: SecItemAdd failed (status=$status, alias=$alias)")
            }
        }
    }

    override suspend fun get(alias: String): String? = memScoped {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            5,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, service.cf())
        CFDictionaryAddValue(query, kSecAttrAccount, alias.cf())
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)

        val result = alloc<CFTypeRefVar>()
        val status: OSStatus = SecItemCopyMatching(query, result.ptr)
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        NSString.create(data, NSUTF8StringEncoding) as? String
    }

    override suspend fun remove(alias: String): Boolean = memScoped {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            3,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, service.cf())
        CFDictionaryAddValue(query, kSecAttrAccount, alias.cf())
        when (SecItemDelete(query)) {
            errSecSuccess -> true
            errSecItemNotFound -> false
            else -> false
        }
    }

    override suspend fun exists(alias: String): Boolean = memScoped {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            4,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, service.cf())
        CFDictionaryAddValue(query, kSecAttrAccount, alias.cf())
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        SecItemCopyMatching(query, null) == errSecSuccess
    }

    private companion object {
        fun defaultService(): String =
            NSBundle.mainBundle.bundleIdentifier ?: "dev.weft.keyvault"
    }
}

/**
 * Bridge a Kotlin/ObjC value to a +1-retained `CFTypeRef`. The
 * CoreFoundation dictionary takes ownership of the added value.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun Any.cf(): CFTypeRef? = CFBridgingRetain(this)
