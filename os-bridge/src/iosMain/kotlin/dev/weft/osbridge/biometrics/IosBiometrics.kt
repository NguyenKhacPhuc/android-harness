package dev.weft.osbridge.biometrics

import dev.weft.contracts.BiometricResult
import dev.weft.contracts.Biometrics
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

/**
 * iOS [Biometrics] via `LocalAuthentication`. [authenticate] gates on
 * `canEvaluatePolicy` first (Face ID / Touch ID availability + enrollment),
 * then runs `evaluatePolicy` and maps the resulting `LAError` codes onto the
 * contract's result types.
 *
 * Maps cancellations (user / system / app) to [BiometricResult.UserCancelled],
 * lockout to [BiometricResult.LockedOut], missing-or-unenrolled hardware to
 * [BiometricResult.NotAvailable], and anything else to [BiometricResult.Failed].
 *
 * Open so hosts can subclass and override individual methods.
 */
@OptIn(ExperimentalForeignApi::class)
public open class IosBiometrics : Biometrics {

    override suspend fun authenticate(reason: String): BiometricResult {
        val context = LAContext()
        val policy = LAPolicyDeviceOwnerAuthenticationWithBiometrics

        val availability = memScoped {
            val errPtr = alloc<ObjCObjectVar<NSError?>>()
            if (context.canEvaluatePolicy(policy, errPtr.ptr)) {
                null
            } else {
                mapError(errPtr.value)
            }
        }
        if (availability != null) return availability

        return suspendCancellableCoroutine { cont ->
            context.evaluatePolicy(policy, localizedReason = reason) { success, error ->
                if (!cont.isActive) return@evaluatePolicy
                cont.resume(if (success) BiometricResult.Authenticated else mapError(error))
            }
        }
    }

    private fun mapError(error: NSError?): BiometricResult = when (error?.code) {
        null -> BiometricResult.Failed("")
        LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel -> BiometricResult.UserCancelled
        LAErrorBiometryLockout -> BiometricResult.LockedOut
        LAErrorBiometryNotAvailable, LAErrorBiometryNotEnrolled -> BiometricResult.NotAvailable
        else -> BiometricResult.Failed(error.localizedDescription ?: "")
    }
}
