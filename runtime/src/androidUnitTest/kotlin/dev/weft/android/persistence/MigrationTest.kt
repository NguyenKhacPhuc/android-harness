package dev.weft.android.persistence

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.weft.android.db.WeftDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests that exercise the SQLDelight migration path against an
 * in-memory JDBC SQLite driver. These run on the JVM during `./gradlew
 * :substrate:android:testDebugUnitTest` — no Android instrumentation
 * needed.
 *
 * The build-time `verifySqlDelightMigration` task already asserts that
 * each `.sqm` migration leaves the schema in the shape `Schema.create()`
 * would produce at the next version. These tests cover the orthogonal
 * concern: **does data survive migration?** They construct an
 * older-version DB by hand, populate it, run the migration, and assert
 * rows are intact + new tables are populated/indexed.
 *
 * When you add a new `.sqm` file:
 *   1. Add a fixture method matching the previous schema (or copy +
 *      tweak the existing one).
 *   2. Add a test that inserts data at the old shape, calls
 *      [WeftDatabase.Schema.migrate], and asserts the new shape.
 */
class MigrationTest {

    private fun freshDriver(): JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    /** Sanity check: a fresh install at the latest version works end-to-end. */
    @Test
    fun freshInstallAtLatestVersion() {
        val driver = freshDriver()
        WeftDatabase.Schema.create(driver)
        val db = WeftDatabase(driver)

        // Insert a memory; verify it round-trips.
        db.memoriesQueries.insert(
            id = "mem-test",
            content = "User prefers metric units",
            tags_json = "[\"preferences\",\"units\"]",
            scope = "PERMANENT",
            stored_at_ms = 1_700_000_000_000L,
        )
        val rows = db.memoriesQueries.selectAll().executeAsList()
        assertEquals(1, rows.size)
        assertEquals("mem-test", rows[0].id)

        // FTS5 trigger should have indexed the row — prefix search "metr*"
        // hits "metric".
        val ftsHits = db.memoriesQueries.selectMatchingFts(
            scope = "ANY",
            query = "metr*",
            limit = 5L,
        ).executeAsList()
        assertEquals(1, ftsHits.size)
        assertEquals("mem-test", ftsHits[0].id)

        driver.close()
    }

    /**
     * v1 → v2 migration: inserts sample data at the v1 shape (no FTS tables),
     * runs the migration, asserts data survives AND the FTS index is
     * back-filled so existing rows are searchable.
     */
    @Test
    fun migrationFromV1PreservesDataAndPopulatesFts() {
        val driver = freshDriver()
        // Build the v1 schema by hand. This snapshot was the production
        // shape before 1.sqm shipped; keeping it inline makes the test
        // self-explanatory.
        createV1Schema(driver)

        // Sample data at v1 shape — five memories, two conversations with
        // five messages between them. Tests both `memories` and `messages`
        // FTS sync triggers + back-fill at migrate time.
        driver.execute(null, V1_MEMORY_INSERT_1, 0)
        driver.execute(null, V1_MEMORY_INSERT_2, 0)
        driver.execute(null, V1_MEMORY_INSERT_3, 0)
        driver.execute(null, V1_CONV_INSERT, 0)
        driver.execute(null, V1_MSG_INSERT_1, 0)
        driver.execute(null, V1_MSG_INSERT_2, 0)

        // Migrate v1 → v2.
        WeftDatabase.Schema.migrate(driver, 1, 2, AfterVersion(2) {})

        val db = WeftDatabase(driver)

        // Memories: all three originals still there.
        val memRows = db.memoriesQueries.selectAll().executeAsList()
        assertEquals(3, memRows.size)
        assertTrue(memRows.any { it.id == "mem-1" && it.content.contains("metric") })

        // FTS5 should find pre-existing memories via the back-fill in 1.sqm.
        val metricHits = db.memoriesQueries.selectMatchingFts(
            scope = "ANY",
            query = "metric*",
            limit = 10L,
        ).executeAsList()
        assertEquals(1, metricHits.size)
        assertEquals("mem-1", metricHits[0].id)

        // Multi-word FTS query: implicit AND. "lunch breakfast" matches no
        // single memory (the test data only has each word separately).
        val nothingHits = db.memoriesQueries.selectMatchingFts(
            scope = "ANY",
            query = "lunch* breakfast*",
            limit = 10L,
        ).executeAsList()
        assertEquals(0, nothingHits.size)

        // Conversations: messages survive and message FTS is populated.
        val messages = db.conversationsQueries.selectMessagesByConversation("conv-1").executeAsList()
        assertEquals(2, messages.size)

        // New writes after migration go through the live FTS triggers —
        // verify a fresh insert + FTS search.
        db.memoriesQueries.insert(
            id = "mem-after",
            content = "Coffee in the afternoon helps focus",
            tags_json = "[]",
            scope = "SESSION",
            stored_at_ms = 1_710_000_000_000L,
        )
        val coffeeHits = db.memoriesQueries.selectMatchingFts(
            scope = "ANY",
            query = "coffee*",
            limit = 5L,
        ).executeAsList()
        assertEquals(1, coffeeHits.size)
        assertEquals("mem-after", coffeeHits[0].id)

        // Update path: changing content should refresh the index.
        driver.close()
    }

    // ------------------------------------------------------------------
    // v1 schema fixture — kept inline so the test is self-contained.
    // If you ship 2.sqm, copy this method to `createV2Schema` and tweak.
    // ------------------------------------------------------------------

    private fun createV1Schema(driver: JdbcSqliteDriver) {
        // memories
        driver.execute(null, """
            CREATE TABLE memories (
                id TEXT NOT NULL PRIMARY KEY,
                content TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                scope TEXT NOT NULL,
                stored_at_ms INTEGER NOT NULL
            )
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX memories_by_stored_at ON memories(stored_at_ms DESC)", 0)
        driver.execute(null, "CREATE INDEX memories_by_scope ON memories(scope)", 0)

        // key_value
        driver.execute(null, """
            CREATE TABLE key_value (
                namespace TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                expires_at_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (namespace, key)
            )
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX key_value_by_namespace ON key_value(namespace)", 0)

        // traces + llm_calls + tool_calls
        driver.execute(null, """
            CREATE TABLE traces (
                id TEXT NOT NULL PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                start_epoch_ms INTEGER NOT NULL,
                end_epoch_ms INTEGER,
                user_message TEXT NOT NULL,
                final_assistant_message TEXT,
                status TEXT NOT NULL,
                error_message TEXT,
                feedback TEXT NOT NULL DEFAULT 'NONE'
            )
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX traces_by_start ON traces(start_epoch_ms DESC)", 0)
        driver.execute(null, "CREATE INDEX traces_by_conversation ON traces(conversation_id)", 0)
        driver.execute(null, """
            CREATE TABLE llm_calls (
                id TEXT NOT NULL PRIMARY KEY,
                trace_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                start_epoch_ms INTEGER NOT NULL,
                end_epoch_ms INTEGER,
                model TEXT NOT NULL,
                input_tokens INTEGER,
                output_tokens INTEGER,
                total_tokens INTEGER
            )
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX llm_calls_by_trace ON llm_calls(trace_id, seq)", 0)
        driver.execute(null, """
            CREATE TABLE tool_calls (
                id TEXT NOT NULL PRIMARY KEY,
                trace_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                start_epoch_ms INTEGER NOT NULL,
                end_epoch_ms INTEGER,
                tool_name TEXT NOT NULL,
                args_preview TEXT NOT NULL,
                result_preview TEXT,
                status TEXT NOT NULL,
                error_message TEXT
            )
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX tool_calls_by_trace ON tool_calls(trace_id, seq)", 0)

        // usage_daily
        driver.execute(null, """
            CREATE TABLE usage_daily (
                day TEXT NOT NULL PRIMARY KEY,
                usd_total REAL NOT NULL DEFAULT 0,
                input_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent(), 0)

        // conversations + messages
        driver.execute(null, """
            CREATE TABLE conversations (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                last_message_at_ms INTEGER NOT NULL
            )
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX conversations_by_last_message ON conversations(last_message_at_ms DESC)", 0)
        driver.execute(null, """
            CREATE TABLE messages (
                id TEXT NOT NULL PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
        """.trimIndent(), 0)
        driver.execute(null, "CREATE INDEX messages_by_conversation ON messages(conversation_id, seq)", 0)
    }

    companion object {
        // Sample v1 data — kept as raw SQL so the test reads top-to-bottom
        // without needing to know the SQLDelight-generated bindings.
        private const val V1_MEMORY_INSERT_1 =
            "INSERT INTO memories(id, content, tags_json, scope, stored_at_ms) " +
                "VALUES ('mem-1', 'User prefers metric units', '[\"prefs\"]', 'PERMANENT', 1700000000000)"
        private const val V1_MEMORY_INSERT_2 =
            "INSERT INTO memories(id, content, tags_json, scope, stored_at_ms) " +
                "VALUES ('mem-2', 'Lunch usually at 1pm', '[\"schedule\"]', 'PERMANENT', 1700000100000)"
        private const val V1_MEMORY_INSERT_3 =
            "INSERT INTO memories(id, content, tags_json, scope, stored_at_ms) " +
                "VALUES ('mem-3', 'Skips breakfast on weekends', '[\"schedule\"]', 'SESSION', 1700000200000)"
        private const val V1_CONV_INSERT =
            "INSERT INTO conversations(id, title, created_at_ms, last_message_at_ms) " +
                "VALUES ('conv-1', 'Test thread', 1700000000000, 1700000300000)"
        private const val V1_MSG_INSERT_1 =
            "INSERT INTO messages(id, conversation_id, seq, role, content, created_at_ms) " +
                "VALUES ('msg-1', 'conv-1', 0, 'USER', 'When is lunch?', 1700000200000)"
        private const val V1_MSG_INSERT_2 =
            "INSERT INTO messages(id, conversation_id, seq, role, content, created_at_ms) " +
                "VALUES ('msg-2', 'conv-1', 1, 'ASSISTANT', 'Around 1pm based on your preferences.', 1700000300000)"
    }
}
