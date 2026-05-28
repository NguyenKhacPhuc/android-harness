package dev.weft.osbridge.telephony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.telephony.TelephonyManager
import dev.weft.contracts.Telephony
import dev.weft.contracts.TelephonyInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Telephony]. Dial / SMS-compose are
 * Intent-based handoffs (no permission). [info] reads the public,
 * permissionless subset of [TelephonyManager] state.
 */
class AndroidTelephony(context: Context) : Telephony {
    private val appContext: Context = context.applicationContext

    override suspend fun dial(phoneNumber: String): Boolean = withContext(Dispatchers.Default) {
        if (phoneNumber.isBlank()) return@withContext false
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${encodeNumber(phoneNumber)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }.isSuccess
    }

    override suspend fun composeSms(phoneNumber: String, body: String?): Boolean =
        withContext(Dispatchers.Default) {
            if (phoneNumber.isBlank()) return@withContext false
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${encodeNumber(phoneNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!body.isNullOrEmpty()) putExtra("sms_body", body)
            }
            runCatching { appContext.startActivity(intent) }.isSuccess
        }

    override suspend fun info(): TelephonyInfo = withContext(Dispatchers.IO) {
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return@withContext TelephonyInfo()

        val phoneType = when (runCatching { tm.phoneType }.getOrDefault(TelephonyManager.PHONE_TYPE_NONE)) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            else -> "NONE"
        }

        val airplane = runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0
        }.getOrDefault(false)

        TelephonyInfo(
            carrierName = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            simCountryIso = runCatching { tm.simCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.uppercase(),
            networkOperator = runCatching { tm.networkOperator }.getOrNull()?.takeIf { it.isNotBlank() },
            phoneType = phoneType,
            airplaneMode = airplane,
        )
    }

    /** Percent-encode for the tel: / smsto: URI authority. */
    private fun encodeNumber(raw: String): String = Uri.encode(raw)
}
