package dev.weft.osbridge.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.weft.contracts.Clipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Clipboard], backed by the system
 * [android.content.ClipboardManager].
 *
 * Read behavior: returns the primary clip coerced to text. Non-text clips
 * (images, URIs) come back as their `coerceToText(context)` representation,
 * which is what most users expect ("Image" or the URI string).
 *
 * Privacy note: on Android 10+ (API 29+) the OS surfaces a system-level
 * "Pasted from <app>" toast whenever the primary clip is read by an app
 * not in the foreground. Tools that call [read] should treat the call as
 * user-observable.
 */
public class AndroidClipboard(context: Context) : Clipboard {

    private val appContext: Context = context.applicationContext
    private val cm: ClipboardManager =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override suspend fun read(): String? = withContext(Dispatchers.Main) {
        val clip = cm.primaryClip ?: return@withContext null
        if (clip.itemCount == 0) return@withContext null
        // coerceToText handles plain text clips natively and best-effort
        // converts URI / Intent clips to their string form via the
        // ContentResolver attached to the supplied context.
        clip.getItemAt(0).coerceToText(appContext)?.toString()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun write(text: String): Unit = withContext(Dispatchers.Main) {
        cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Main) {
        cm.clearPrimaryClip()
    }

    private companion object {
        // Shown by Android's clipboard editors as the source description.
        // Generic so the substrate doesn't leak the agent's identity into
        // the user's clipboard history.
        const val CLIP_LABEL = "substrate"
    }
}
