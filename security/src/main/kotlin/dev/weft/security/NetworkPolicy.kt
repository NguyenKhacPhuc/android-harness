package dev.weft.security

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Url

/**
 * Network allowlist. The substrate's default is to permit exactly one host:
 * api.anthropic.com. The user adds more domains explicitly in settings; each
 * addition is logged in the audit log (wired in Phase 3+).
 *
 * Threats this addresses: data exfiltration via compromised dependency,
 * stray HTTP calls to surprising hosts. Enforced at the HTTP client layer,
 * not by trust in upstream code.
 */
data class NetworkPolicy(
    /** Hosts always allowed (substrate-internal). */
    val coreAllowlist: Set<String> = setOf(ANTHROPIC_HOST),
    /** Hosts the user has explicitly added via settings. Mutated by the user, not by code. */
    val userAllowlist: Set<String> = emptySet(),
    /**
     * Disable host filtering entirely. Every outbound request is permitted.
     *
     * Intended for development / demo apps where the friction of maintaining
     * an allowlist outweighs the security benefit. Production apps that
     * handle sensitive data should NEVER enable this — it removes the
     * substrate's main defense against data exfiltration via
     * compromised dependencies or LLM-emitted URLs.
     */
    val allowAll: Boolean = false,
) {
    fun isAllowed(host: String): Boolean =
        allowAll || host in coreAllowlist || host in userAllowlist

    fun withUserAddition(host: String): NetworkPolicy = copy(userAllowlist = userAllowlist + host.lowercase())

    fun withUserRemoval(host: String): NetworkPolicy = copy(userAllowlist = userAllowlist - host.lowercase())

    companion object {
        const val ANTHROPIC_HOST = "api.anthropic.com"

        /**
         * Explicit "open" policy — every host allowed. Equivalent to
         * `NetworkPolicy(allowAll = true)` but more readable at call sites.
         * Use only for development / demo. See [NetworkPolicy.allowAll].
         */
        val OPEN: NetworkPolicy = NetworkPolicy(allowAll = true)
    }
}

/** Raised when an outbound HTTP call targets a non-allowlisted host. */
class NetworkPolicyException(val host: String) :
    RuntimeException("Host '$host' is not on the network allowlist. Add it in Settings to permit.")

/**
 * Ktor client plugin that fails every request whose host isn't on the [policy] allowlist.
 *
 * Install it on the outermost HttpClient your code constructs; it short-circuits
 * before the network engine sees the request.
 */
val NetworkAllowlistPlugin = createClientPlugin("NetworkAllowlistPlugin", ::NetworkAllowlistConfig) {
    val policy = pluginConfig.policy
    onRequest { request, _ ->
        val host = request.url.host.lowercase()
        if (!policy.isAllowed(host)) {
            throw NetworkPolicyException(host)
        }
    }
}

class NetworkAllowlistConfig {
    var policy: NetworkPolicy = NetworkPolicy()
}

/**
 * Convenience builder. Wraps an existing engine with a Ktor client that enforces [policy].
 */
fun whitelistingHttpClient(
    engine: HttpClientEngine,
    policy: NetworkPolicy = NetworkPolicy(),
    extraConfig: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(engine) {
    install(NetworkAllowlistPlugin) { this.policy = policy }
    extraConfig()
}

/**
 * Lightweight URL host check, exposed for callers that want to validate a
 * URL string before bothering to build a request.
 */
fun NetworkPolicy.assertAllowed(url: String) {
    val host = Url(url).host.lowercase()
    if (!isAllowed(host)) throw NetworkPolicyException(host)
}
