package dev.weft.osbridge.translation

import dev.weft.contracts.Translation
import platform.NaturalLanguage.NLLanguageRecognizer

/**
 * iOS [Translation]. Language detection is real (NaturalLanguage's
 * `NLLanguageRecognizer` runs fully offline and returns a BCP-47 tag);
 * [supportedLanguages] is a curated static list of common ISO-639-1
 * codes since NaturalLanguage doesn't enumerate translatable pairs.
 *
 * [translate] is an honest no-op: Apple's on-device Translation
 * framework (iOS 17.4+) is UI/session-gated with no headless string API
 * reachable from Kotlin/Native, so we return null and let the host wire
 * a cloud translator if it needs one.
 *
 * Open so hosts can subclass and override individual methods.
 */
public open class IosTranslation : Translation {

    /**
     * Always null on iOS — the Translation framework requires a
     * SwiftUI `translationTask`/`TranslationSession` bound to a view,
     * with no headless API reachable from Kotlin/Native. Hosts that need
     * translation should subclass and delegate to a cloud service.
     */
    override suspend fun translate(text: String, target: String, source: String?): String? = null

    override suspend fun detectLanguage(text: String): String {
        if (text.isBlank()) return UNDETERMINED
        val recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        return recognizer.dominantLanguage() ?: UNDETERMINED
    }

    override suspend fun supportedLanguages(): List<String> = SUPPORTED

    private companion object {
        const val UNDETERMINED = "und"

        // NaturalLanguage doesn't expose a translatable-pair enumeration;
        // this mirrors the common subset Apple's models cover.
        val SUPPORTED = listOf(
            "en", "es", "fr", "de", "it", "pt", "ru", "zh",
            "ja", "ko", "ar", "hi", "nl", "pl", "tr", "sv",
        )
    }
}
