package dev.weft.osbridge.biometrics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.weft.contracts.BiometricResult
import dev.weft.contracts.Biometrics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Android implementation of [Biometrics] backed by [BiometricPrompt].
 *
 * BiometricPrompt requires a `FragmentActivity` (or `AppCompatActivity`)
 * to attach its bottom-sheet UI to. We track the foreground activity
 * automatically by registering an `ActivityLifecycleCallbacks` once at
 * construction — apps don't have to manually thread an Activity reference
 * through their wiring.
 *
 * If the foreground activity isn't a `FragmentActivity` (rare — would
 * imply a pure `Activity` host that opted out of AndroidX), authenticate
 * returns [BiometricResult.NotAvailable] rather than crashing.
 *
 * Threading: BiometricPrompt's constructor and `authenticate()` must run
 * on the main thread. We hop dispatchers internally so callers can invoke
 * from any context.
 */
class AndroidBiometrics(context: Context) : Biometrics {

    private val app: Application = context.applicationContext as Application
    private val mgr: BiometricManager = BiometricManager.from(app)

    @Volatile
    private var foreground: WeakReference<FragmentActivity>? = null

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is FragmentActivity) foreground = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (foreground?.get() === activity) foreground = null
            }
            // No-op callbacks — required by the interface.
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override suspend fun authenticate(reason: String): BiometricResult = withContext(Dispatchers.Main) {
        // canAuthenticate gates early — no point lighting up the prompt if
        // the device has no enrolled biometrics or no hardware.
        val auth = mgr.canAuthenticate(ALLOWED_AUTHENTICATORS)
        when (auth) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> return@withContext BiometricResult.NotAvailable
        }

        val activity = foreground?.get()
            ?: return@withContext BiometricResult.NotAvailable

        suspendCancellableCoroutine<BiometricResult> { cont ->
            val executor = ContextCompat.getMainExecutor(app)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(BiometricResult.Authenticated)
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (!cont.isActive) return
                        val r = when (code) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED -> BiometricResult.UserCancelled
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricResult.LockedOut
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricResult.NotAvailable
                            else -> BiometricResult.Failed(msg.toString())
                        }
                        cont.resume(r)
                    }
                    override fun onAuthenticationFailed() {
                        // Don't resume — the user failed a single attempt but
                        // BiometricPrompt stays open. We only resume on a
                        // terminal succeed/error.
                    }
                },
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm it's you")
                .setSubtitle(reason)
                .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                .build()

            prompt.authenticate(info)

            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
    }

    private companion object {
        // BIOMETRIC_WEAK covers fingerprint + face (Class 2 sensors).
        // DEVICE_CREDENTIAL falls back to PIN/pattern/password if no
        // biometric is enrolled — the user always has *some* way to confirm.
        const val ALLOWED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
