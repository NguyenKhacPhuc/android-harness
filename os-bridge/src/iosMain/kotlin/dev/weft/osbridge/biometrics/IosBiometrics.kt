package dev.weft.osbridge.biometrics

import dev.weft.contracts.BiometricResult
import dev.weft.contracts.Biometrics

/**
 * iOS stub for [Biometrics]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `LocalAuthentication.LAContext` —
 * `canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error:)`
 * to gate availability, then
 * `evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason:)`
 * to run the Face ID / Touch ID prompt. Map `LAError` codes onto
 * [BiometricResult.UserCancelled] / `.NotAvailable` / `.LockedOut` /
 * `.Failed`.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosBiometrics : Biometrics {
    override suspend fun authenticate(reason: String): BiometricResult =
        TODO("IosBiometrics.authenticate — wrap LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason:)")
}
