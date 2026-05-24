package dev.weft.mcp

import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool

/**
 * Configuration for one MCP server connection.
 *
 * @property name short identifier used to namespace this server's tool
 *   names. `name = "linear"` plus a remote tool `create_issue` becomes
 *   `linear:create_issue` in the substrate's tool catalog.
 * @property url full URL to the server's JSON-RPC endpoint. The host
 *   portion must pass the substrate's [dev.weft.security.NetworkPolicy]
 *   allowlist (same gating as `network_fetch`).
 * @property bearerToken optional **static** bearer. Use for servers
 *   authenticated by a long-lived token (your own service, dev/test
 *   environments). Apps should source this from
 *   [dev.weft.contracts.KeyVault] rather than hard-coding.
 * @property tokenProvider optional **dynamic** token source. Called
 *   before every request to fetch the current access token. Use for
 *   OAuth-protected servers where the token expires and must be
 *   refreshed — wire to `OAuthTokenStore.activeAccessToken(connectorId)`.
 *   When both [tokenProvider] and [bearerToken] are set, [tokenProvider]
 *   wins.
 * @property onAuthFailed optional fallback when a request returns 401.
 *   The client invokes it once, and if the lambda returns true, retries
 *   the request. Typical implementation: trigger an OAuth refresh, then
 *   return whether it succeeded. Returning false (or omitting this
 *   callback) lets the 401 propagate as an [McpException].
 * @property extraHeaders any additional headers (custom auth schemes,
 *   tracing ids, tenant routing). Applied after the auth header.
 */
public data class McpServerConfig(
    val name: String,
    val url: String,
    val bearerToken: String? = null,
    val tokenProvider: (suspend () -> String?)? = null,
    val onAuthFailed: (suspend () -> Boolean)? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "McpServerConfig.name must not be blank" }
        require(name.none { it.isWhitespace() || it == ':' }) {
            "McpServerConfig.name must be a single token (no whitespace or ':'): '$name'"
        }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "McpServerConfig.url must be an HTTP(S) URL: '$url'"
        }
    }
}

/**
 * Connect to each server in [servers], call `initialize` + `tools/list`,
 * and produce a flat list of [McpRemoteTool]s ready to register in the
 * WeftRuntime's tool catalog.
 *
 * Each remote tool's name is prefixed `{serverName}:{toolName}` so two
 * servers can both expose a `search` tool without collision. If a server
 * fails to initialize or list — bad URL, auth rejected, network error —
 * its tools are skipped silently and the substrate continues with whatever
 * succeeded. The supplied [onError] callback fires per-server so callers
 * can surface diagnostics in their own UI without blocking startup.
 */
public suspend fun discoverMcpTools(
    client: McpClient,
    servers: List<McpServerConfig>,
    ctx: WeftContext,
    onError: (McpServerConfig, Throwable) -> Unit = { _, _ -> },
): List<WeftTool<*, *>> {
    if (servers.isEmpty()) return emptyList()
    val out = mutableListOf<WeftTool<*, *>>()
    for (server in servers) {
        val tools = runCatching {
            // We don't actually use the InitializeResult's contents in v1,
            // but the call is required by the MCP protocol — many servers
            // refuse subsequent calls until they've been initialized.
            client.initialize(server)
            client.listTools(server)
        }.getOrElse { t ->
            onError(server, t)
            continue
        }
        for (mcpTool in tools) {
            val qualified = "${server.name}:${mcpTool.name}"
            val descriptor = translateToKoogDescriptor(qualified, mcpTool)
            out += McpRemoteTool(
                ctx = ctx,
                client = client,
                serverConfig = server,
                remoteToolName = mcpTool.name,
                descriptor = descriptor,
            )
        }
    }
    return out
}
