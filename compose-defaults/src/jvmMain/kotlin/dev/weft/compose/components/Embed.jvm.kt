package dev.weft.compose.components

/**
 * Desktop / pure-JVM consumers get an empty embed palette. WebView /
 * Html depend on `android.webkit.WebView`; a Compose Desktop port would
 * use JCEF or similar. Hosts on JVM that need embedded web content
 * can ship their own component via `WeftUi.extraComponents`.
 */
public actual val EmbedComponents: List<WeftComponent<*>> = emptyList()
