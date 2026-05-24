package dev.weft.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * MCP protocol version this client targets. Sent in the `initialize`
 * request; servers older than this should still respond with their own
 * version, and the client treats anything compatible-or-newer as OK.
 */
public const val MCP_PROTOCOL_VERSION: String = "2024-11-05"

// ----- JSON-RPC 2.0 envelope ----------------------------------------------

@Serializable
public data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
public data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
public data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/** Raised on transport / protocol errors (HTTP failure, malformed JSON, JSON-RPC error). */
public class McpException(
    public val code: Int? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// ----- initialize ----------------------------------------------------------

@Serializable
public data class InitializeParams(
    val protocolVersion: String = MCP_PROTOCOL_VERSION,
    val capabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: ClientInfo = ClientInfo(),
)

@Serializable
public data class ClientCapabilities(
    /**
     * The substrate is a tool consumer in v1. Other capability bags
     * (sampling, roots, etc.) land in later versions.
     */
    val tools: JsonObject = JsonObject(emptyMap()),
)

@Serializable
public data class ClientInfo(
    val name: String = "mobile-agent-substrate",
    val version: String = "0.1.0",
)

@Serializable
public data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: ServerInfo? = null,
)

@Serializable
public data class ServerCapabilities(
    val tools: JsonObject? = null,
    val resources: JsonObject? = null,
    val prompts: JsonObject? = null,
)

@Serializable
public data class ServerInfo(
    val name: String = "",
    val version: String = "",
)

// ----- tools/list ----------------------------------------------------------

@Serializable
public data class ToolsListResult(
    val tools: List<McpToolDescriptor>,
)

/**
 * Server-advertised tool. The `inputSchema` is a JSON Schema object —
 * we translate the subset we support into Koog's [ToolDescriptor] when
 * wrapping this into a WeftTool.
 */
@Serializable
public data class McpToolDescriptor(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

// ----- tools/call ----------------------------------------------------------

@Serializable
public data class ToolsCallParams(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
public data class ToolsCallResult(
    val content: List<McpContent> = emptyList(),
    /**
     * MCP convention: when `isError = true` the content list carries the
     * error message rather than success output. We surface this as a
     * thrown error so the LLM sees a tool-fail bubble.
     */
    val isError: Boolean = false,
)

/**
 * One block of tool-call output. For v1 we materialize text natively;
 * image / resource refs pass through with placeholder text the LLM can
 * see and decide what to do about.
 */
@Serializable
public sealed class McpContent {
    @Serializable
    @SerialName("text")
    public data class Text(val text: String) : McpContent()

    @Serializable
    @SerialName("image")
    public data class Image(
        val data: String, // base64
        val mimeType: String,
    ) : McpContent()

    @Serializable
    @SerialName("resource")
    public data class Resource(
        val resource: JsonObject,
    ) : McpContent()
}
