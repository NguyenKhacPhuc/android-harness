package dev.weft.compose.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * The app design tokens handed to a mini-app, already resolved to
 * web-ready values (hex colors, px font size). Produced from the live
 * `MaterialTheme` via [rememberMiniAppThemeTokens]; consumed by
 * [MiniAppTheme] to build the injected `<style>` / `<script>`.
 *
 * Resolving against `MaterialTheme` means the values already reflect
 * light vs dark — when the host theme flips, recomposition yields new
 * tokens and the mini-app re-decorates.
 */
public data class MiniAppThemeTokens(
    public val primary: String,
    public val onPrimary: String,
    public val background: String,
    public val onBackground: String,
    public val surface: String,
    public val onSurface: String,
    public val outline: String,
    public val bodyFontSizePx: Float,
)

/**
 * A per-mini-app theme override. Every field is optional — a non-null
 * value replaces the corresponding inherited app token, a null leaves
 * it as the app default. Lets a single mini-app diverge from the app
 * chrome (a distinct palette, a larger base font) while still
 * inheriting everything it doesn't override.
 *
 * `@Serializable` so it can ride along in a mini-app's stored payload:
 * the host catalog persists it per mini-app and the agent can author
 * it declaratively. Applied via [MiniAppThemeTokens.overlay].
 */
@Serializable
public data class MiniAppThemeOverride(
    public val primary: String? = null,
    public val onPrimary: String? = null,
    public val background: String? = null,
    public val onBackground: String? = null,
    public val surface: String? = null,
    public val onSurface: String? = null,
    public val outline: String? = null,
    public val bodyFontSizePx: Float? = null,
)

/**
 * Overlay [override] onto these inherited app tokens: each non-null
 * field in the override wins, everything else stays as the app default.
 * A `null` override is the identity (pure app inheritance).
 */
public fun MiniAppThemeTokens.overlay(override: MiniAppThemeOverride?): MiniAppThemeTokens {
    if (override == null) return this
    return copy(
        primary = override.primary ?: primary,
        onPrimary = override.onPrimary ?: onPrimary,
        background = override.background ?: background,
        onBackground = override.onBackground ?: onBackground,
        surface = override.surface ?: surface,
        onSurface = override.onSurface ?: onSurface,
        outline = override.outline ?: outline,
        bodyFontSizePx = override.bodyFontSizePx ?: bodyFontSizePx,
    )
}

/**
 * Builds the theme surface injected into a mini-app's HTML so it reads
 * as part of the app by default:
 *  - a `<style>` block exposing the app tokens as CSS custom properties
 *    (`--weft-color-*`) plus base rules that adopt them, so even a
 *    plain mini-app inherits the app's colors and typography.
 *  - a `<script>` exposing the same tokens on `window.weft.theme` for
 *    mini-apps that style dynamically (only injected when scripts run).
 *
 * Pure string assembly — no Compose, no WebView — so it's unit tested
 * directly. [rememberMiniAppThemeTokens] is the only Composable seam.
 */
public object MiniAppTheme {

    /** The `<style>` block: CSS custom properties + base rules adopting them. */
    public fun styleTag(tokens: MiniAppThemeTokens): String = """
        <style>
        :root {
          --weft-color-primary: ${tokens.primary};
          --weft-color-on-primary: ${tokens.onPrimary};
          --weft-color-background: ${tokens.background};
          --weft-color-on-background: ${tokens.onBackground};
          --weft-color-surface: ${tokens.surface};
          --weft-color-on-surface: ${tokens.onSurface};
          --weft-color-outline: ${tokens.outline};
          --weft-font-size-body: ${tokens.bodyFontSizePx}px;
        }
        html, body {
          margin: 0;
          padding: 8px;
          background: var(--weft-color-background);
          color: var(--weft-color-on-background);
          font-family: -apple-system, system-ui, "Segoe UI", Roboto, sans-serif;
          font-size: var(--weft-font-size-body);
        }
        a { color: var(--weft-color-primary); }
        button {
          background: var(--weft-color-primary);
          color: var(--weft-color-on-primary);
          border: none;
          border-radius: 8px;
          padding: 8px 14px;
          font-size: var(--weft-font-size-body);
        }
        </style>
    """.trimIndent()

    /** The `<script>` exposing tokens on `window.weft.theme` for dynamic styling. */
    public fun themeScript(tokens: MiniAppThemeTokens): String =
        "<script>window.weft = window.weft || {}; window.weft.theme = ${themeJson(tokens)};</script>"

    /**
     * The sealing `<meta>` Content-Security-Policy injected ahead of every
     * mini-app document. A mini-app's only path to the outside is the
     * approved-action bridge, so the page itself is locked down:
     *  - `default-src 'none'` — deny everything not explicitly allowed.
     *  - `connect-src 'none'` — no `fetch` / `XHR` / `WebSocket`; the
     *    mini-app cannot make network calls of its own.
     *  - `script-src` / `style-src 'unsafe-inline'` — self-contained
     *    inline widgets + the injected theme still run; no *remote* code.
     *  - `img-src https: data:` — remote https images (galleries, avatars,
     *    photos) + inline data URIs. `connect-src` stays `'none'`, so the
     *    residual exfiltration surface is a GET-only image ping with no
     *    readable response — accepted for usability over a dead gallery.
     *  - `font-src data:` — inline fonts only, no remote font fetches.
     *  - `base-uri 'none'`, `form-action 'none'`, `frame-src 'none'` — no
     *    base hijack, no form posts off-origin, no iframes.
     * Combined with the WebView's null base URL and the platform
     * navigation guard, this seals the side doors around the bridge.
     */
    public const val MINI_APP_CSP: String =
        "default-src 'none'; " +
            "script-src 'unsafe-inline'; " +
            "style-src 'unsafe-inline'; " +
            "img-src https: data:; " +
            "font-src data:; " +
            "connect-src 'none'; " +
            "base-uri 'none'; " +
            "form-action 'none'; " +
            "frame-src 'none'"

    /** The CSP as a `<meta http-equiv>` tag, honored by Android WebView + WKWebView. */
    public fun cspMetaTag(): String =
        "<meta http-equiv=\"Content-Security-Policy\" content=\"$MINI_APP_CSP\">"

    /**
     * Prepend the sealing [cspMetaTag] (always, first), the theme
     * [styleTag] (always) and, when [includeThemeScript], the
     * [themeScript] to the mini-app's [html]. The mini-app content is
     * left untouched after the injected head.
     */
    public fun decorate(html: String, tokens: MiniAppThemeTokens, includeThemeScript: Boolean): String =
        buildString {
            append(cspMetaTag())
            append('\n')
            append(styleTag(tokens))
            if (includeThemeScript) {
                append('\n')
                append(themeScript(tokens))
            }
            append('\n')
            append(html)
        }

    private fun themeJson(t: MiniAppThemeTokens): String =
        """{"primary":"${t.primary}","onPrimary":"${t.onPrimary}",""" +
            """"background":"${t.background}","onBackground":"${t.onBackground}",""" +
            """"surface":"${t.surface}","onSurface":"${t.onSurface}",""" +
            """"outline":"${t.outline}","bodyFontSizePx":${t.bodyFontSizePx}}"""
}

/** Resolve [Color] to a `#rrggbb` CSS hex string (alpha dropped). */
internal fun Color.toCssHex(): String {
    fun component(channel: Float): String =
        (channel * COLOR_MAX).roundToInt().coerceIn(0, COLOR_MAX).toString(HEX_RADIX).padStart(2, '0')
    return "#${component(red)}${component(green)}${component(blue)}"
}

/**
 * Snapshot the live `MaterialTheme` (colors + body typography) into
 * [MiniAppThemeTokens]. Recomputes when the scheme or typography object
 * changes — i.e. on a light/dark flip — so the injected theme tracks
 * the app.
 */
@Composable
public fun rememberMiniAppThemeTokens(): MiniAppThemeTokens {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    return remember(scheme, typography) {
        MiniAppThemeTokens(
            primary = scheme.primary.toCssHex(),
            onPrimary = scheme.onPrimary.toCssHex(),
            background = scheme.background.toCssHex(),
            onBackground = scheme.onBackground.toCssHex(),
            surface = scheme.surface.toCssHex(),
            onSurface = scheme.onSurface.toCssHex(),
            outline = scheme.outline.toCssHex(),
            bodyFontSizePx = typography.bodyLarge.fontSize.value,
        )
    }
}

private const val COLOR_MAX = 255
private const val HEX_RADIX = 16
