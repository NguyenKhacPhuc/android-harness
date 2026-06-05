package dev.weft.compose.components

import androidx.compose.ui.graphics.Color
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/**
 * Pure-logic tests for the mini-app theme injection. The CSS/JS strings
 * are produced from already-resolved tokens, so the whole thing is
 * testable without Compose or a WebView. Reading the live MaterialTheme
 * is a thin @Composable shim verified by compilation.
 */
class MiniAppThemeTest {

    private val tokens = MiniAppThemeTokens(
        primary = "#3366cc",
        onPrimary = "#ffffff",
        background = "#101418",
        onBackground = "#e2e2e6",
        surface = "#1a1f24",
        onSurface = "#e2e2e6",
        outline = "#8b9198",
        bodyFontSizePx = 16f,
    )

    @Test
    fun colorConvertsToSixDigitCssHex() {
        Color(0xFF3366CC).toCssHex() shouldBe "#3366cc"
        Color(0xFF000000).toCssHex() shouldBe "#000000"
        Color(0xFFFFFFFF).toCssHex() shouldBe "#ffffff"
    }

    @Test
    fun styleTagDeclaresThemeTokensAsCssCustomProperties() {
        val style = MiniAppTheme.styleTag(tokens)
        style shouldContain "<style>"
        style shouldContain "--weft-color-primary: #3366cc"
        style shouldContain "--weft-color-background: #101418"
        // base rules pull from the tokens so a default mini-app reads native
        style shouldContain "var(--weft-color-background)"
        style shouldContain "var(--weft-color-on-background)"
    }

    @Test
    fun themeScriptExposesTokensOnWindowWeftTheme() {
        val script = MiniAppTheme.themeScript(tokens)
        script shouldContain "window.weft = window.weft || {}"
        script shouldContain "window.weft.theme"
        script shouldContain "\"primary\":\"#3366cc\""
    }

    @Test
    fun decoratePrependsHeadBeforeTheMiniAppHtml() {
        val out = MiniAppTheme.decorate("<h1>Hi</h1>", tokens, includeThemeScript = false)
        // CSP meta leads, then the style tag, both before the mini-app html
        (out.indexOf("Content-Security-Policy") < out.indexOf("<style>")) shouldBe true
        (out.indexOf("<style>") < out.indexOf("<h1>Hi</h1>")) shouldBe true
        out shouldNotContain "window.weft.theme"
    }

    @Test
    fun cspMetaSealsNetworkAndNavigation() {
        val csp = MiniAppTheme.cspMetaTag()
        csp shouldContain "http-equiv=\"Content-Security-Policy\""
        // the mini-app's only path out is the bridge — its own network is dead
        csp shouldContain "connect-src 'none'"
        csp shouldContain "default-src 'none'"
        // no navigation away, no form posts, no base hijack, no iframes
        csp shouldContain "base-uri 'none'"
        csp shouldContain "form-action 'none'"
        csp shouldContain "frame-src 'none'"
        // self-contained inline widgets still run
        csp shouldContain "script-src 'unsafe-inline'"
        // remote https images allowed (galleries) but fetch/XHR still blocked
        csp shouldContain "img-src https: data:"
    }

    @Test
    fun decorateLeadsWithTheSealingCsp() {
        val out = MiniAppTheme.decorate("<h1>Hi</h1>", tokens, includeThemeScript = true)
        // the document leads with the CSP meta tag
        out.indexOf("<meta") shouldBe 0
        (out.indexOf("Content-Security-Policy") < out.indexOf("window.weft.theme")) shouldBe true
        (out.indexOf("Content-Security-Policy") < out.indexOf("<h1>Hi</h1>")) shouldBe true
    }

    @Test
    fun decorateAddsThemeScriptOnlyWhenScriptsRequested() {
        val out = MiniAppTheme.decorate("<h1>Hi</h1>", tokens, includeThemeScript = true)
        out shouldContain "window.weft.theme"
        (out.indexOf("window.weft.theme") < out.indexOf("<h1>Hi</h1>")) shouldBe true
    }

    @Test
    fun overlayWithNullIsIdentityPureAppInheritance() {
        tokens.overlay(null) shouldBe tokens
    }

    @Test
    fun overlayReplacesOnlyTheSpecifiedFields() {
        val overridden = tokens.overlay(
            MiniAppThemeOverride(primary = "#ff0000", bodyFontSizePx = 20f),
        )
        overridden.primary shouldBe "#ff0000"
        overridden.bodyFontSizePx shouldBe 20f
        // everything not named in the override stays the app default
        overridden.background shouldBe tokens.background
        overridden.onBackground shouldBe tokens.onBackground
        overridden.surface shouldBe tokens.surface
    }

    @Test
    fun overlayedTokensFlowThroughIntoTheInjectedCss() {
        val overridden = tokens.overlay(MiniAppThemeOverride(primary = "#ff0000"))
        MiniAppTheme.styleTag(overridden) shouldContain "--weft-color-primary: #ff0000"
    }
}
