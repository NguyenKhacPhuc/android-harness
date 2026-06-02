package dev.weft.compose.components

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
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
 * Pure JS↔native marshalling core for the mini-app bridge. Platform
 * WebView wrappers (Android `@JavascriptInterface`, iOS
 * `WKScriptMessageHandler`) own only the transport: they hand the raw
 * JSON payload to [handle] and evaluate the returned JS string back in
 * the page. Everything in between — parsing, dispatch, the three
 * outcomes, and JS-string escaping — lives here so it can be unit
 * tested without a WebView.
 */
public class MiniAppBridge(
    private val invoker: MiniAppActionInvoker,
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
     * Marshal one posted payload end-to-end: parse, dispatch to the
     * host invoker, and return the JS to evaluate back in the page
     * (`window.weft.__resolve(...)` / `__reject(...)`). Returns an empty
     * string when the payload can't be parsed.
     */
    public suspend fun handle(payload: String): String {
        val call = parseCall(payload) ?: return ""
        return dispatch(call)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun dispatch(call: MiniAppCall): String =
        try {
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

    public companion object {
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
              window.weft = {
                callTool: function (name, args) {
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
                },
                __resolve: function (id, payload) {
                  var p = pending[id];
                  if (!p) { return; }
                  delete pending[id];
                  var value;
                  try { value = JSON.parse(payload); } catch (e) { value = payload; }
                  p.resolve(value);
                },
                __reject: function (id, message) {
                  var p = pending[id];
                  if (!p) { return; }
                  delete pending[id];
                  p.reject(new Error(message));
                }
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
