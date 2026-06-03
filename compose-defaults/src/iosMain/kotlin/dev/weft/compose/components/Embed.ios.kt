package dev.weft.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import dev.weft.contracts.ComponentCategory
import dev.weft.contracts.ComponentEvent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.darwin.NSObject

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

public class HtmlComponent(
    private val invoker: MiniAppActionInvoker? = null,
    private val scopeResolver: MiniAppScopeResolver? = null,
    private val stateStore: MiniAppStateStore? = null,
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
        // for scripts. Tokens track light/dark via MaterialTheme; a per
        // mini-app override (props.theme) wins over the inherited defaults.
        val tokens = rememberMiniAppThemeTokens().overlay(props.theme)
        val decorated = MiniAppTheme.decorate(props.html, tokens, props.runScripts)
        // Bridge is live only when the host supplied an invoker AND the
        // mini-app opted into scripts. Otherwise the WKWebView is sandboxed.
        // The host's scope resolver gates it to this mini-app's approved set.
        val approved = scopeResolver?.invoke(props.miniAppId)
        val bridge = remember(invoker, approved, stateStore, props.miniAppId) {
            invoker?.let { MiniAppBridge(it, approved, stateStore, props.miniAppId) }
        }
        val bridged = bridge != null && props.runScripts
        val scope = rememberCoroutineScope()
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
                    val handler = if (bridge != null && bridged) {
                        IosWeftMessageHandler(bridge, scope).also { h ->
                            config.userContentController.addScriptMessageHandler(h, name = WEFT_MESSAGE_NAME)
                            config.userContentController.addUserScript(
                                WKUserScript(
                                    source = MiniAppBridge.jsShim(
                                        "window.webkit.messageHandlers.$WEFT_MESSAGE_NAME.postMessage(msg);",
                                    ),
                                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                                    forMainFrameOnly = true,
                                ),
                            )
                        }
                    } else {
                        null
                    }
                    val webView = WKWebView(
                        frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                        configuration = config,
                    )
                    handler?.bind(webView)
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

/** Handler name the `window.weft` shim posts to — see [MiniAppBridge.jsShim]. */
private const val WEFT_MESSAGE_NAME = "weft"

/**
 * `WKScriptMessageHandler` the injected shim posts call payloads to.
 * Runs the suspend dispatch on [scope], then evaluates the
 * resolve/reject JS back in the bound web view.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosWeftMessageHandler(
    private val bridge: MiniAppBridge,
    private val scope: CoroutineScope,
) : NSObject(), WKScriptMessageHandlerProtocol {

    private var webView: WKWebView? = null

    fun bind(view: WKWebView) {
        webView = view
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val payload = didReceiveScriptMessage.body as? String ?: return
        scope.launch {
            val js = bridge.handle(payload)
            if (js.isNotEmpty()) {
                webView?.evaluateJavaScript(js, completionHandler = null)
            }
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
