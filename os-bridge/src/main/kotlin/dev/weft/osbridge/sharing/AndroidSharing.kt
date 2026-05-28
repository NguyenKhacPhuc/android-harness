package dev.weft.osbridge.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.weft.contracts.ShareContent
import dev.weft.contracts.ShareTarget
import dev.weft.contracts.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Sharing]. Backs `external_share`.
 *
 * Handles three content shapes:
 *   - Plain text / URL → `ACTION_SEND` with `text/plain` and `EXTRA_TEXT`.
 *   - File URI (typically a `content://…/fileprovider/…` returned by
 *     [dev.weft.osbridge.files.AndroidFiles]) → `ACTION_SEND` with the
 *     URI's actual MIME type and `EXTRA_STREAM`.
 *   - Both text and a file → file send with `EXTRA_TEXT` as caption.
 *
 * [ShareTarget.SpecificApp] passes the package id via `Intent.setPackage`;
 * if the app doesn't handle the MIME type, the share fails silently.
 */
class AndroidSharing(private val context: Context) : Sharing {

    override suspend fun share(content: ShareContent, target: ShareTarget): Boolean = withContext(Dispatchers.Default) {
        val text = listOfNotNull(content.text, content.url).joinToString(separator = "\n").takeIf { it.isNotBlank() }
        val fileUri: Uri? = content.fileUri?.let(Uri::parse)

        if (text == null && fileUri == null) return@withContext false

        val intent = Intent(Intent.ACTION_SEND).apply {
            if (fileUri != null) {
                type = context.contentResolver.getType(fileUri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                // Grant the receiver one-shot read on this URI.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = when (target) {
            is ShareTarget.SystemSheet ->
                Intent.createChooser(intent, "Share via").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            is ShareTarget.SpecificApp -> {
                intent.setPackage(target.appId)
                intent
            }
        }

        runCatching { context.startActivity(chooser) }.isSuccess
    }
}
