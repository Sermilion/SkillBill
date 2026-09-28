---
name: bill-kmp-code-review-persistence
description: Use when reviewing Room, SQLDelight, or DataStore writes, schema migrations, and offline-first sync cursor and idempotency risks on Android and KMP.
internal-for: skill-bill
---

# KMP Persistence Review Specialist

Review only durability and consistency failures in on-device storage and offline sync.

## Focus

- Room, SQLDelight, and DataStore write, transaction, and threading boundaries
- Schema migration safety across installed app versions
- Offline-first sync cursors, idempotency keys, and shared cross-feature table ownership

## Ignore

- Query or DAO style without a durability, consistency, or correctness consequence
- Store-choice preferences where the module's existing store is already coherent
- Server-side storage concerns owned by the manifest-declared Kotlin baseline

## Applicability

Use this specialist when a diff touches Room entities, DAOs, or `RoomDatabase` configuration; `.sq` files or a SQLDelight driver; a `DataStore<Preferences>` or proto `DataStore`; a migration; or offline sync state persisted on device. Judge every rule against installed-app upgrade, process death, and airplane-mode operation, not just a clean install.

## Project-Specific Rules

### Room Transaction And Threading Rules

- Multi-entity invariants must be written inside a single `@Transaction` DAO method or `withTransaction` block; reject split writes that leave a parent row without its children after a mid-write failure.
- `RoomDatabase.Builder.allowMainThreadQueries` must never be enabled on a shipping build; reject it because a slow query then blocks the main thread into an ANR and abandons the pending write.
- Suspending DAO calls must not be wrapped in an outer `withContext(Dispatchers.IO)` that escapes the `withTransaction` coroutine context; reject context switches inside a transaction because Room then executes the nested statement on a different connection and silently drops it from the atomic unit.
- `@Insert(onConflict = OnConflictStrategy.REPLACE)` on a table with child foreign keys must be verified against `onDelete` behavior; reject replace-on-conflict where the cascade deletes live child rows that the sync layer cannot re-fetch.
- Room `Flow` queries observing a table written by another feature must be verified for invalidation scope; reject a raw `@RawQuery` without declared `observedEntities` because the UI then serves stale rows indefinitely after a write it cannot see.

### Migration Safety Rules

- Every `version` bump must ship a `Migration` covering the schema delta and an entry in the exported `schemas/` JSON; reject a bump without both because installed devices fail to open the database on the next launch.
- `fallbackToDestructiveMigration` must never be enabled on a database holding unsynced local writes; reject it because the upgrade silently deletes user work that was never uploaded.
- A shipped `Migration` body must never be edited after release; require a new migration for the correction because devices that already ran the old body retain a schema the new code cannot read.
- SQLDelight `.sq` schema changes must add a numbered file under the migrations directory and keep `verifyMigrations` enabled; reject an in-place `CREATE TABLE` edit because it diverges the upgraded schema from the fresh-install schema.
- Migration and store-open failures must be reported through the app's error channel with the original database preserved; never delete the file to recover because that converts a recoverable upgrade error into permanent data loss.

### SQLDelight Transaction And Driver Rules

- Multi-statement writes must run inside `transaction` or `transactionWithResult`; reject sequential statements that commit a partial state when the second statement throws.
- A value read for a subsequent write must be read inside the same transaction; reject read-then-write across transaction boundaries because a concurrent writer makes the update overwrite a newer value.
- Driver calls must never run on `Dispatchers.Main`; require an IO-confined driver wrapper because a blocking SQLite call on the main thread produces an ANR mid-write.
- A single `SqlDriver` instance must be owned for the process lifetime and closed exactly once; reject per-call driver creation because concurrent connections to the same file cause lock contention and write failures.
- `Query.addListener` subscriptions must be removed on scope cancellation; reject leaked listeners because they retain a closed driver and throw on the next notification.

### DataStore Atomicity And Concurrency Rules

- A `DataStore` instance must be created exactly once per file per process; reject a second instance for the same file because concurrent instances corrupt the file and throw `IllegalStateException`.
- Read-modify-write updates must happen inside a single `edit` or `updateData` block; reject a `first()` read followed by a separate `edit` write because two concurrent callers then lose one of the updates.
- Proto `DataStore` serializers must define a `defaultValue` and handle `CorruptionException` through a declared `ReplaceFileCorruptionHandler` or explicit failure; reject an unhandled corruption path because it crashes on every launch with no recovery.
- `DataStore` must never hold large or growing collections used as a queue or cache; reject that use because the whole file is rewritten and re-parsed on every update, and a failure mid-rewrite loses every key.
- Values that must survive a factory reset boundary or that carry secrets must not be written to plain `Preferences` `DataStore`; require the appropriate encrypted or excluded-from-backup store because the file is otherwise readable in a backup archive.

### Offline-First Sync Consistency Rules

- A locally created record must persist a client-generated idempotency key before its first upload attempt; reject server-assigned identity only, because a retried upload after process death then creates a duplicate remote record.
- Delta or watermark cursors must advance only after the whole page is durably committed in the same transaction as the rows; reject advancing the cursor first because an interrupted sync then permanently skips the un-committed window.
- Cursor values must be persisted with their store and schema version and reset when a migration changes row identity; reject reuse of a stale cursor because it hides rows the new schema needs to re-fetch.
- Deletes arriving from sync must be applied as tombstones or version-checked deletes; reject unconditional delete-by-id because an out-of-order delivery resurrects or destroys a newer local edit.
- Tables written by more than one feature must have a single declared writer or an explicit merge rule; reject a second feature writing the shared table directly because concurrent writers overwrite each other's columns with stale values.
- Pending outbound operations must be durable before the network call and removed only after a confirmed result; reject in-memory queues because process death loses user edits that the UI already reported as saved.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
