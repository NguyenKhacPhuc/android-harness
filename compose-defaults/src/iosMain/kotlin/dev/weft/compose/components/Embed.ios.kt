package dev.weft.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import dev.weft.contracts.ComponentCategory
import dev.weft.contracts.ComponentEvent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences

/**
 * iOS counterpart of the substrate's `:compose-defaults` androidMain
 * `WebViewComponent` — embeds a web page using `WKWebView` wired through
 * Compose Multiplatform's `UIKitView` interop.
 *
 * Same prop shape + LLM-facing description as the Android variant so
 * the agent emits identical `{"type": "WebView", ...}` payloads
 * regardless of target; only the renderer differs.
 */
@Serializable
public data class WebViewProps(
    val url: String,
    /** Token: "md" (300dp), "lg" (500dp), "xl" (700dp), "fill" (default — 600dp). */
    val height: String = "fill",
    val title: String = "",
)

public class WebViewComponent : WeftComponent<WebViewProps>(
    name = "WebView",
    description = "Embed a web page inside the app (user stays in the app, sees your top bar). " +
        "Required: url. Optional: height (token, default 'fill'), title. Use this when content " +
        "needs to come from the web but the user shouldn't have to leave the app — Wikipedia " +
        "articles, news pages, generic search results. Prefer rendering proper UI components " +
        "over WebView; prefer WebView over external_open_url.",
    category = ComponentCategory.EMBED,
    propsSerializer = WebViewProps.serializer(),
) {
    @OptIn(ExperimentalForeignApi::class)
    @Composable
    override fun Render(
        props: WebViewProps,
        children: @Composable () -> Unit,
        onEvent: (ComponentEvent) -> Unit,
    ) {
        val heightMod = when (props.height.lowercase()) {
            "md" -> Modifier.height(WEBVIEW_MD_DP.dp)
            "lg" -> Modifier.height(WEBVIEW_LG_DP.dp)
            "xl" -> Modifier.height(WEBVIEW_XL_DP.dp)
            else -> Modifier.height(WEBVIEW_DEFAULT_HEIGHT_DP.dp)
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (props.title.isNotBlank()) {
                Text(
                    text = props.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            UIKitView(
                factory = {
                    val webView = WKWebView(
                        frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                        configuration = WKWebViewConfiguration(),
                    )
                    val nsurl = NSURL.URLWithString(props.url)
                    if (nsurl != null) {
                        webView.loadRequest(NSURLRequest.requestWithURL(nsurl))
                    }
                    webView
                },
                modifier = Modifier.fillMaxWidth().then(heightMod),
                update = { webView ->
                    val current = webView.URL?.absoluteString
                    if (current != props.url) {
                        val nsurl = NSURL.URLWithString(props.url)
                        if (nsurl != null) {
                            webView.loadRequest(NSURLRequest.requestWithURL(nsurl))
                        }
                    }
                },
            )
        }
    }
}

/**
 * iOS counterpart of the substrate's `:compose-defaults` androidMain
 * `HtmlComponent` — renders a raw HTML snippet using
 * `WKWebView.loadHTMLString` wired through `UIKitView`. JS opt-in goes
 * through `WKWebpagePreferences.allowsContentJavaScript` (the modern
 * per-navigation API; the older `WKPreferences.javaScriptEnabled` was
 * deprecated in iOS 14 and isn't in the K/N bindings).
 *
 * Same prop shape + LLM-facing description as the Android variant.
 */
@Serializable
public data class HtmlProps(
    /** The HTML content to render. Supports basic tags + inline CSS. */
    val html: String,
    /**
     * Token: "wrap" (200dp), "md" (300dp), "lg" (500dp), "xl" (700dp),
     * "fill" (600dp full-width). Default is content-aware: "wrap" for
     * the rich-text path (200dp suits a paragraph or two), "xl" for the
     * interactive path (`runScripts = true`) where a 200dp widget would
     * always crush its own controls. Set explicitly to override.
     */
    val height: String = "default",
    val title: String = "",
    /**
     * If true, JavaScript runs inside this HTML — enables self-contained
     * interactive widgets (calculator, countdown, animations, drag-drop).
     * Still sandboxed: no native bridge.
     *
     * Default false (safer; display-only). Opt in only when the LLM is
     * emitting a self-contained widget that needs client-side behavior.
     */
    val runScripts: Boolean = false,
)

public class HtmlComponent : WeftComponent<HtmlProps>(
    name = "Html",
    description = "Render a raw HTML snippet inline (no URL). Required: html (string). " +
        "Optional: title, height (token), runScripts (default false). " +
        "Use for: rich text / prose with formatting (lists, bold, headings, links), " +
        "and — when runScripts=true — self-contained interactive widgets (calculator, " +
        "countdown, animations, drag-drop). With runScripts=true, JS is sandboxed: " +
        "no native bridge, no cross-origin XHR. " +
        "Use Material components instead when the UI needs to talk to the agent (fire events) " +
        "or trigger device features (notifications, sharing, calendar, etc).",
    category = ComponentCategory.EMBED,
    propsSerializer = HtmlProps.serializer(),
    example = """{"type": "Html", "props": {"html": "<h2>Summary</h2><ul><li>Point one</li><li>Point two with <b>emphasis</b></li></ul>"}}""",
) {
    @OptIn(ExperimentalForeignApi::class)
    @Composable
    override fun Render(
        props: HtmlProps,
        children: @Composable () -> Unit,
        onEvent: (ComponentEvent) -> Unit,
    ) {
        // Resolve "default" against runScripts — same logic the Android
        // HtmlComponent applies. Interactive widgets need room for
        // canvas + controls; rich-text snippets do not.
        val resolvedHeight = when (props.height.lowercase()) {
            "default" -> if (props.runScripts) "xl" else "wrap"
            else -> props.height.lowercase()
        }
        val heightMod = when (resolvedHeight) {
            "md" -> Modifier.height(HTML_MD_DP.dp)
            "lg" -> Modifier.height(HTML_LG_DP.dp)
            "xl" -> Modifier.height(HTML_XL_DP.dp)
            "fill" -> Modifier.fillMaxWidth().height(HTML_FILL_DP.dp)
            else -> Modifier.height(HTML_WRAP_DP.dp)
        }
        // Inject the app's theme so the mini-app reads as part of the app:
        // CSS custom properties + base rules (always), plus window.weft.theme
        // for scripts. Tokens track light/dark via MaterialTheme.
        val tokens = rememberMiniAppThemeTokens()
        val decorated = MiniAppTheme.decorate(props.html, tokens, props.runScripts)
        Column(modifier = Modifier.fillMaxWidth()) {
            if (props.title.isNotBlank()) {
                Text(
                    text = props.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            UIKitView(
                factory = {
                    val config = WKWebViewConfiguration()
                    config.defaultWebpagePreferences = WKWebpagePreferences().apply {
                        allowsContentJavaScript = props.runScripts
                    }
                    val webView = WKWebView(
                        frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                        configuration = config,
                    )
                    webView.loadHTMLString(decorated, baseURL = null)
                    webView
                },
                modifier = Modifier.fillMaxWidth().then(heightMod),
                update = { webView ->
                    // Reload the theme-decorated document — recomposition on a
                    // light/dark flip yields new tokens, so the look updates.
                    webView.loadHTMLString(decorated, baseURL = null)
                },
            )
        }
    }
}

/**
 * iOS [ComponentCategory.EMBED] palette. Mirrors the androidMain set
 * (WebView + Html), now backed by `WKWebView` instead of
 * `android.webkit.WebView`.
 */
public actual val EmbedComponents: List<WeftComponent<*>> = listOf(
    WebViewComponent(),
    HtmlComponent(),
)

// ─── Dimension tokens (mirror the substrate's androidMain Embed.kt) ─────

private const val WEBVIEW_DEFAULT_HEIGHT_DP = 600
private const val WEBVIEW_MD_DP = 300
private const val WEBVIEW_LG_DP = 500
private const val WEBVIEW_XL_DP = 700

private const val HTML_WRAP_DP = 200
private const val HTML_MD_DP = 300
private const val HTML_LG_DP = 500
private const val HTML_XL_DP = 700
private const val HTML_FILL_DP = 600
