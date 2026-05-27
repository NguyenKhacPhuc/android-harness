package dev.weft.osbridge.translation

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MlkitTranslation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.weft.contracts.Translation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android implementation of [Translation] backed by ML Kit.
 *
 * Two pieces:
 *   - **Translation** — pair-specific [Translator] instances cached per
 *     (source, target). Models download lazily on first use, then run
 *     fully offline. First-call latency includes ~30MB download per
 *     pair; subsequent calls are sub-100ms.
 *   - **Language ID** — single [com.google.mlkit.nl.languageid.LanguageIdentifier]
 *     instance. ~1MB model, downloaded once.
 *
 * Both ML Kit components return [Task]s — we adapt them to suspend
 * functions via [awaitTask]. A 30-second timeout bounds model
 * downloads; translation itself is fast enough that the same timeout
 * applies to the whole operation.
 */
public class AndroidTranslation(context: Context) : Translation {
    @Suppress("unused") private val appContext: Context = context.applicationContext

    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    private val translators = HashMap<String, Translator>()

    override suspend fun translate(text: String, target: String, source: String?): String? {
        if (text.isBlank()) return null
        val targetCode = TranslateLanguage.fromLanguageTag(target) ?: return null
        val resolvedSource = (source ?: detectLanguage(text)).takeIf { it != UNDETERMINED }
            ?: return null
        val sourceCode = TranslateLanguage.fromLanguageTag(resolvedSource) ?: return null
        if (sourceCode == targetCode) return text

        val translator = translatorFor(sourceCode, targetCode)

        // Download model if needed (no-op once cached).
        val downloaded = withTimeoutOrNull(MODEL_TIMEOUT_MS) {
            awaitTask(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        }
        if (downloaded == null) return null

        return withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) {
            awaitTask(translator.translate(text))
        }
    }

    override suspend fun detectLanguage(text: String): String {
        if (text.isBlank()) return UNDETERMINED
        return withTimeoutOrNull(DETECT_TIMEOUT_MS) {
            awaitTask(languageIdentifier.identifyLanguage(text))
        } ?: UNDETERMINED
    }

    override suspend fun supportedLanguages(): List<String> = TranslateLanguage.getAllLanguages()

    private fun translatorFor(source: String, target: String): Translator {
        val key = "$source->$target"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            MlkitTranslation.getClient(options)
        }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T? = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { value ->
            if (cont.isActive) cont.resume(value)
        }
        task.addOnFailureListener {
            if (cont.isActive) cont.resume(null)
        }
        task.addOnCanceledListener {
            if (cont.isActive) cont.resume(null)
        }
    }

    private companion object {
        const val UNDETERMINED = "und"
        const val MODEL_TIMEOUT_MS = 60_000L
        const val TRANSLATE_TIMEOUT_MS = 10_000L
        const val DETECT_TIMEOUT_MS = 5_000L
    }
}
