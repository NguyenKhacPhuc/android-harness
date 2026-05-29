package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import dev.weft.security.NetworkPolicyException
import dev.weft.tools.internal.base64Encode
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Make an HTTP request to a domain on the user-approved allowlist.
 *
 * The substrate's [HttpClient] is wrapped with the security module's
 * [WhitelistingHttpClient] policy, so requests to off-allowlist hosts throw
 * [NetworkPolicyException] before they leave the process. The tool maps
 * that exception into a structured error so Claude can ask the user to
 * allowlist the domain in app settings.
 *
 * Without an entry in the allowlist, this tool returns:
 *   { error: true, code: "DOMAIN_NOT_ALLOWED", host: "..." }
 *
 * Apps configure the allowlist at WeftRuntime construction; a settings
 * UI to let users add domains lives in Phase 5+ (see docs/05-script-catalog.md
 * "network.fetch").
 */
class NetworkFetchTool(
    ctx: WeftContext,
    private val client: HttpClient,
) : WeftTool<NetworkFetchTool.Args, NetworkFetchTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "network_fetch",
        description = "Make an HTTP request to an allowlisted domain. " +
            "Use for public APIs the user has approved (weather, time, news, custom services). " +
            "Returns { status, headers, body }. body is JSON-parsed when parseAs='json'.",
        requiredParameters = listOf(
            ToolParameterDescriptor("url", "Full URL to fetch. The host must be on the user's allowlist.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("method", "HTTP method: GET (default), POST, PUT, DELETE, PATCH.", ToolParameterType.String),
            ToolParameterDescriptor(
                "headers",
                "Optional request headers as a JsonObject of string key/value pairs.",
                ToolParameterType.Object(properties = emptyList(), requiredProperties = emptyList()),
            ),
            ToolParameterDescriptor("bodyText", "Optional request body as a UTF-8 string (for POST/PUT/PATCH).", ToolParameterType.String),
            ToolParameterDescriptor("contentType", "Body Content-Type, defaults to 'application/json' if bodyText is JSON.", ToolParameterType.String),
            ToolParameterDescriptor("parseAs", "'json' (default), 'text', or 'bytes' (base64).", ToolParameterType.String),
        ),
    ),
) {

    @Serializable
    data class Args(
        val url: String,
        val method: String = "GET",
        val headers: JsonObject = JsonObject(emptyMap()),
        val bodyText: String? = null,
        val contentType: String? = null,
        val parseAs: String = "json",
    )

    @Serializable
    data class Result(
        val status: Int = 0,
        val headers: JsonObject = JsonObject(emptyMap()),
        val body: JsonElement = JsonNull,
        val error: Boolean = false,
        val code: String? = null,
        val message: String? = null,
    )

    override suspend fun executeWeft(args: Args): Result {
        val method = HttpMethod.parse(args.method.uppercase())
        return try {
            val response = client.request(args.url) {
                this.method = method
                headers {
                    args.headers.forEach { (k, v) -> append(k, jsonElementToString(v)) }
                }
                args.bodyText?.let { body ->
                    args.contentType?.let { contentType(io.ktor.http.ContentType.parse(it)) }
                    setBody(body)
                }
            }

            val responseHeaders: JsonObject = buildJsonObject {
                response.headers.entries().forEach { (name, values) ->
                    put(name, JsonPrimitive(values.joinToString(", ")))
                }
            }
            val bodyText = response.bodyAsText()
            val parsed: JsonElement = when (args.parseAs.lowercase()) {
                "json" -> runCatching { Json.parseToJsonElement(bodyText) }
                    .getOrElse { JsonPrimitive(bodyText) }
                "text" -> JsonPrimitive(bodyText)
                "bytes" -> JsonPrimitive(base64Encode(bodyText.encodeToByteArray()))
                else -> JsonPrimitive(bodyText)
            }

            Result(status = response.status.value, headers = responseHeaders, body = parsed)
        } catch (e: NetworkPolicyException) {
            Result(
                error = true,
                code = "DOMAIN_NOT_ALLOWED",
                message = "Host '${e.host}' is not on the network allowlist. The user can add it in app settings.",
            )
        } catch (e: Throwable) {
            Result(
                error = true,
                code = "NETWORK_ERROR",
                message = e.message ?: e::class.simpleName.orEmpty(),
            )
        }
    }

    private fun jsonElementToString(v: JsonElement): String =
        if (v is JsonPrimitive) v.content else v.toString()
}
