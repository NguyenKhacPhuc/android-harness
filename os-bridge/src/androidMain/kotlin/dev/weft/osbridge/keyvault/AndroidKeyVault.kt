package dev.weft.osbridge.keyvault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.weft.contracts.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KeyVault backed by EncryptedSharedPreferences with a master key from
 * Android Keystore. Hardware-backed on devices with StrongBox or a TEE.
 *
 * The substrate's threat model treats the device as the trust boundary:
 *   - The plaintext key never lives outside Keystore-encrypted storage.
 *   - Other apps cannot read this app's prefs (per-uid).
 *   - On rooted devices, all bets are off — this is documented in SECURITY.md.
 *
 * Construction is synchronous (it touches Keystore once); reads/writes are
 * dispatched to IO. Construction may take ~tens of ms cold; do it once at
 * app startup and reuse the instance.
 */
class AndroidKeyVault private constructor(
    private val prefs: SharedPreferences,
) : KeyVault {

    override suspend fun put(alias: String, secret: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(alias, secret).apply()
    }

    override suspend fun get(alias: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(alias, null)
    }

    override suspend fun remove(alias: String): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.contains(alias)) return@withContext false
        prefs.edit().remove(alias).apply()
        true
    }

    override suspend fun exists(alias: String): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(alias)
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mas_keyvault_v1"

        /**
         * Build a KeyVault. Performs Keystore-backed setup; safe to call once
         * at startup and cache.
         */
        @JvmStatic
        @JvmOverloads
        fun create(context: Context, fileName: String = DEFAULT_FILE_NAME): AndroidKeyVault {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(false)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return AndroidKeyVault(prefs)
        }

        /**
         * Build a KeyVault from a pre-constructed SharedPreferences. Useful for
         * tests with a fake or in-memory implementation.
         */
        @JvmStatic
        fun fromSharedPreferences(prefs: SharedPreferences): AndroidKeyVault = AndroidKeyVault(prefs)
    }
}
