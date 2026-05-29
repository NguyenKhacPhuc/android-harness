package dev.weft.osbridge.telephony

import dev.weft.contracts.Telephony
import dev.weft.contracts.TelephonyInfo

/**
 * iOS stub for [Telephony]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `UIApplication.shared.open(URL(string: "tel:..."))`
 * for dial, `UIApplication.shared.open(URL(string: "sms:&body=..."))`
 * for composeSms, and `CoreTelephony.CTTelephonyNetworkInfo` +
 * `CTCarrier` for the carrier snapshot (`carrierName`,
 * `mobileCountryCode + mobileNetworkCode`, `isoCountryCode`). Airplane
 * mode is not directly observable on iOS — best-effort by checking
 * `NWPathMonitor` reports no transports.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosTelephony : Telephony {
    override suspend fun dial(phoneNumber: String): Boolean =
        TODO("IosTelephony.dial — wrap UIApplication.shared.open(URL(string: \"tel:<number>\"))")

    override suspend fun composeSms(phoneNumber: String, body: String?): Boolean =
        TODO("IosTelephony.composeSms — wrap UIApplication.shared.open(URL(string: \"sms:<number>&body=<body>\"))")

    override suspend fun info(): TelephonyInfo =
        TODO("IosTelephony.info — wrap CTTelephonyNetworkInfo.serviceSubscriberCellularProviders + CTCarrier fields")
}
