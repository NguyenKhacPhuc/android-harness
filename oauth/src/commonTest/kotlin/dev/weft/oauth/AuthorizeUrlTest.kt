package dev.weft.oauth

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.http.Url

class AuthorizeUrlTest : BehaviorSpec({

    val baseConfig = OAuthConfig(
        clientId = "client-123",
        authorizationEndpoint = "https://provider.example/oauth/authorize",
        tokenEndpoint = "https://provider.example/oauth/token",
        redirectUri = "undercurrent://oauth/acme",
    )

    Given("a config, state and PKCE challenge") {
        When("the authorize URL is built") {
            val params = Url(AuthorizeUrl.build(baseConfig, state = "st-abc", challenge = "ch-xyz")).parameters
            Then("it carries the required Authorization Code + PKCE params") {
                params["response_type"] shouldBe "code"
                params["client_id"] shouldBe "client-123"
                params["redirect_uri"] shouldBe "undercurrent://oauth/acme"
                params["state"] shouldBe "st-abc"
                params["code_challenge"] shouldBe "ch-xyz"
                params["code_challenge_method"] shouldBe "S256"
            }
        }
    }

    Given("scopes on the config") {
        When("the URL is built") {
            val url = AuthorizeUrl.build(baseConfig.copy(scopes = listOf("read", "write")), "s", "c")
            Then("scopes join space-separated") {
                Url(url).parameters["scope"] shouldBe "read write"
            }
        }
    }

    Given("no scopes") {
        Then("the scope param is omitted entirely, not emitted empty") {
            AuthorizeUrl.build(baseConfig, "s", "c") shouldNotContain "scope="
        }
    }

    Given("extra auth params") {
        When("the URL is built") {
            val url = AuthorizeUrl.build(
                baseConfig.copy(extraAuthParams = mapOf("prompt" to "consent", "audience" to "api")),
                "s",
                "c",
            )
            Then("they are appended to the query") {
                url shouldContain "prompt=consent"
                url shouldContain "audience=api"
            }
        }
    }
})
