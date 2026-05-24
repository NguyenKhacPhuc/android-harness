package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.BiometricResult
import kotlinx.serialization.Serializable

/**
 * Prompt the user for biometric (fingerprint / face) or device-credential
 * (PIN / pattern / password) authentication. Use to gate destructive or
 * sensitive operations — clearing memories, viewing secrets, confirming
 * payments — so the model can't take an irreversible action without an
 * explicit "yes, it's me" from the human.
 *
 * Not flagged destructive on its own — the destructive operation is what
 * the agent does *after* a successful authenticate. Pattern:
 *
 * ```
 * 1. agent → biometric_authenticate(reason = "Clear all memories")
 * 2. user authenticates
 * 3. agent reads result.outcome == "AUTHENTICATED"
 * 4. agent → memory_clear  (or similar destructive op)
 * ```
 */
public class BiometricAuthenticateTool(ctx: WeftContext) :
    WeftTool<BiometricAuthenticateTool.Args, BiometricAuthenticateTool.Result>(
        ctx = ctx,
        argsType = typeToken<Args>(),
        resultType = typeToken<Result>(),
        descriptor = ToolDescriptor(
            name = "biometric_authenticate",
            description = "Prompt the user for biometric or device-credential authentication. " +
                "Returns outcome: AUTHENTICATED (user confirmed), USER_CANCELLED (user dismissed), " +
                "NOT_AVAILABLE (no biometrics enrolled / no hardware), LOCKED_OUT (too many failed " +
                "attempts), or FAILED (other error). Use to gate destructive operations the user " +
                "should explicitly approve.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "reason",
                    "Short user-facing subtitle explaining what they're confirming, e.g. " +
                        "'Clear all memories' or 'Show your saved API keys'.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
    ) {

    @Serializable
    public data class Args(val reason: String)

    @Serializable
    public data class Result(
        /** One of: AUTHENTICATED, USER_CANCELLED, NOT_AVAILABLE, LOCKED_OUT, FAILED. */
        val outcome: String,
        /** Human-readable detail, mostly useful when outcome=FAILED. */
        val detail: String? = null,
    )

    override suspend fun executeWeft(args: Args): Result =
        when (val r = os.biometrics.authenticate(args.reason)) {
            BiometricResult.Authenticated -> Result(outcome = "AUTHENTICATED")
            BiometricResult.UserCancelled -> Result(outcome = "USER_CANCELLED")
            BiometricResult.NotAvailable -> Result(outcome = "NOT_AVAILABLE")
            BiometricResult.LockedOut -> Result(outcome = "LOCKED_OUT")
            is BiometricResult.Failed -> Result(outcome = "FAILED", detail = r.message.ifEmpty { null })
        }
}
