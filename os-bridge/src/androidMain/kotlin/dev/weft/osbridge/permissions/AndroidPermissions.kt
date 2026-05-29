package dev.weft.osbridge.permissions

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.weft.contracts.Permission
import dev.weft.contracts.PermissionState
import dev.weft.contracts.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Android implementation of [Permissions]. [check] is a synchronous
 * read; [request] launches the system permission dialog via
 * [androidx.activity.result.ActivityResultRegistry] and suspends until
 * the user responds.
 *
 * ### Why the foreground-activity dance
 *
 * The standard `registerForActivityResult(...)` pattern requires
 * pre-registration during `onCreate`. That doesn't work for us because
 * the LLM decides at runtime which permissions to ask for — we can't
 * pre-declare them. Same problem [dev.weft.osbridge.camera.AndroidCamera]
 * solves: track the currently-foregrounded [ActivityResultRegistryOwner]
 * via [Application.ActivityLifecycleCallbacks], then register a launcher
 * at the moment we need it.
 *
 * ### Limitations
 *
 *  - **No process-death survival.** If Android kills the process mid-
 *    permission-dialog, the result lands at a key nobody's listening
 *    for. The agent loop sees `NOT_DETERMINED` and can retry.
 *  - **No foreground = no prompt.** When the app is backgrounded the
 *    launcher can't run; we fall back to returning the current state
 *    via [check].
 *  - Host activity must be a [ComponentActivity] (or anything else
 *    implementing [ActivityResultRegistryOwner]). All Compose activities
 *    already are.
 */
class AndroidPermissions(context: Context) : Permissions {
    private val app: Application = context.applicationContext as Application

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

    override suspend fun check(permission: Permission): PermissionState {
        val androidPermission = permission.toAndroidPermission(app)
            ?: return PermissionState.GRANTED
        val granted = ContextCompat.checkSelfPermission(app, androidPermission) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionState.GRANTED else PermissionState.NOT_DETERMINED
    }

    override suspend fun request(permission: Permission): PermissionState {
        val androidPermission = permission.toAndroidPermission(app)
            ?: return PermissionState.GRANTED

        // Already granted — short-circuit, no prompt needed.
        if (ContextCompat.checkSelfPermission(app, androidPermission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return PermissionState.GRANTED
        }

        val owner = foreground?.get() ?: return PermissionState.NOT_DETERMINED

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val key = "weft.perm.${UUID.randomUUID()}"
                val launcher = owner.activityResultRegistry.register(
                    key,
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    val result = if (granted) PermissionState.GRANTED else PermissionState.DENIED
                    if (cont.isActive) cont.resume(result)
                }
                cont.invokeOnCancellation { runCatching { launcher.unregister() } }
                runCatching { launcher.launch(androidPermission) }
                    .onFailure {
                        // Launcher.launch can throw if the activity died
                        // mid-flight — recover by resuming with the
                        // current state so the caller's coroutine doesn't
                        // hang forever.
                        if (cont.isActive) cont.resume(PermissionState.NOT_DETERMINED)
                    }
            }
        }
    }
}

/**
 * Map our platform-agnostic [Permission] to the Android manifest string.
 * Returns null when the platform doesn't require a runtime permission
 * for the requested capability — caller treats null as implicit
 * GRANTED. Examples: BLUETOOTH_CONNECT is runtime-only on Android 12+;
 * NOTIFICATIONS_READ has no Android runtime permission at all.
 */
internal fun Permission.toAndroidPermission(context: Context): String? = when (this) {
    Permission.NOTIFICATIONS -> android.Manifest.permission.POST_NOTIFICATIONS
    Permission.CALENDAR_READ -> android.Manifest.permission.READ_CALENDAR
    Permission.CALENDAR_WRITE -> android.Manifest.permission.WRITE_CALENDAR
    Permission.CONTACTS_READ -> android.Manifest.permission.READ_CONTACTS
    Permission.LOCATION -> android.Manifest.permission.ACCESS_FINE_LOCATION
    Permission.READ_MEDIA_IMAGES -> android.Manifest.permission.READ_MEDIA_IMAGES
    Permission.READ_MEDIA_VIDEO -> android.Manifest.permission.READ_MEDIA_VIDEO
    Permission.READ_MEDIA_AUDIO -> android.Manifest.permission.READ_MEDIA_AUDIO
    Permission.CAMERA -> android.Manifest.permission.CAMERA
    Permission.MICROPHONE -> android.Manifest.permission.RECORD_AUDIO
    Permission.NOTIFICATIONS_READ -> null   // no Android runtime permission
    Permission.BLUETOOTH_CONNECT ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.Manifest.permission.BLUETOOTH_CONNECT
        } else {
            null
        }
    Permission.ACTIVITY_RECOGNITION ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.Manifest.permission.ACTIVITY_RECOGNITION
        } else {
            null
        }
}
