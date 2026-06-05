package dev.weft.compose.components
import dev.weft.contracts.ComponentEvent
import dev.weft.contracts.ComponentCategory

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    /**
     * Optional per-mini-app theme override. Each non-null field replaces
     * the inherited app token; null fields keep the app default. Lets one
     * mini-app diverge from the app chrome while still inheriting the rest.
     * Default null ⇒ pure app inheritance.
     */
    val theme: MiniAppThemeOverride? = null,
    /**
     * Stable, non-sensitive identity for this mini-app. The host's scope
     * resolver maps it to the approved action set enforced at the bridge.
     * It carries identity only — never the grant itself — so an
     * agent-authored value can't widen what the mini-app may reach.
     */
    val miniAppId: String? = null,
)

/**
 * Renders an LLM-emitted HTML snippet in a minimal inline WebView.
 * JavaScript is disabled — safer for LLM-generated content. Use for
 * rich-text formatting (lists, bold/italic, links, headings, simple
 * inline CSS) that doesn't fit cleanly in a Text component.
 *
 * Different from [WebViewComponent]: that one loads a URL; this one
 * takes raw HTML directly via `loadDataWithBaseURL`.
 *
 * When constructed with a non-null [invoker] and rendered with
 * `runScripts = true`, the page gains the `window.weft` bridge: its
 * script can `callTool(name, args)` and `await` the host's result.
 * Left `null` (the default), the WebView stays fully sandboxed — no
 * `addJavascriptInterface`, exactly as before.
 *
 * [scopeResolver] (host-supplied) gates the bridge per mini-app: it
 * maps the rendered mini-app's id to its approved action set, and the
 * bridge refuses anything outside it. Left `null`, the bridge is
 * ungated (trusted) — same as before scope enforcement landed.
 */
public class HtmlComponent(
    private val invoker: MiniAppActionInvoker? = null,
    private val scopeResolver: MiniAppScopeResolver? = null,
    private val stateStore: MiniAppStateStore? = null,
    private val dataSource: MiniAppDataSource? = null,
    private val assistant: MiniAppAssistantHandler? = null,
) : WeftComponent<HtmlProps>(
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
        // for scripts. Tokens track light/dark via MaterialTheme; a per
        // mini-app override (props.theme) wins over the inherited defaults.
        val tokens = rememberMiniAppThemeTokens().overlay(props.theme)
        val decorated = MiniAppTheme.decorate(props.html, tokens, props.runScripts)
        // Bridge is live only when the host supplied an invoker AND the
        // mini-app opted into scripts. Otherwise the WebView is sandboxed.
        // The host's scope resolver gates it to this mini-app's approved set.
        val approved = scopeResolver?.invoke(props.miniAppId)
        val bridge = remember(invoker, approved, stateStore, props.miniAppId, assistant) {
            invoker?.let { MiniAppBridge(it, approved, stateStore, props.miniAppId, assistant) }
        }
        val bridged = bridge != null && props.runScripts
        val scope = rememberCoroutineScope()
        // Live updates pushed into the running mini-app. The holder lets a
        // LaunchedEffect reach the live WebView without recomposing; when the
        // mini-app leaves composition the effect cancels and pushes stop.
        val webViewRef = remember { arrayOfNulls<WebView>(1) }
        val updates = remember(dataSource, props.miniAppId) { dataSource?.invoke(props.miniAppId) }
        if (bridged && updates != null) {
            LaunchedEffect(updates) {
                updates.collect { dataJson ->
                    webViewRef[0]?.let { wv -> wv.post { wv.evaluateJavascript(MiniAppBridge.pushJs(dataJson), null) } }
                }
            }
        }
        // The shim is injected at document start (prepended to the HTML) so
        // window.weft exists before the mini-app's own script runs and can
        // register onOpen/onData/onClose. Non-bridged mini-apps load as-is.
        val loaded = if (bridged) "${bridgeHeadScript()}\n$decorated" else decorated
        if (bridged) {
            DisposableEffect(Unit) {
                onDispose {
                    webViewRef[0]?.evaluateJavascript(MiniAppBridge.closeJs(), null)
                }
            }
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
                        webViewRef[0] = this
                        // JS opt-in. Sandboxed by default (no base URL → cross-origin
                        // XHR blocked). The window.weft bridge is attached only when
                        // `bridged` — see HtmlProps / HtmlComponent(invoker).
                        settings.javaScriptEnabled = props.runScripts
                        settings.defaultTextEncodingName = "utf-8"
                        // domStorage allows localStorage/sessionStorage in self-contained
                        // widgets — only available when scripts are enabled anyway.
                        settings.domStorageEnabled = props.runScripts
                        webViewClient = if (bridge != null && bridged) {
                            attachWeftBridge(bridge, scope)
                        } else {
                            MiniAppWebViewClient()
                        }
                        loadDataWithBaseURL(null, loaded, "text/html", "utf-8", null)
                    }
                },
                update = { webView ->
                    // Reload when the loaded document changes — covers html,
                    // runScripts, AND a theme (light/dark) flip in one key.
                    val key = "${props.runScripts}:$loaded"
                    val lastLoaded = webView.getTag(R_ID_LAST_HTML) as? String
                    if (lastLoaded != key) {
                        webView.settings.javaScriptEnabled = props.runScripts
                        webView.settings.domStorageEnabled = props.runScripts
                        webView.loadDataWithBaseURL(null, loaded, "text/html", "utf-8", null)
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

/** Name the `window.weft` shim posts to — see [MiniAppBridge.jsShim]. */
private const val JS_BRIDGE_NAME = "AndroidWeftBridge"

/** The `window.weft` shim as a document-start `<script>` for the loaded HTML. */
private fun bridgeHeadScript(): String =
    "<script>${MiniAppBridge.jsShim("$JS_BRIDGE_NAME.postMessage(msg);")}</script>"

/**
 * Attach the `window.weft` bridge to this WebView: register the
 * `@JavascriptInterface` transport (available before load, so the
 * document-start shim can post through it) and return a [WebViewClient]
 * that fires `onOpen` once the page — and its `onOpen` registration —
 * has loaded.
 */
private fun WebView.attachWeftBridge(bridge: MiniAppBridge, scope: CoroutineScope): WebViewClient {
    val webView = this
    addJavascriptInterface(
        AndroidWeftBridge(bridge, scope) { js -> webView.post { webView.evaluateJavascript(js, null) } },
        JS_BRIDGE_NAME,
    )
    return object : MiniAppWebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            view?.evaluateJavascript(MiniAppBridge.openJs(), null)
        }
    }
}

/**
 * [WebViewClient] for HTML mini-apps: blocks **main-frame** navigation
 * away from the loaded document — link clicks, redirects, and form
 * submits can't replace the mini-app with another page. Sub-frame loads
 * (iframes, gated by the CSP's `frame-src`) are allowed through. (The
 * URL-loading [WebViewComponent] keeps the default client; navigation is
 * its point.)
 */
private open class MiniAppWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        request?.isForMainFrame != false
}

/**
 * The `@JavascriptInterface` object the shim posts call payloads to.
 * Runs the suspend dispatch off the JS thread on [scope], then hands
 * the resolve/reject JS to [evalOnMain].
 */
private class AndroidWeftBridge(
    private val bridge: MiniAppBridge,
    private val scope: CoroutineScope,
    private val evalOnMain: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(payload: String) {
        scope.launch {
            val js = bridge.handle(payload)
            if (js.isNotEmpty()) evalOnMain(js)
        }
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
