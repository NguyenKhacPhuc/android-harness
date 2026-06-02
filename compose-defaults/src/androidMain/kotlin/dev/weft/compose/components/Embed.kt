package dev.weft.compose.components
import dev.weft.contracts.ComponentEvent
import dev.weft.contracts.ComponentCategory

import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.Serializable

/**
 * [ComponentCategory.EMBED] components — wrap external content. Today
 * just web pages; future video/audio players slot here too.
 */

@Serializable
public data class WebViewProps(
    val url: String,
    /** Token: "md" (300dp), "lg" (500dp), "xl" (700dp), "fill" (default — full screen height). */
    val height: String = "fill",
    val title: String = "",
)

public class WebViewComponent : WeftComponent<WebViewProps>(
    name = "WebView",
    description = "Embed a web page inside the app (user stays in the app, sees your top bar). Required: url. Optional: height (token, default 'fill'), title. Use this when content needs to come from the web but the user shouldn't have to leave the app — Wikipedia articles, news pages, generic search results. Prefer rendering proper UI components over WebView; prefer WebView over external_open_url.",
    category = ComponentCategory.EMBED,
    propsSerializer = WebViewProps.serializer(),
) {
    @Composable
    override fun Render(props: WebViewProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        val heightMod = when (props.height.lowercase()) {
            "md" -> Modifier.height(300.dp)
            "lg" -> Modifier.height(500.dp)
            "xl" -> Modifier.height(700.dp)
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
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl(props.url)
                    }
                },
                update = { webView ->
                    if (webView.url != props.url) webView.loadUrl(props.url)
                },
                modifier = Modifier.fillMaxWidth().then(heightMod),
            )
        }
    }

    public companion object {
        private const val WEBVIEW_DEFAULT_HEIGHT_DP = 600
    }
}

// ============================================================================
// Html — render a raw HTML snippet inline (no URL fetch)
// ============================================================================

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
     * Still sandboxed: no native bridge, and cross-origin XHR is blocked
     * because the WebView has a null base URL.
     *
     * Default false (safer; display-only). Opt in only when the LLM is
     * emitting a self-contained widget that needs client-side behavior.
     */
    val runScripts: Boolean = false,
)

/**
 * Renders an LLM-emitted HTML snippet in a minimal inline WebView.
 * JavaScript is disabled — safer for LLM-generated content. Use for
 * rich-text formatting (lists, bold/italic, links, headings, simple
 * inline CSS) that doesn't fit cleanly in a Text component.
 *
 * Different from [WebViewComponent]: that one loads a URL; this one
 * takes raw HTML directly via `loadDataWithBaseURL`.
 */
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
    @Composable
    override fun Render(props: HtmlProps, children: @Composable () -> Unit, onEvent: (ComponentEvent) -> Unit) {
        // Resolve "default" against runScripts — interactive widgets need
        // room for canvas + controls; rich-text snippets do not.
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
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        // JS opt-in: still sandboxed (no addJavascriptInterface, no base URL
                        // so cross-origin XHR is blocked). Default off — see HtmlProps.
                        settings.javaScriptEnabled = props.runScripts
                        settings.defaultTextEncodingName = "utf-8"
                        // domStorage allows localStorage/sessionStorage in self-contained
                        // widgets — only available when scripts are enabled anyway.
                        settings.domStorageEnabled = props.runScripts
                        webViewClient = WebViewClient()
                        loadDataWithBaseURL(null, decorated, "text/html", "utf-8", null)
                    }
                },
                update = { webView ->
                    // Reload when the decorated document changes — covers html,
                    // runScripts, AND a theme (light/dark) flip in one key.
                    val key = "${props.runScripts}:$decorated"
                    val lastLoaded = webView.getTag(R_ID_LAST_HTML) as? String
                    if (lastLoaded != key) {
                        webView.settings.javaScriptEnabled = props.runScripts
                        webView.settings.domStorageEnabled = props.runScripts
                        webView.loadDataWithBaseURL(null, decorated, "text/html", "utf-8", null)
                        webView.setTag(R_ID_LAST_HTML, key)
                    }
                },
                modifier = Modifier.fillMaxWidth().then(heightMod),
            )
        }
    }

    public companion object {
        private const val HTML_WRAP_DP = 200
        private const val HTML_MD_DP = 300
        private const val HTML_LG_DP = 500
        private const val HTML_XL_DP = 700
        private const val HTML_FILL_DP = 600

        // Arbitrary tag id — must be unique within this View. Using a high
        // negative-ish constant to avoid clashing with framework ids.
        private const val R_ID_LAST_HTML = 0x7F00_AB01
    }
}

/**
 * Android's [ComponentCategory.EMBED] palette — backed by
 * `android.webkit.WebView`. iOS provides an empty actual until a
 * WKWebView wrapper ships.
 */
public actual val EmbedComponents: List<WeftComponent<*>> = listOf(
    WebViewComponent(),
    HtmlComponent(),
)
