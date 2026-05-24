package dev.weft.contracts

/**
 * Tiny structured-logging interface. Implementations write to platform logs
 * and (where wired) into the trace store via the observability middleware.
 *
 * Use this for code that lives in :contracts or any module that doesn't want
 * a direct platform-logging dependency.
 */
interface Logger {
    fun debug(msg: String, fields: Map<String, Any?> = emptyMap())
    fun info(msg: String, fields: Map<String, Any?> = emptyMap())
    fun warn(msg: String, fields: Map<String, Any?> = emptyMap())
    fun error(msg: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null)
}

/** A logger that drops everything. Useful as a default in tests. */
object NoOpLogger : Logger {
    override fun debug(msg: String, fields: Map<String, Any?>) = Unit
    override fun info(msg: String, fields: Map<String, Any?>) = Unit
    override fun warn(msg: String, fields: Map<String, Any?>) = Unit
    override fun error(msg: String, fields: Map<String, Any?>, throwable: Throwable?) = Unit
}
