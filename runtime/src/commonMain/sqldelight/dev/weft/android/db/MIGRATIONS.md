# SubstrateDatabase migrations

The substrate ships one SQLite file per app (`substrate.db`) shared by every
persistent store: MemoryStore, ScriptStorage, ScheduledNotificationStore,
TraceStore, UsageStore, ConversationStore. Schemas live in this directory
as `.sq` files; migrations live next to them as `.sqm` files.

## When to add a migration

Any change to a `CREATE TABLE` statement, or any new table, **on an existing
schema file** that has already shipped to users, needs a migration. Adding
a brand-new `.sq` file (a whole new feature with no prior schema) doesn't
need a migration — SQLDelight creates new tables on first install and
existing devices pick them up on `Schema.create()` skipping over already-
present ones (driven by `IF NOT EXISTS`-style CREATE semantics SQLDelight
generates).

In practice, **any of the following** = write a migration:

- Add a column to an existing table.
- Drop or rename a column.
- Change a column type / nullability / default.
- Add a new index (safe to do in migration or via `CREATE INDEX IF NOT EXISTS`
  in the schema, but explicit migration is clearer).
- Add a CHECK / UNIQUE constraint.

**Not required** (the schema file itself works):
- A whole new `.sq` file with new tables that didn't exist before.

> **CRITICAL — lesson learned the hard way.** Adding a new `.sq` file
> with new tables WITHOUT bumping the version is safe ONLY until you
> ship your first `.sqm`. The instant a real migration exists, every
> device on "version 1" gets `Schema.migrate(driver, 1, N)` run against
> it — and "version 1" means whatever was in `Schema.create()` when
> that device first installed, which might predate your later `.sq`
> additions. Old devices end up missing tables your latest schema
> assumes exist.
>
> **Defensive rule for every `.sqm`**: start with `CREATE TABLE IF NOT
> EXISTS` for every table any prior version of the app might have been
> missing. Then write the version-N → N+1 diff. The IF NOT EXISTS is a
> no-op for devices that have the tables; it heals devices that don't.
> `verifyMigrations` still passes because the final shape matches the
> snapshot regardless. See `1.sqm` for the working example.

## Workflow

1. **Edit the `.sq` file** with the new shape. The generated Kotlin code
   reflects the latest schema; your code can use it immediately.

2. **Find the current version.** It's `1 + (count of existing .sqm files
   in this directory)`. With zero `.sqm` files, the schema is at version 1.

3. **Write `N.sqm`** where `N` is the current version. The migration goes
   FROM version `N` TO version `N+1`. The file is plain SQL:

   ```sql
   -- 1.sqm — adds a `pinned` flag to conversations (example).
   ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
   ```

   Migrations cannot reference table-aliases generated for newer schemas.
   Use raw SQL only.

4. **Build.** SQLDelight regenerates `SubstrateDatabase.Schema` with the
   new `version` (= old + 1) and embeds the migration. `AndroidSqliteDriver`
   calls `Schema.migrate(driver, oldVersion, newVersion)` automatically
   via SQLiteOpenHelper's `onUpgrade` when an existing install opens the DB.

5. **(Optional but recommended) Snapshot the schema.** With
   `schemaOutputDirectory` set in `:substrate:android/build.gradle.kts`,
   running `./gradlew generateSubstrateDatabaseSchema` dumps a `.db` file
   for the new version into `src/main/sqldelight/databases/`. Check it in.
   Future migrations can be cross-checked against the snapshot via
   `verifyMigrations.set(true)`.

## Anti-patterns

- **Don't edit a `.sqm` file after it has shipped.** Devices already on
  that version skip it on next launch; later devices that hit a fresh
  install get the new `.sq` directly. Result: divergent schemas in the
  wild. If you need to fix something, write a follow-up `.sqm`.

- **Don't reorder `.sqm` files.** Numbers identify which version to run
  from. Renaming `5.sqm` to `4.sqm` breaks every device past version 4.

- **Don't `DROP TABLE`-then-`CREATE` to "fix" something.** That nukes user
  data. Use `ALTER TABLE` whenever possible; for column type changes
  SQLite makes hard, do the classic copy-rename dance:

  ```sql
  CREATE TABLE memories_new (id TEXT NOT NULL PRIMARY KEY, ...);
  INSERT INTO memories_new SELECT id, ... FROM memories;
  DROP TABLE memories;
  ALTER TABLE memories_new RENAME TO memories;
  ```

## What ships at version 1 (this baseline)

- `memories` — MemoryStore (1 table).
- `key_value` — ScriptStorage + ScheduledNotificationStore share via
  `namespace` column.
- `traces` + `llm_calls` + `tool_calls` — TraceStore. FK declared for
  documentation; cascade enforced in Kotlin (`foreign_keys = ON` is not
  set on the driver).
- `usage_daily` — UsageStore aggregates.
- `conversations` + `messages` — ConversationStore.

## Testing a migration

There's no automated test harness in the substrate yet; the recommended
manual flow:

1. Install the app from the prior commit (before the schema change).
2. Use the app enough to populate the changed table.
3. Update to the new commit. The app should open without crashing and
   pre-existing rows should be intact (per your migration logic).
4. Check `adb shell run-as <pkg> sqlite3 databases/substrate.db
   "SELECT * FROM <changed_table>;"` for sanity.

A follow-up could add a CI test that constructs an old-version DB,
applies the migration, and asserts the new shape — see
`docs/follow-ups.md`.
