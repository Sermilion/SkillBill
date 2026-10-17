# SQLite boundary decisions

## [2026-10-17] Isolate malformed work-list rows in aggregate list
Context: SQLiteWorkListRepository.list threw INVALID_WORK_LIST_ROW on the first bad toWorkItem parse and aborted the whole board and list.
Decision: list skips that code through tryWorkItemOrReject, keeps healthy items, and records rejectedRows. findByWorkflowId still typed-rejects a corrupt target.
Reason: Aggregate reads must retain healthy records beside malformed ones. Explicit identity access must still fail loudly so absence is not silent.

## [2026-10-17] Guard issue-finish overwrite on finished_at and terminal status
Context: Issue finish used finished_event_emitted_at as the overwrite guard, so a duplicate finish before or after unavailable emission could rewrite the admitted historical outcome.
Decision: UPDATE only when finished_at IS NULL and status is not already completed, completed_no_change, failed, or abandoned. Emission still follows save so a recorded finish can retry unavailable emission.
Reason: Telemetry emission is not a second completion authority. The first admitted finish must stay; a later duplicate is a no-op.

## [2026-10-17] Refuse phase settlement before INSERT when the workflow cannot settle
Context: MCP and other upsert callers can reach feature_task_phase_settlements without the phase-loop fences, including against a missing or already terminal runtime row.
Decision: Read the runtime workflow row and worker lease before INSERT. Missing rows return WORKFLOW_NOT_SETTLEABLE; terminal status returns TERMINAL_WORKFLOW; lower attempts, takeover-reserved, expired, or wrong-phase leases refuse with the matching reason.
Reason: A completed envelope must not create a settlement for closed or unowned work. Attempt and lease identity belong at this durable gate because the table key is only workflow, phase, and attempt.
Alternatives considered: Enforcing attempt and owner only in the phase loop, rejected because direct upserts would bypass it.
Revisit when: subtask 2 needs parent-aggregate settlement rules, or a named recovery path must settle a terminal workflow.

## [2026-10-17] Treat settlement ON CONFLICT as identity compare, never overwrite
Context: INSERT ON CONFLICT DO NOTHING reports success even when the existing row has a different kind or envelope.
Decision: After a zero-row insert, reread the key. Matching kind and envelope is IdenticalReplay; any mismatch is Conflicting and leaves the first envelope in place.
Reason: A contradictory upsert is not idempotent because SQL succeeded. The first accepted settlement is the authoritative phase output.
Alternatives considered: ON CONFLICT DO UPDATE, rejected because it would replace an accepted output with a later envelope.

## [2026-10-17] Ordinary terminal workflow writes accept only an identical snapshot
Context: A late running or blocked save on a completed, failed, or abandoned row reopened work and cleared finished_at.
Decision: When the current runtime row is terminal, compare status, current step, steps and artifacts JSON, and finished_at. Identical snapshots return Accepted; any other proposed row is TerminalRefused, and the SQL upsert also refuses non-identical replacement.
Reason: Intentional recovery uses named resume or continue with retained identity, not an ordinary overwrite. Identical replay must not fail retries.
Revisit when: a supported recovery API needs to replace a terminal snapshot in place.

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
