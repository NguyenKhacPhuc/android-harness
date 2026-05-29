package dev.weft.osbridge.camera

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import dev.weft.contracts.Camera
import dev.weft.contracts.FileRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Android implementation of [Camera]. Launches the system camera via
 * `ACTION_IMAGE_CAPTURE` and waits for the user to shoot-or-cancel.
 *
 * ### Why this looks different from the other capabilities
 *
 * Modern Android requires `ActivityResultLauncher` for any
 * `startActivityForResult`-style flow. The recommended pattern (registering
 * a launcher at `onCreate` via `registerForActivityResult`) is too tight a
 * coupling between the host app and the substrate — apps would have to
 * pre-declare every camera-capable launcher up front, even though only
 * the LLM decides when to invoke it.
 *
 * Instead we use the **lower-level** [androidx.activity.result.ActivityResultRegistry.register]
 * overload that doesn't take a [androidx.lifecycle.LifecycleOwner]. That
 * overload can be called at *any* point during the activity's lifetime —
 * including from a background coroutine driving the agent loop. We:
 *
 *   1. Track the foreground [ActivityResultRegistryOwner] via
 *      [Application.ActivityLifecycleCallbacks] (same trick as
 *      `AndroidBiometrics`).
 *   2. On `captureImage()`, pre-create the output file + FileProvider URI.
 *   3. Register a [ActivityResultContracts.TakePicture] launcher with a
 *      fresh unique key, launch it with the URI as input.
 *   4. Suspend until the callback fires, resume with `FileRef` or null,
 *      and unregister the launcher.
 *
 * ### Known limitations
 *
 *   - **No process-death survival.** If Android kills the process while
 *     the camera is open, the result arrives at a freshly-registered key
 *     that nobody is listening for, and the agent loop sees null. The LLM
 *     can re-call the tool. Adding survival would require persisting the
 *     pending key + URI to disk and re-registering on startup; deferred.
 *   - Requires the host activity to be a [ComponentActivity] (or anything
 *     else implementing [ActivityResultRegistryOwner]). Compose activities
 *     already are, so this isn't a real constraint in practice.
 */
class AndroidCamera(context: Context) : Camera {

    private val app: Application = context.applicationContext as Application
    private val authority: String = "${app.packageName}.fileprovider"

    @Volatile
    private var foreground: WeakReference<ActivityResultRegistryOwner>? = null

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is ActivityResultRegistryOwner) foreground = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (foreground?.get() === activity) foreground = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override suspend fun captureImage(namePrefix: String): FileRef? {
        // Quick capability gate — if the device has no camera at all, fail
        // early instead of launching an intent that resolves to nothing.
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return null

        val owner = foreground?.get() ?: return null

        // Pre-create the destination file. ACTION_IMAGE_CAPTURE writes
        // straight to the URI passed via EXTRA_OUTPUT (handled internally
        // by ActivityResultContracts.TakePicture), so the camera app needs
        // a real destination before launch.
        val outFile = withContext(Dispatchers.IO) {
            val cacheDir = File(app.cacheDir, "camera").apply { mkdirs() }
            File(cacheDir, "${sanitize(namePrefix)}-${System.currentTimeMillis()}.jpg")
                .also { it.createNewFile() }
        }
        val targetUri: Uri = FileProvider.getUriForFile(app, authority, outFile)

        // Unique key per call — avoids any collision if the LLM somehow
        // triggers two captures in flight (it shouldn't, but the contract
        // doesn't prevent it).
        val key = "substrate.camera.capture.${UUID.randomUUID()}"

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<FileRef?> { cont ->
                lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Uri>
                launcher = owner.activityResultRegistry.register(
                    key,
                    ActivityResultContracts.TakePicture(),
                ) { success ->
                    // Always unregister to keep the registry tidy. The
                    // non-lifecycle overload doesn't auto-clean.
                    launcher.unregister()
                    if (cont.isActive) {
                        if (success && outFile.exists() && outFile.length() > 0) {
                            cont.resume(FileRef(uri = targetUri.toString(), sizeBytes = outFile.length()))
                        } else {
                            outFile.delete()
                            cont.resume(null)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { launcher.unregister() }
                    outFile.delete()
                }
                launcher.launch(targetUri)
            }
        }
    }

    private fun sanitize(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "photo" }
}
