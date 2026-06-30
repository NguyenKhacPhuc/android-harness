package dev.weft.osbridge.telephony

import dev.weft.contracts.Telephony
import dev.weft.contracts.TelephonyInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreTelephony.CTCarrier
import platform.CoreTelephony.CTTelephonyNetworkInfo
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.coroutines.resume

/**
 * iOS [Telephony]. Dial / composeSms hand off to the system apps via
 * `tel:` / `sms:` URLs (the user taps to act — we never auto-dial or
 * auto-send). [info] reads the permissionless carrier snapshot from
 * `CTTelephonyNetworkInfo` / `CTCarrier`. As of iOS 16 `CTCarrier` is
 * deprecated and returns placeholder values ("--" / nil) on real
 * hardware, so carrier fields are kept nullable. Airplane mode is not
 * observable on iOS, so it is always reported false.
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosTelephony : Telephony {

    override suspend fun dial(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val digits = phoneNumber.filter { it.isDigit() || it == '+' }
        return open("tel://$digits")
    }

    override suspend fun composeSms(phoneNumber: String, body: String?): Boolean {
        if (phoneNumber.isBlank()) return false
        // iOS ignores a prefilled body via the sms: URL scheme; just open the thread.
        return open("sms:$phoneNumber")
    }

    override suspend fun info(): TelephonyInfo {
        val networkInfo = CTTelephonyNetworkInfo()
        val carrier = networkInfo.serviceSubscriberCellularProviders
            ?.values
            ?.firstOrNull() as? CTCarrier
            ?: return TelephonyInfo()

        val mcc = carrier.mobileCountryCode
        val mnc = carrier.mobileNetworkCode
        val networkOperator = if (mcc != null && mnc != null) "$mcc$mnc" else null
        return TelephonyInfo(
            carrierName = carrier.carrierName?.takeIf { it.isNotBlank() },
            simCountryIso = carrier.isoCountryCode?.takeIf { it.isNotBlank() }?.uppercase(),
            networkOperator = networkOperator,
            phoneType = "GSM",
            airplaneMode = false,
        )
    }

    private suspend fun open(urlString: String): Boolean {
        val url = NSURL.URLWithString(urlString) ?: return false
        return withContext(Dispatchers.Main) {
            val app = UIApplication.sharedApplication
            if (!app.canOpenURL(url)) return@withContext false
            suspendCancellableCoroutine { cont ->
                app.openURL(url, options = emptyMap<Any?, Any?>()) { success ->
                    if (cont.isActive) cont.resume(success)
                }
            }
        }
    }
}
