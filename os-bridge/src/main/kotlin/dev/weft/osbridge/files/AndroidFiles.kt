package dev.weft.osbridge.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.weft.contracts.FileContent
import dev.weft.contracts.FileRef
import dev.weft.contracts.FileSaveSpec
import dev.weft.contracts.Files
import dev.weft.contracts.ShareTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import android.util.Base64
import java.util.UUID

/**
 * Android implementation of [Files]. Writes / reads files inside the app's
 * private storage (Context.filesDir), and exposes them to other apps via a
 * FileProvider when shared.
 *
 * No runtime storage permission needed — everything stays inside the app
 * sandbox. Other apps can only read files we explicitly share via a
 * `content://${applicationId}.fileprovider/...` URI.
 *
 * The FileProvider declaration lives in the os-bridge AndroidManifest with
 * the placeholder `${applicationId}.fileprovider`, so each consuming app
 * gets a unique authority.
 */
public class AndroidFiles(private val context: Context) : Files {

    /**
     * Authority used for the FileProvider. Matches the `<provider>` in the
     * library manifest (which uses `${applicationId}.fileprovider`).
     */
    private val authority: String get() = "${context.packageName}.fileprovider"

    override suspend fun save(spec: FileSaveSpec): FileRef = withContext(Dispatchers.IO) {
        val dir = if (spec.directory.isNullOrBlank()) {
            context.filesDir
        } else {
            File(context.filesDir, spec.directory).apply { mkdirs() }
        }
        val name = spec.name ?: defaultName(spec.mimeType)
        val file = File(dir, name)

        val bytes: ByteArray = when {
            spec.text != null -> spec.text!!.toByteArray()
            spec.contentBase64 != null -> Base64.decode(spec.contentBase64, Base64.DEFAULT)
            else -> ByteArray(0)
        }

        file.outputStream().use { it.write(bytes) }

        val uri = FileProvider.getUriForFile(context, authority, file)
        FileRef(uri = uri.toString(), sizeBytes = file.length())
    }

    override suspend fun read(uri: String, asBase64: Boolean): FileContent = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val bytes: ByteArray = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
            ?: throw IOException("Could not open URI: $uri")
        val mimeType: String = context.contentResolver.getType(parsed) ?: "application/octet-stream"

        if (asBase64) {
            FileContent(
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
            )
        } else {
            FileContent(
                text = bytes.toString(Charsets.UTF_8),
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
            )
        }
    }

    override suspend fun share(uri: String, target: ShareTarget): Boolean = withContext(Dispatchers.Default) {
        val parsed = Uri.parse(uri)
        val mimeType = context.contentResolver.getType(parsed) ?: "*/*"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, parsed)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val toFire = when (target) {
            is ShareTarget.SystemSheet -> Intent.createChooser(intent, "Share file").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            is ShareTarget.SpecificApp -> intent.apply { setPackage(target.appId) }
        }
        runCatching { context.startActivity(toFire) }.isSuccess
    }

    private fun defaultName(mimeType: String): String {
        val ext = when {
            mimeType.startsWith("text/") -> ".txt"
            mimeType == "application/json" -> ".json"
            mimeType == "application/pdf" -> ".pdf"
            mimeType.startsWith("image/png") -> ".png"
            mimeType.startsWith("image/jpeg") -> ".jpg"
            else -> ""
        }
        return "file-${UUID.randomUUID()}$ext"
    }
}
