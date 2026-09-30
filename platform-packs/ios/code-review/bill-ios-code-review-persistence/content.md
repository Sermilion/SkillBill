---
name: bill-ios-code-review-persistence
description: Use when reviewing iOS persistence, migration, transaction, and offline-sync risks.
internal-for: skill-bill
---

# Persistence Review Specialist

Review only data durability and consistency failures.

## Focus

- Core Data, SwiftData, and detected SQLite-family stores
- Migration and transaction safety
- Offline synchronization and recovery

## Ignore

- Query style without correctness or performance impact
- Framework preferences where the repository's detected store is coherent

## Applicability

Use the Core Data/SwiftData branch or the detected GRDB, SQLite, Realm, or other local-store branch. Review queue and actor ownership, including work on its owning queue and on its owning actor; never pass an `NSManagedObject` or `ModelContext` across those boundaries. Consider installed-version upgrades, offline operation, relaunch, and supported OS versions. Repo-local rules are optional enrichment.

## Project-Specific Rules

### Isolation And Transaction Rules

- `NSManagedObjectContext` work must use `perform` or `performAndWait`; reject queue crossings that race and corrupt Core Data state.
- SwiftData `ModelContext` values must remain on their owning actor; never cross tasks with a context because isolation failure can crash or corrupt data.
- Cross-context transfers must use an `NSManagedObjectID` or an explicitly `Sendable` value representation; reject passing `NSManagedObject` instances across lifecycle boundaries.
- Multi-record invariants must use a detected store transaction such as `DatabaseWriter.write` or a context save; reject partial commits that leave invalid data after failure.
- SQLite statements must bind values through `StatementArguments` or equivalent parameters; reject string interpolation that creates injection or serialization failures.
- Concurrent writers must define merge policy, conflict handling, or serialization ownership in `NSMergePolicy`; reject default behavior when it can silently cause data-loss failure.

### Migration And Offline Rules

- A shipped GRDB or SQLite migration must never be edited; require a new `DatabaseMigrator` migration because existing devices otherwise retain an incompatible schema and fail operationally.
- Core Data lightweight migration and SwiftData automatic migration must be used only for inferable `NSManagedObjectModel` changes; reject unsafe model drift that causes store-loading failure.
- Semantic changes must provide `VersionedSchema` with `SchemaMigrationPlan`, staged Core Data migration, or detected equivalent plus evidence that an existing store upgrades successfully; reject a missing old-store test because it risks data loss.
- Offline writes must persist an operation identifier and retry state in the detected store before network dispatch; reject memory-only queues that cause user-data loss on relaunch.
- Sync conflict resolution must preserve `version`, tombstones, and idempotency under retry; reject delete or merge ordering that resurrects stale data and causes consistency failure.
- Cache eviction must download and durably swap a replacement with `FileManager.replaceItemAt` or detected equivalent before deleting the old artifact; reject delete-first flows that cause offline data loss.
- Recovery must surface migration or `NSPersistentStore` open failure and preserve the original store for diagnostics; never silently delete it because that corrupts user trust and data.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
