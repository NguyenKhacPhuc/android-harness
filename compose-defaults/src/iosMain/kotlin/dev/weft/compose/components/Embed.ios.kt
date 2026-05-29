package dev.weft.compose.components

/**
 * iOS gets an empty embed palette for now. WebViewComponent and
 * HtmlComponent depend on `android.webkit.WebView`; a WKWebView-backed
 * port can land here when somebody needs it. Hosts that want
 * rich-text rendering on iOS today can register their own component
 * (e.g. a markdown-rendered Text) via `WeftUi.extraComponents`.
 */
public actual val EmbedComponents: List<WeftComponent<*>> = emptyList()
