# SQLite boundary decisions

## [2026-10-01] Pass RuntimeDiagnostics explicitly instead of a connection registry
Context: SQLite helpers looked up diagnostics and clock through a registry keyed by connection, hiding who reported degradation and letting call sites silently skip it.
Decision: Diagnostics is a required parameter on every store, migration, and transaction helper that can degrade; tests use test-side overloads that supply a test diagnostics sink.
Reason: Explicit threading makes ownership visible and removes mutable global state; required parameters stop new code from dropping reports.
Alternatives considered: Keeping the registry with attach/detach was rejected as hidden state.

## [2026-09-30] Join the owned admission transaction for worker acquisition
Context: Atomic execution-plan admission acquires a worker lease inside an existing SQLite write transaction. Starting a second BEGIN there fails.
Decision: Worker acquisition joins the caller's owned transaction when present. Standalone acquisition still opens its own write transaction.
Reason: The workflow advance and worker lease must commit or roll back together. Joining the existing transaction preserves that ownership and keeps standalone acquisition atomic.
