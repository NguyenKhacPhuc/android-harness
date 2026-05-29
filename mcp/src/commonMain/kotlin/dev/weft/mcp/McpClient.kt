package dev.weft.mcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.atomicfu.atomic

/**
 * Minimal Model Context Protocol client.
 *
 * Transport: JSON-RPC 2.0 over HTTP POST (one request per call, response
 * arrives synchronously in the same HTTP response body). The newer SSE
 * transport variant from the 2025-03-26 spec is not implemented in v1.
 *
 * Lifecycle per server:
 *   1. [initialize] — exchange protocol versions + capability bags.
 *   2. [listTools] — fetch the server's tool catalog.
 *   3. [callTool] — invoke a tool, get a [ToolsCallResult].
 *
 * The substrate calls 1+2 at startup to build [McpRemoteTool] wrappers,
 * then 3 each time the LLM picks one. The client is stateless across
 * tools/call requests — each call rebuilds a fresh JSON-RPC envelope.
 *
 * Implementations are responsible for honouring the substrate's
 * [dev.weft.security.NetworkPolicy] before issuing any request — see
 * [HttpMcpClient].
 */
public interface McpClient {
    public suspend fun initialize(server: McpServerConfig): InitializeResult
    public suspend fun listTools(server: McpServerConfig): List<McpToolDescriptor>
    public suspend fun callTool(
        server: McpServerConfig,
        name: String,
        arguments: JsonObject,
    ): ToolsCallResult
}

/**
 * Default HTTP+JSON-RPC implementation.
 *
 * Uses the substrate's already-configured Ktor [HttpClient] — typically
 * the one built with `whitelistingHttpClient(...)` so the same allowlist
 * that gates `network_fetch` also gates MCP traffic. There's no separate
 * MCP allowlist; if a server's host isn't on the policy, the request
 * fails with `NetworkPolicyException` and the substrate logs it.
 *
 * One JSON instance is shared across requests; ids are monotonic per
 * client instance.
 */
public class HttpMcpClient(
    private val http: HttpClient,
    private val json: Json = DEFAULT_JSON,
) : McpClient {

    private val nextId = atomic(0L)

    public companion object {
        public val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            classDiscriminator = "type"
        }

        /**
         * Convenience: build a Ktor client pre-configured for MCP traffic.
         * Apps usually pass their own [HttpClient] instead so a single
         * allowlist + auth strategy covers all of their network surfaces.
         */
        public fun ktorClient(base: HttpClient): HttpClient = base.config {
            install(ContentNegotiation) { json(DEFAULT_JSON) }
        }
    }

    override suspend fun initialize(server: McpServerConfig): InitializeResult {
        val result = call(server, "initialize", json.encodeToJsonElement(InitializeParams()))
        return json.decodeFromJsonElement(InitializeResult.serializer(), result)
    }

    override suspend fun listTools(server: McpServerConfig): List<McpToolDescriptor> {
        val result = call(server, "tools/list", null)
        return json.decodeFromJsonElement(ToolsListResult.serializer(), result).tools
    }

    override suspend fun callTool(
        server: McpServerConfig,
        name: String,
        arguments: JsonObject,
    ): ToolsCallResult {
        val params = json.encodeToJsonElement(ToolsCallParams(name = name, arguments = arguments))
        val result = call(server, "tools/call", params)
        val parsed = json.decodeFromJsonElement(ToolsCallResult.serializer(), result)
        if (parsed.isError) {
            // Hoist the server-reported error into a thrown exception so
            // the WeftTool execute path surfaces a tool-fail bubble.
            val errMsg = parsed.content
                .filterIsInstance<McpContent.Text>()
                .joinToString("\n") { it.text }
                .ifEmpty { "MCP server reported isError=true with no message." }
            throw McpException(message = errMsg)
        }
        return parsed
    }

    /**
     * Issue one JSON-RPC call. Single-shot HTTP POST → JSON response —
     * no SSE, no streaming.
     *
     * **Auth + retry.** The auth token is resolved freshly per call (via
     * [McpServerConfig.tokenProvider] if set, otherwise [McpServerConfig.bearerToken]).
     * On HTTP 401, if [McpServerConfig.onAuthFailed] is configured, the
     * client invokes it once — typically the app refreshes the OAuth
     * token — and retries the call. A second 401 propagates as
     * [McpException].
     *
     * Server-level JSON-RPC errors come back inside the envelope's
     * `error` field and surface as [McpException] regardless of HTTP
     * status.
     */
    private suspend fun call(
        server: McpServerConfig,
        method: String,
        params: kotlinx.serialization.json.JsonElement?,
    ): kotlinx.serialization.json.JsonElement {
        val request = JsonRpcRequest(
            id = nextId.incrementAndGet(),
            method = method,
            params = params,
        )

        val firstAttempt = postOnce(server, request)
        val response = if (firstAttempt.status == HttpStatusCode.Unauthorized && server.onAuthFailed != null) {
            // Give the app one shot to repair the auth state (refresh
            // token, prompt re-auth, etc.). If it returns true, retry the
            // exact same call with a freshly-resolved token.
            val refreshed = runCatching { server.onAuthFailed!!.invoke() }.getOrDefault(false)
            if (refreshed) postOnce(server, request) else firstAttempt
        } else firstAttempt

        if (response.status != HttpStatusCode.OK) {
            throw McpException(
                code = response.status.value,
                message = "MCP server returned HTTP ${response.status.value}",
            )
        }
        val envelope: JsonRpcResponse = response.body()
        envelope.error?.let {
            throw McpException(code = it.code, message = it.message)
        }
        return envelope.result
            ?: throw McpException(message = "MCP response had neither result nor error")
    }

    /**
     * One HTTP attempt. The auth header is resolved per call — dynamic
     * [McpServerConfig.tokenProvider] wins over the static
     * [McpServerConfig.bearerToken]. Transport failures (DNS, TLS,
     * timeout) throw [McpException]; HTTP-status non-200 is returned as-
     * is for the caller to interpret.
     */
    private suspend fun postOnce(server: McpServerConfig, request: JsonRpcRequest): HttpResponse {
        val authToken: String? = server.tokenProvider?.invoke() ?: server.bearerToken
        return try {
            http.post(server.url) {
                contentType(ContentType.Application.Json)
                headers {
                    authToken?.let { append(HttpHeaders.Authorization, "Bearer $it") }
                    server.extraHeaders.forEach { (k, v) -> append(k, v) }
                }
                setBody(json.encodeToString(request))
            }
        } catch (t: Throwable) {
            throw McpException(
                message = "HTTP transport failed: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
    }
}
