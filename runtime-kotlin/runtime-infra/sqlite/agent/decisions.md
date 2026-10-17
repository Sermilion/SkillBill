# SQLite boundary decisions

## [2026-10-17] Rewrite finished repair rows that predate final-audit records
Context: Repair state contract 0.1 requires `final_audit_required`, `final_audit_digest`, and `final_audits`. Its mode and stage enums no longer include `accepted`. Rows written by the previous runtime stored that token once the queue was empty, and detail reads now fail schema validation.
Decision: Migration 60 rewrites only that finished shape to mode `final_full` and stage `final_accepted`. It records one final audit at the existing revision, with no regressions, using the closure tree and checkpoint already stored on the row. The digest is a hash of that row's catalog, execution, spec, revision, audited refs, and closure anchor. A row that says `accepted` but is not an empty queue, or that has no closure anchor, fails the migration. Coherence is checked before the rewrite is saved. The rewrite lives in `migration.area.acceptedrepair` so the area package stays within the 12-file sibling ceiling.
Reason: `accepted` meant source repair had finished. `final_accepted` is that state in the current contract. The audit record uses evidence the row already stored and does not invent a later revision.
Alternatives considered: Reading `accepted` as `final_audit_required` would satisfy the schema but would tell the operator a final audit is still required. Deleting the artifact would drop the repair history the detail view needs.

## [2026-10-09] Use an append-only migration for phase-strategy telemetry columns
Context: Version 39 was already recorded on existing databases, while later telemetry writes required two columns it had once added.
Decision: Register a uniquely named v49 migration and reuse the idempotent nullable-column helper for both columns.
Reason: The name-keyed ledger skips already-applied migrations; a new migration upgrades old files without rewriting ledger history, backfilling measurements, or changing existing rows.
Alternatives considered: Revising version 39 or fabricating historical strategy values would leave already-applied databases broken or make unmeasured history untruthful.

## [2026-10-01] Pass RuntimeDiagnostics explicitly instead of a connection registry
Context: SQLite helpers looked up diagnostics and clock through a registry keyed by connection, hiding who reported degradation and letting call sites silently skip it.
Decision: Diagnostics is a required parameter on every store, migration, and transaction helper that can degrade; tests use test-side overloads that supply a test diagnostics sink.
Reason: Explicit threading makes ownership visible and removes mutable global state; required parameters stop new code from dropping reports.
Alternatives considered: Keeping the registry with attach/detach was rejected as hidden state.

## [2026-09-30] Join the owned admission transaction for worker acquisition
Context: Atomic execution-plan admission acquires a worker lease inside an existing SQLite write transaction. Starting a second BEGIN there fails.
Decision: Worker acquisition joins the caller's owned transaction when present. Standalone acquisition still opens its own write transaction.
Reason: The workflow advance and worker lease must commit or roll back together. Joining the existing transaction preserves that ownership and keeps standalone acquisition atomic.


## [2026-10-11] Bound nested status reads to one read transaction
Context: A board request repeatedly opened SQLite connections through nested status services. Issue and parent lookup queries also scanned large workflow artifacts for each historical goal.
Decision: Index both stored issue-key forms, missing-key fallback candidates and guarded parent identities. Select execution candidates through indexed issue ids. Synchronous nested reads on the same factory and thread reuse the outer read transaction, with thread state removed in finally. Write transactions retain their separate ownership.
Reason: One status request needs a consistent snapshot and bounded connection lifetime. Reopening connections and rescanning unrelated artifacts amplified work across the complete workflow history. The snapshot regression proves that nested reads retain the outer view and that the next request sees committed writes after an exception.

## [2026-10-12] Admit desktop controls inside one owned transaction
Context: Desktop control admission validates the durable goal and lease before invoking the existing control service. A second SQLite connection cannot write while admission owns the write lock.
Decision: Nested reads, transactions, and self-managed writes on the same factory and thread join the outer write unit. The outer transaction owns commit and rollback. Thread state clears in finally. An independent write within a read snapshot keeps its existing ownership.
Reason: Validation and the admitted control must see one durable identity and commit together. The nested-write regression proves rollback and reuse after failure. Desktop status projection joins readIfPresent, which refuses missing or incompatible stores without initialization. CLI read retains its fresh-store initialization contract.
