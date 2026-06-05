package dev.weft.oauth

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class TokenEndpointTest : BehaviorSpec({

    val config = OAuthConfig(
        clientId = "client-123",
        authorizationEndpoint = "https://provider.example/oauth/authorize",
        tokenEndpoint = "https://provider.example/oauth/token",
        redirectUri = "undercurrent://oauth/acme",
    )

    // Captures the last request so we can assert the form body the
    // endpoint posted, plus replays a canned provider response.
    fun endpoint(status: HttpStatusCode, body: String, capture: ((String) -> Unit)? = null): TokenEndpoint {
        val client = HttpClient(
            MockEngine { request: HttpRequestData ->
                val sent = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                capture?.invoke(sent)
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return TokenEndpoint(client)
    }

    Given("a provider that returns a full token bundle") {
        var sentForm = ""
        val te = endpoint(
            HttpStatusCode.OK,
            """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"token_type":"Bearer","scope":"read"}""",
            capture = { sentForm = it },
        )
        When("exchangeCode runs") {
            Then("it returns Success with the parsed tokens and a computed expiry") {
                runTest {
                    val result = te.exchangeCode(config, code = "auth-code", verifier = "ver-1")
                    result.shouldBeInstanceOf<OAuthResult.Success>()
                    result.tokens.accessToken shouldBe "at-1"
                    result.tokens.refreshToken shouldBe "rt-1"
                    result.tokens.tokenType shouldBe "Bearer"
                    result.tokens.scope shouldBe "read"
                    (result.tokens.expiresAtEpochMs > 0L) shouldBe true
                }
            }
            Then("it posts the authorization_code grant with the verifier") {
                runTest {
                    te.exchangeCode(config, code = "auth-code", verifier = "ver-1")
                    sentForm shouldContain "grant_type=authorization_code"
                    sentForm shouldContain "code=auth-code"
                    sentForm shouldContain "code_verifier=ver-1"
                }
            }
        }
    }

    Given("a refresh where the provider omits a new refresh token") {
        var sentForm = ""
        val te = endpoint(
            HttpStatusCode.OK,
            """{"access_token":"at-2","expires_in":3600}""",
            capture = { sentForm = it },
        )
        When("refresh runs with an existing refresh token") {
            Then("it falls back to the caller's refresh token and posts the refresh grant") {
                runTest {
                    val result = te.refresh(config, refreshToken = "rt-old")
                    result.shouldBeInstanceOf<OAuthResult.Success>()
                    result.tokens.accessToken shouldBe "at-2"
                    result.tokens.refreshToken shouldBe "rt-old"
                    sentForm shouldContain "grant_type=refresh_token"
                }
            }
        }
    }

    Given("a token response with no expires_in") {
        val te = endpoint(HttpStatusCode.OK, """{"access_token":"at-3"}""")
        When("exchangeCode runs") {
            Then("expiry is 0 (treated as non-expiring)") {
                runTest {
                    val result = te.exchangeCode(config, "c", "v")
                    result.shouldBeInstanceOf<OAuthResult.Success>()
                    result.tokens.expiresAtEpochMs shouldBe 0L
                    result.tokens.refreshToken.shouldBeNull()
                }
            }
        }
    }

    Given("a provider that returns a structured OAuth error") {
        val te = endpoint(
            HttpStatusCode.BadRequest,
            """{"error":"invalid_grant","error_description":"code expired"}""",
        )
        When("exchangeCode runs") {
            Then("it maps to ProviderError carrying code and description") {
                runTest {
                    val result = te.exchangeCode(config, "c", "v")
                    result.shouldBeInstanceOf<OAuthResult.ProviderError>()
                    result.code shouldBe "invalid_grant"
                    result.description shouldBe "code expired"
                }
            }
        }
    }

    Given("a server error with no structured error body") {
        val te = endpoint(HttpStatusCode.InternalServerError, """{}""")
        When("exchangeCode runs") {
            Then("it maps to TransportError with the status code") {
                runTest {
                    val result = te.exchangeCode(config, "c", "v")
                    result.shouldBeInstanceOf<OAuthResult.TransportError>()
                    result.message shouldContain "500"
                }
            }
        }
    }
})
