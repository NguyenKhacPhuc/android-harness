package dev.weft.oauth

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class DefaultOAuthClientTest : BehaviorSpec({

    val config = OAuthConfig(
        clientId = "client-123",
        authorizationEndpoint = "https://provider.example/oauth/authorize",
        tokenEndpoint = "https://provider.example/oauth/token",
        redirectUri = "undercurrent://oauth/acme",
    )

    // A BrowserAuthorizer that echoes a fixed redirect outcome, but mirrors
    // the real `state` back so the orchestrator's state check passes on the
    // happy path (the browser is what the provider hands the state to).
    fun fakeBrowser(outcome: (state: String) -> BrowserResult) = object : BrowserAuthorizer {
        override suspend fun authorize(authorizeUrl: String, redirectUri: String, expectedState: String) =
            outcome(expectedState)
    }

    fun tokenEndpoint() = TokenEndpoint(
        HttpClient(
            MockEngine {
                respond(
                    content = """{"access_token":"at-ok","expires_in":3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
    )

    Given("the browser returns a code with the matching state") {
        val client = DefaultOAuthClient(
            browser = fakeBrowser { state -> BrowserResult.Redirect(mapOf("state" to state, "code" to "auth-code")) },
            tokenEndpoint = tokenEndpoint(),
        )
        When("authorize runs") {
            Then("the code is exchanged and Success is returned") {
                runTest {
                    val result = client.authorize(config)
                    result.shouldBeInstanceOf<OAuthResult.Success>()
                    result.tokens.accessToken shouldBe "at-ok"
                }
            }
        }
    }

    Given("the user dismisses the browser") {
        val client = DefaultOAuthClient(fakeBrowser { BrowserResult.Cancelled }, tokenEndpoint())
        Then("authorize returns UserCancelled") {
            runTest { client.authorize(config) shouldBe OAuthResult.UserCancelled }
        }
    }

    Given("the provider redirects with an error param") {
        val client = DefaultOAuthClient(
            fakeBrowser { BrowserResult.Redirect(mapOf("error" to "access_denied", "error_description" to "nope")) },
            tokenEndpoint(),
        )
        Then("authorize returns ProviderError without hitting the token endpoint") {
            runTest {
                val result = client.authorize(config)
                result.shouldBeInstanceOf<OAuthResult.ProviderError>()
                result.code shouldBe "access_denied"
                result.description shouldBe "nope"
            }
        }
    }

    Given("the redirect state does not match the flow state") {
        val client = DefaultOAuthClient(
            fakeBrowser { BrowserResult.Redirect(mapOf("state" to "stale-state", "code" to "auth-code")) },
            tokenEndpoint(),
        )
        Then("authorize returns StateMismatch (CSRF guard)") {
            runTest { client.authorize(config) shouldBe OAuthResult.StateMismatch }
        }
    }

    Given("the redirect has neither code nor error") {
        val client = DefaultOAuthClient(
            fakeBrowser { state -> BrowserResult.Redirect(mapOf("state" to state)) },
            tokenEndpoint(),
        )
        Then("authorize returns a missing_code ProviderError") {
            runTest {
                val result = client.authorize(config)
                result.shouldBeInstanceOf<OAuthResult.ProviderError>()
                result.code shouldBe "missing_code"
            }
        }
    }

    Given("a stored refresh token") {
        val client = DefaultOAuthClient(fakeBrowser { BrowserResult.Cancelled }, tokenEndpoint())
        When("refresh runs") {
            Then("it delegates to the token endpoint and returns Success") {
                runTest {
                    val result = client.refresh(config, refreshToken = "rt-1")
                    result.shouldBeInstanceOf<OAuthResult.Success>()
                    result.tokens.accessToken shouldBe "at-ok"
                }
            }
        }
    }
})
