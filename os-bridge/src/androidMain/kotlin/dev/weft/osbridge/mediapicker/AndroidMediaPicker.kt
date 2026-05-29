package dev.weft.osbridge.mediapicker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.weft.contracts.MediaPicker
import dev.weft.contracts.MediaPickerKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Android implementation of [MediaPicker] backed by
 * [ActivityResultContracts.PickVisualMedia] /
 * [ActivityResultContracts.PickMultipleVisualMedia].
 *
 * **Permissionless** — the OS picker shows only what the user
 * explicitly selects. Apps don't need READ_MEDIA_IMAGES etc., which
 * makes this the Play-Store-safe alternative to [MediaLibrary] for
 * any "user picks the file" flow.
 *
 * Same foreground-activity dance as
 * [dev.weft.osbridge.camera.AndroidCamera] — we track the resumed
 * [ActivityResultRegistryOwner] via lifecycle callbacks and register
 * a fresh launcher per call.
 *
 * Returns an empty list when the user cancels (the picker contract
 * uses an empty result, not null, for cancellation).
 */
class AndroidMediaPicker(context: Context) : MediaPicker {
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

    override suspend fun pick(kind: MediaPickerKind, maxItems: Int): List<String> {
        val owner = foreground?.get() ?: return emptyList()
        val mediaType = kind.toMediaType()
        val request = PickVisualMediaRequest(mediaType)

        return withContext(Dispatchers.Main) {
            if (maxItems <= 1) pickSingle(owner, request) else pickMultiple(owner, request, maxItems)
        }
    }

    private suspend fun pickSingle(
        owner: ActivityResultRegistryOwner,
        request: PickVisualMediaRequest,
    ): List<String> {
        val key = "substrate.mediapicker.single.${UUID.randomUUID()}"
        return suspendCancellableCoroutine { cont ->
            lateinit var launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>
            launcher = owner.activityResultRegistry.register(
                key,
                ActivityResultContracts.PickVisualMedia(),
            ) { uri: Uri? ->
                runCatching { launcher.unregister() }
                if (cont.isActive) {
                    cont.resume(if (uri == null) emptyList() else listOf(uri.toString()))
                }
            }
            cont.invokeOnCancellation { runCatching { launcher.unregister() } }
            runCatching { launcher.launch(request) }
                .onFailure {
                    runCatching { launcher.unregister() }
                    if (cont.isActive) cont.resume(emptyList())
                }
        }
    }

    private suspend fun pickMultiple(
        owner: ActivityResultRegistryOwner,
        request: PickVisualMediaRequest,
        maxItems: Int,
    ): List<String> {
        val cap = maxItems.coerceIn(MIN_MULTI_PICK, MAX_MULTI_PICK)
        val key = "substrate.mediapicker.multi.${UUID.randomUUID()}"
        return suspendCancellableCoroutine { cont ->
            lateinit var launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>
            launcher = owner.activityResultRegistry.register(
                key,
                ActivityResultContracts.PickMultipleVisualMedia(cap),
            ) { uris: List<Uri> ->
                runCatching { launcher.unregister() }
                if (cont.isActive) cont.resume(uris.map { it.toString() })
            }
            cont.invokeOnCancellation { runCatching { launcher.unregister() } }
            runCatching { launcher.launch(request) }
                .onFailure {
                    runCatching { launcher.unregister() }
                    if (cont.isActive) cont.resume(emptyList())
                }
        }
    }

    private fun MediaPickerKind.toMediaType(): ActivityResultContracts.PickVisualMedia.VisualMediaType =
        when (this) {
            MediaPickerKind.IMAGE -> ActivityResultContracts.PickVisualMedia.ImageOnly
            MediaPickerKind.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
            MediaPickerKind.IMAGE_OR_VIDEO -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }

    private companion object {
        // PickMultipleVisualMedia requires max in [2, getPickImagesMaxLimit()];
        // we coerce to a sane range.
        const val MIN_MULTI_PICK = 2
        const val MAX_MULTI_PICK = 100
    }
}
