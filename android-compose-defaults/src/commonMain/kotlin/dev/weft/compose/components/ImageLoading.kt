package dev.weft.compose.components

import coil3.ImageLoader
import coil3.PlatformContext

/**
 * Builds the substrate's Coil [ImageLoader] for the [ImageComponent].
 *
 * Today this is a thin wrapper over Coil 3's default loader — image URLs
 * are not host-allowlisted, matching the behavior of every mobile
 * browser and most production apps. The substrate's strong host
 * allowlist still applies to `network_fetch` (where the damage radius is
 * higher: arbitrary HTTP methods, request bodies, response data the
 * agent can read and act on).
 *
 * **KMP.** Coil 3's [PlatformContext] is a typealias — `android.content.Context`
 * on Android, `Any` on iOS — so this factory lives in commonMain and
 * accepts the platform-native context type at the call site.
 *
 * **Security tradeoff (see [docs/follow-ups.md](../../../../../../../../docs/follow-ups.md)).**
 * Image URLs can leak modest data via query params (tracking pixels) and
 * trigger background network requests. The substrate accepts this in
 * exchange for the substrate's "show me a gallery / chart / photo"
 * use cases working without per-host allowlist gymnastics. Apps that
 * need stricter image-host gating can build their own [ImageLoader]
 * with a Ktor / OkHttp interceptor and inject it via custom components.
 *
 * Why keep this as a substrate-owned function rather than relying on
 * Coil's default singleton: it gives apps a single chokepoint (the
 * `WeftUi.imageLoader` field) for connection pooling, cache config,
 * and future per-app interceptors without process-global surgery on
 * Coil's singleton.
 */
public fun buildWeftImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context).build()
