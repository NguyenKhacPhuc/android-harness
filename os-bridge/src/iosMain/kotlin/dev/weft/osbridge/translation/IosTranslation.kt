package dev.weft.osbridge.translation

import dev.weft.contracts.Translation

/**
 * iOS stub for [Translation]. Every method throws [NotImplementedError]
 * via [TODO] until somebody wires the iOS-native API.
 *
 * Native API to wrap: `Translation.framework` (iOS 17.4+) —
 * `TranslationSession.translate(_:)` with downloadable on-device models,
 * mirroring the ML Kit shape on Android. For language detection use
 * `NaturalLanguage.NLLanguageRecognizer` —
 * `processString(_:)` + `dominantLanguage`. The Translation framework
 * exposes `availableLanguages` for the supported list.
 *
 * On iOS <17.4 there's no public on-device translation API; fall back
 * to a host-supplied translator (cloud service) or return null.
 *
 * Open so hosts can subclass and override individual methods as they
 * implement them piecewise.
 *
 * See `docs/architecture/ios-os-capabilities.md` for effort estimates,
 * priority ordering, and what substrate tools each method unblocks.
 */
public open class IosTranslation : Translation {
    override suspend fun translate(text: String, target: String, source: String?): String? =
        TODO("IosTranslation.translate — wrap TranslationSession.translate(_:) (iOS 17.4+) or return null on older versions")

    override suspend fun detectLanguage(text: String): String =
        TODO("IosTranslation.detectLanguage — wrap NLLanguageRecognizer.processString + dominantLanguage, return rawValue or \"und\"")

    override suspend fun supportedLanguages(): List<String> =
        TODO("IosTranslation.supportedLanguages — wrap Translation.availableLanguages (iOS 17.4+)")
}
