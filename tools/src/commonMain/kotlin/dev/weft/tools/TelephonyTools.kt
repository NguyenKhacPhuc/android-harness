package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.TelephonyInfo
import kotlinx.serialization.Serializable

/**
 * Open the system dialer pre-filled with a phone number. The user
 * taps to call — we never auto-place. Use for "call mom", "dial my
 * mechanic", or anywhere the user wants the dialer ready.
 *
 * NOT for SMS — use `sms_compose`. NOT for placing a call without
 * user confirmation (substrate intentionally won't do that).
 */
class PhoneDialTool(ctx: WeftContext) : WeftTool<PhoneDialTool.Args, PhoneDialTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "phone_dial",
        description = "Open the system dialer pre-filled with a phone number. The user " +
            "still has to tap the call button — we never auto-call. Use for 'call X', " +
            "'dial Y'. NOT for sending SMS (use sms_compose).",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "phoneNumber",
                "Phone number with country code preferred ('+14155551234', '15551234567').",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
    sideEffecting = true,
) {

    @Serializable
    data class Args(val phoneNumber: String)

    @Serializable
    data class Result(val opened: Boolean)

    override suspend fun executeWeft(args: Args): Result =
        Result(opened = os.telephony.dial(args.phoneNumber))
}

/**
 * Open the default SMS app pre-filled with recipient and optional
 * body. The user reviews and taps send.
 *
 * NOT for placing calls (use `phone_dial`). NOT for sending without
 * user confirmation. NOT for reading SMS (intentionally out of scope).
 */
class SmsComposeTool(ctx: WeftContext) :
    WeftTool<SmsComposeTool.Args, SmsComposeTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "sms_compose",
            description = "Open the SMS app pre-filled with recipient + body. The user " +
                "reviews and sends. Use for 'text X saying Y', 'send a quick SMS'. NOT for " +
                "auto-sending (we never do).",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "phoneNumber",
                    "Recipient phone number.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("body", "Message body to pre-fill.", ToolParameterType.String),
            ),
        ),
        sideEffecting = true,
    ) {

    @Serializable
    data class Args(val phoneNumber: String, val body: String? = null)

    @Serializable
    data class Result(val opened: Boolean)

    override suspend fun executeWeft(args: Args): Result =
        Result(opened = os.telephony.composeSms(args.phoneNumber, args.body))
}

/**
 * Read carrier / SIM info — carrier name, SIM country, network
 * operator, phone type, airplane mode. No permission needed. Use
 * for: "what carrier?", "is airplane mode on?", country-aware
 * routing.
 */
class TelephonyInfoTool(ctx: WeftContext) : WeftTool<TelephonyInfoTool.Args, TelephonyInfo>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<TelephonyInfo>(),
    descriptor = ToolDescriptor(
        name = "telephony_info",
        description = "Read carrier name, SIM country (ISO), network operator, phone type " +
            "(GSM/CDMA/SIP/NONE), and airplane-mode flag. No permission required.",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're calling, e.g. 'user asked about carrier'. Ignored.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = emptyList(),
    ),
) {
    @Serializable
    data class Args(val context: String = "")

    override suspend fun executeWeft(args: Args): TelephonyInfo = os.telephony.info()
}
