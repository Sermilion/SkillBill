---
name: bill-go-code-review-persistence
description: Use when reviewing Go database/sql lifecycles, queries, transactions, isolation, pools, migrations, mapping, and applicable data libraries.
internal-for: skill-bill
---

# Go Persistence Review Specialist

Own durable-data correctness and database resource lifecycles. Apply library-specific guidance only when repository evidence shows that library is present.

## Focus

- `database/sql` query, row, transaction, isolation, locking, pool, mapping, and migration correctness
- Data-loss, consistency, durability, and mixed-version rollout failures

## Ignore

- Query style preferences without a correctness or availability consequence
- Library-specific rules when the repository does not use that library

## Applicability

Apply to `database/sql`, migrations, query generation, and repository code. Examine commit, rollback, scan, and cancellation paths alongside the successful query.

## Project-Specific Rules

### Go Persistence Rules

- Require `db.QueryContext` and `db.ExecContext` to receive the operation context; context-free calls risk runaway queries after request timeout.
- Ensure every `*sql.Rows` is closed and `rows.Err()` checked after iteration; skipped closure leaks pool connections and skipped errors return incomplete data.
- Verify each `rows.Scan` destination matches selected column order and nullability; mismatches cause runtime failures or corrupt field mapping.
- Require `QueryRowContext(...).Scan` errors to distinguish `sql.ErrNoRows`; collapsing absence into server failure breaks repository contracts.
- Ensure transactions created by `BeginTx` register `Rollback` immediately, inspect the final `Commit` error, and do not report success after a failed commit; missing rollback leaks connections while ignored commit failure loses durable writes.
- Reject non-transactional multi-statement invariants when partial success is invalid; a mid-sequence error can corrupt durable state.
- Verify `sql.TxOptions` isolation and read-only settings match the invariant being protected; default isolation may permit lost updates or stale authorization data.
- Require read-modify-write paths to use a lock, version column, or conditional update; unchecked concurrency risks overwritten data.
- Ensure dynamic SQL values use placeholders rather than `fmt.Sprintf`; string interpolation permits injection and invalid quoting.
- Verify pool settings such as `SetMaxOpenConns`, `SetMaxIdleConns`, and `SetConnMaxLifetime` match database limits; bad sizing causes starvation or connection storms.
- Require migration additions to be compatible with the running old binary during rollout; immediate `NOT NULL` or destructive renames can break live deployments.
- Require expand-and-contract migrations to stage additive schema, mixed-version backfill, reader/writer cutover, and destructive removal separately; collapsing phases breaks old binaries or corrupts data during rolling deployment.
- Ensure backfills are bounded, restartable, and separate from schema locks when data volume is material; monolithic migration work risks operational timeout.
- Verify index builds, table rewrites, lock duration, and rollback expectations with the repository migration tool such as `golang-migrate` or Goose when present; an unbounded DDL lock causes availability failure without a safe recovery path.
- Verify domain mapping preserves `sql.NullString`, pointers, or repository-specific nullable types intentionally; lossy conversion corrupts absent-versus-empty data.
- Require applicable `sqlc` generated query changes to be produced by the repository generator; hand edits will drift and fail the next build.
- Ensure applicable GORM operations check `Error` and intentional `RowsAffected`; ignored results can report success after failed or missing writes.
- Verify applicable Ent or sqlx transaction handles are threaded through all repository calls; escaping to the root client breaks atomicity and risks partial data.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
