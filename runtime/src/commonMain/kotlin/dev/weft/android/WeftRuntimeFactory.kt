package dev.weft.android

import dev.weft.android.db.WeftDatabase
import dev.weft.contracts.OsCapabilities
import dev.weft.contracts.UiBridge
import dev.weft.mcp.HttpMcpClient
import dev.weft.security.whitelistingHttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Shared cross-platform assembly for [WeftRuntime].
 *
 * The Android `WeftRuntime.create(context, …)` factory and the iOS
 * `WeftRuntime.create(platform, os, …)` factory both delegate here. They
 * differ only in the platform-bound pieces they supply: the [os]
 * capabilities, the HTTP [networkEngine] (OkHttp / Darwin), and the
 * [deviceSnapshotProvider]. The app-facing tuning rides in a single
 * [WeftRuntimeConfig]. The one shared bit of logic — wrapping the engine
 * in the host-allowlist client (with `ContentNegotiation` pre-installed so
 * MCP discovery can reuse it) — lives here, so there is one composition
 * rather than two parallel ones.
 */
internal fun assembleWeftRuntime(
    os: OsCapabilities,
    database: WeftDatabase,
    networkEngine: HttpClientEngine,
    deviceSnapshotProvider: () -> String,
    uiBridge: UiBridge,
    config: WeftRuntimeConfig,
): WeftRuntime = WeftRuntime(
    os = os,
    uiBridge = uiBridge,
    database = database,
    networkClient = whitelistingHttpClient(
        engine = networkEngine,
        policy = config.networkPolicy,
        extraConfig = {
            install(ContentNegotiation) { json(HttpMcpClient.DEFAULT_JSON) }
        },
    ),
    deviceSnapshotProvider = deviceSnapshotProvider,
    config = config,
)
