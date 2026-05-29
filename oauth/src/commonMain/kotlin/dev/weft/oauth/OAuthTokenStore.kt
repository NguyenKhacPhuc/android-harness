package dev.weft.oauth

import dev.weft.contracts.KeyVault
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-connector token persistence on top of [KeyVault].
 *
 * Storage layout: one JSON-encoded [TokenSet] per connector id, written
 * under the alias `oauth.tokens.<connectorId>`. The KeyVault itself is
 * Android Keystore + EncryptedSharedPreferences (see AndroidKeyVault),
 * so tokens are encrypted at rest.
 *
 * **Refresh policy.** [activeAccessToken] reads the persisted bundle,
 * checks the expiry (with [TokenSet.DEFAULT_SKEW_MS] of slack), and runs
 * a refresh through [OAuthClient.refresh] if needed. Concurrent calls
 * for the same connector serialize through a per-connector mutex so we
 * don't burn refresh tokens in parallel.
 *
 * **Refresh failure.** If the refresh fails (revoked grant, network
 * down, provider rotation lost the refresh token), [activeAccessToken]
 * returns null. The substrate's MCP client then surfaces a 401 to the
 * agent loop, which the agent can interpret as "tell the user they need
 * to reconnect this connector."
 */
public class OAuthTokenStore(
    private val keyVault: KeyVault,
    private val oauthClient: OAuthClient,
    private val json: Json = DEFAULT_JSON,
) {

    // ConcurrentHashMap isn't in commonMain; substitute a regular map
    // guarded by SynchronizedObject + the kotlinx.atomicfu monitor
    // intrinsic. Per-connector Mutex still gates the actual refresh so
    // different connectors don't block each other.
    private val perConnectorLocksMonitor = SynchronizedObject()
    private val perConnectorLocks = mutableMapOf<String, Mutex>()
    private fun lockFor(connectorId: String): Mutex =
        synchronized(perConnectorLocksMonitor) {
            perConnectorLocks.getOrPut(connectorId) { Mutex() }
        }

    /** Persist the bundle for [connectorId], overwriting any previous value. */
    public suspend fun put(connectorId: String, tokens: TokenSet) {
        keyVault.put(alias(connectorId), json.encodeToString(tokens))
    }

    /** Read the persisted bundle, or null if nothing's stored yet. */
    public suspend fun get(connectorId: String): TokenSet? {
        val blob = keyVault.get(alias(connectorId)) ?: return null
        return runCatching { json.decodeFromString(TokenSet.serializer(), blob) }.getOrNull()
    }

    /** Remove the bundle. Used by "disconnect" UX. */
    public suspend fun remove(connectorId: String): Boolean =
        keyVault.remove(alias(connectorId))

    /**
     * Return the current access token, refreshing first if it's near
     * expiry. Null when:
     *   - no bundle stored,
     *   - bundle is expired AND no refresh token to recover with,
     *   - refresh attempt failed.
     *
     * Pass this as the `tokenProvider` on an [dev.weft.mcp.McpServerConfig]
     * so every MCP call automatically uses a live token.
     */
    public suspend fun activeAccessToken(connectorId: String, config: OAuthConfig): String? {
        val lock = lockFor(connectorId)
        return lock.withLock {
            val stored = get(connectorId) ?: return@withLock null
            if (!stored.isExpired()) return@withLock stored.accessToken

            // Need to refresh. Bail if we have nothing to refresh with.
            val rt = stored.refreshToken ?: return@withLock null
            val result = oauthClient.refresh(config, rt)
            when (result) {
                is OAuthResult.Success -> {
                    put(connectorId, result.tokens)
                    result.tokens.accessToken
                }
                else -> null
            }
        }
    }

    private fun alias(connectorId: String): String = "$ALIAS_PREFIX$connectorId"

    public companion object {
        public const val ALIAS_PREFIX: String = "oauth.tokens."

        public val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
