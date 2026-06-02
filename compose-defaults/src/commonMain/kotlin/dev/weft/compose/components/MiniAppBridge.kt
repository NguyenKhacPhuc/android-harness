package dev.weft.compose.components

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Host-supplied router from a mini-app action call to a result.
 *
 * A mini-app's script invokes `window.weft.callTool(name, args)`; the
 * bridge marshals that into a call against this invoker. The host owns
 * the action namespace (the substrate ships only the mechanism — the
 * scope/permission gate is a later story).
 *
 * Contract — three outcomes map to the three things the JS caller can
 * observe on its returned Promise:
 *  - **return a result string** (JSON) → the Promise resolves with it.
 *  - **return `null`** → no action by that name exists → the Promise
 *    rejects with "no such action: <name>".
 *  - **throw** → the action ran but failed → the Promise rejects with
 *    the throwable's message. (Never hangs.)
 *
 * @param argsJson the call's `args` serialized as a JSON string
 *   (`"null"` when the caller passed nothing).
 */
public fun interface MiniAppActionInvoker {
    public suspend fun invoke(name: String, argsJson: String): String?
}

/** A single parsed `window.weft.callTool` invocation crossing the bridge. */
public data class MiniAppCall(
    public val id: String,
    public val name: String,
    public val argsJson: String,
)

/**
 * Host-supplied resolver from a mini-app's (declared, non-sensitive)
 * id to the set of action names it may call — its declared scopes
 * intersected with what the user approved. Returns `null` for an
 * ungated mini-app (no enforcement) or an empty set for one with
 * nothing approved (every call refused). The grant store + approval
 * UX that back this live in the host.
 */
public typealias MiniAppScopeResolver = (miniAppId: String?) -> Set<String>?

/**
 * Host-supplied per-mini-app state store backing `window.weft.getState`
 * / `setState`. Keyed by the mini-app's id so one mini-app's state is
 * isolated from another's. The substrate exposes the bridge API; the
 * host owns the actual persistence (e.g. SQLDelight).
 */
public interface MiniAppStateStore {
    /** The mini-app's saved state JSON, or `null` if it never saved any. */
    public suspend fun get(miniAppId: String?): String?

    /** Persist the mini-app's state JSON, replacing any prior value. */
    public suspend fun set(miniAppId: String?, stateJson: String)
}

/**
 * Pure JS↔native marshalling core for the mini-app bridge. Platform
 * WebView wrappers (Android `@JavascriptInterface`, iOS
 * `WKScriptMessageHandler`) own only the transport: they hand the raw
 * JSON payload to [handle] and evaluate the returned JS string back in
 * the page. Everything in between — parsing, the scope gate, dispatch,
 * the outcomes, and JS-string escaping — lives here so it can be unit
 * tested without a WebView.
 *
 * @param approvedActions the per-mini-app set of action names this
 *   mini-app may call — its *declared* scopes intersected with what the
 *   user *approved*, supplied by the host. A call to anything outside
 *   the set is refused before the invoker runs. `null` means **ungated**
 *   (a trusted bridge with no scope enforcement); an empty set means
 *   gated with nothing approved (every call refused). The substrate
 *   only enforces this set — it doesn't decide policy (host stories own
 *   the offerable menu, approval UX, and grant store).
 */
public class MiniAppBridge(
    private val invoker: MiniAppActionInvoker,
    private val approvedActions: Set<String>? = null,
    private val stateStore: MiniAppStateStore? = null,
    private val miniAppId: String? = null,
    private val json: Json = DEFAULT_JSON,
) {

    /**
     * Parse a raw `{id, name, args}` payload posted by the JS shim.
     * Returns `null` for anything malformed (no id/name, not an object,
     * not JSON) — there's no call id to reject against, so the caller
     * simply evaluates nothing.
     */
    public fun parseCall(payload: String): MiniAppCall? = runCatching {
        val obj = json.parseToJsonElement(payload).jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        if (id == null || name == null) return@runCatching null
        val args = obj["args"]
        val argsJson = if (args == null || args is JsonNull) "null" else args.toString()
        MiniAppCall(id, name, argsJson)
    }.getOrNull()

    /**
     * Marshal one posted payload end-to-end and return the JS to
     * evaluate back in the page (`window.weft.__resolve(...)` /
     * `__reject(...)`). Routes by the message `kind`: state ops go to the
     * [stateStore], everything else is a `callTool` dispatch. Returns an
     * empty string when the payload can't be parsed.
     */
    public suspend fun handle(payload: String): String {
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return ""
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return ""
        return when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            KIND_GET_STATE -> getState(id)
            KIND_SET_STATE -> setState(id, obj["state"])
            else -> {
                val call = parseCall(payload) ?: return ""
                dispatch(call)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun getState(id: String): String {
        val store = stateStore ?: return rejectJs(id, "state not available")
        return try {
            // Never-saved → null, which JS resolves as `null` (not an error).
            resolveJs(id, store.get(miniAppId) ?: "null")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            rejectJs(id, failure.message ?: "getState failed")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun setState(id: String, state: JsonElement?): String {
        val store = stateStore ?: return rejectJs(id, "state not available")
        return try {
            store.set(miniAppId, state?.toString() ?: "null")
            resolveJs(id, "true")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            rejectJs(id, failure.message ?: "setState failed")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun dispatch(call: MiniAppCall): String {
        // Scope gate: a gated bridge refuses any action outside the
        // approved set before the invoker ever runs. Ungated (null) skips.
        if (approvedActions != null && call.name !in approvedActions) {
            return rejectJs(call.id, "not permitted: ${call.name}")
        }
        return try {
            val result = invoker.invoke(call.name, call.argsJson)
            if (result == null) {
                rejectJs(call.id, "no such action: ${call.name}")
            } else {
                resolveJs(call.id, result)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            rejectJs(call.id, failure.message ?: "action '${call.name}' failed")
        }
    }

    public companion object {
        private const val KIND_GET_STATE = "getState"
        private const val KIND_SET_STATE = "setState"

        public val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** JS expression that resolves the pending Promise for [id] with [resultJson]. */
        public fun resolveJs(id: String, resultJson: String): String =
            "window.weft.__resolve(${jsString(id)}, ${jsString(resultJson)});"

        /** JS expression that rejects the pending Promise for [id] with [message]. */
        public fun rejectJs(id: String, message: String): String =
            "window.weft.__reject(${jsString(id)}, ${jsString(message)});"

        /**
         * The shared `window.weft` shim injected into a mini-app page.
         * Defines `callTool(name, args) -> Promise` plus the
         * `__resolve` / `__reject` entry points the native side calls.
         * [postCall] is the platform transport statement — it must post
         * the local `msg` string to native, e.g.
         * `WeftBridge.postMessage(msg);` (Android) or
         * `window.webkit.messageHandlers.weft.postMessage(msg);` (iOS).
         */
        public fun jsShim(postCall: String): String = """
            (function () {
              if (window.weft && window.weft.callTool) { return; }
              var seq = 0;
              var pending = {};
              // Merge onto any existing window.weft (e.g. the injected theme)
              // rather than replacing it — the bridge and theme coexist.
              window.weft = window.weft || {};
              window.weft.callTool = function (name, args) {
                return new Promise(function (resolve, reject) {
                  var id = String(++seq);
                  pending[id] = { resolve: resolve, reject: reject };
                  var msg = JSON.stringify({
                    id: id,
                    name: name,
                    args: (args === undefined ? null : args)
                  });
                  $postCall
                });
              };
              function request(extra) {
                return new Promise(function (resolve, reject) {
                  var id = String(++seq);
                  pending[id] = { resolve: resolve, reject: reject };
                  extra.id = id;
                  var msg = JSON.stringify(extra);
                  $postCall
                });
              }
              window.weft.getState = function () {
                return request({ kind: "getState" });
              };
              window.weft.setState = function (state) {
                return request({ kind: "setState", state: (state === undefined ? null : state) });
              };
              window.weft.__resolve = function (id, payload) {
                var p = pending[id];
                if (!p) { return; }
                delete pending[id];
                var value;
                try { value = JSON.parse(payload); } catch (e) { value = payload; }
                p.resolve(value);
              };
              window.weft.__reject = function (id, message) {
                var p = pending[id];
                if (!p) { return; }
                delete pending[id];
                p.reject(new Error(message));
              };
            })();
        """.trimIndent()

        /** Render [raw] as an escaped, double-quoted JS string literal. */
        private fun jsString(raw: String): String {
            val sb = StringBuilder(raw.length + 2)
            sb.append('"')
            for (ch in raw) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(ch)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
