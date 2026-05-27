package dev.weft.harness.observability.wiredump

import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicReference

/**
 * Where [WireDumper] writes captures. Multiple sinks can be combined
 * via [CompoundSink] so a single dumper can write to a JSON-lines file
 * AND per-turn pretty files at the same time.
 *
 * Implementations should be lenient about errors — a sink that throws
 * from [write] is logged and skipped; the agent loop never sees the
 * failure.
 */
public interface DumpSink {
    public suspend fun write(capture: WireCapture)

    /** Flush + close any open resources. Idempotent. */
    public fun close()
}

/**
 * Fans out to multiple sinks. Errors from one sink do not stop the
 * others — each gets its own try/catch.
 */
public class CompoundSink(private val sinks: List<DumpSink>) : DumpSink {
    public constructor(vararg sinks: DumpSink) : this(sinks.toList())

    override suspend fun write(capture: WireCapture) {
        for (s in sinks) runCatching { s.write(capture) }
    }

    override fun close() {
        for (s in sinks) runCatching { s.close() }
    }
}

/**
 * Append-only JSON-lines file. One [WireCapture] per line, no
 * pretty-printing. Pipe-friendly: `cat session.jsonl | jq` works.
 *
 * Thread-safe: writes are synchronized on the writer.
 */
public class JsonLinesSink(
    private val file: File,
    private val json: Json = DEFAULT_JSON,
) : DumpSink {
    init {
        file.parentFile?.mkdirs()
    }

    private val writerRef: AtomicReference<BufferedWriter?> = AtomicReference(null)
    private val lock = Any()

    private fun writer(): BufferedWriter {
        writerRef.get()?.let { return it }
        synchronized(lock) {
            writerRef.get()?.let { return it }
            // Append mode — multiple sessions can share a file.
            val w = BufferedWriter(FileWriter(file, /* append = */ true))
            writerRef.set(w)
            return w
        }
    }

    override suspend fun write(capture: WireCapture) {
        val line = json.encodeToString(WireCapture.serializer(), capture)
        synchronized(lock) {
            val w = writer()
            w.write(line)
            w.newLine()
            w.flush()
        }
    }

    override fun close() {
        synchronized(lock) {
            writerRef.getAndSet(null)?.runCatching { close() }
        }
    }

    private companion object {
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            prettyPrint = false
        }
    }
}

/**
 * One pretty-printed JSON file per turn — easy to open in an editor /
 * diff between runs / paste into a bug report. File naming:
 * `${prefix}${turnNumber, 4-zero-padded}-${requestId}.json`.
 *
 * Use alongside [JsonLinesSink] (via [CompoundSink]) — the JSON-lines
 * is the queryable index, the per-turn files are the readable detail.
 */
public class PerTurnSink(
    private val directory: File,
    private val prefix: String = "turn-",
    private val json: Json = DEFAULT_JSON,
) : DumpSink {
    init {
        directory.mkdirs()
    }

    override suspend fun write(capture: WireCapture) {
        val padded = capture.turnNumber.toString().padStart(4, '0')
        val safeReqId = capture.requestId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(directory, "$prefix$padded-$safeReqId.json")
        file.writeText(json.encodeToString(WireCapture.serializer(), capture))
    }

    override fun close() {
        // No persistent handle to release — each write is self-contained.
    }

    private companion object {
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}

/**
 * Read a JSON-lines dump file back into [WireCapture]s. Used by the
 * fixture bridge to seed [dev.weft.harness.testing.FakeWeftLLM].
 *
 * Skips malformed lines (with a warning to stderr) rather than failing
 * the whole read — a half-written line at the tail shouldn't block
 * loading the rest.
 */
public object JsonLinesReader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    public fun readAll(file: File): List<WireCapture> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<WireCapture>()
        file.useLines { lines ->
            for ((i, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                runCatching {
                    out += json.decodeFromString(WireCapture.serializer(), trimmed)
                }.onFailure { t ->
                    System.err.println(
                        "WireDumper: skipping malformed line ${i + 1} in ${file.name}: " +
                            "${t.message ?: t::class.simpleName}",
                    )
                }
            }
        }
        return out
    }
}
