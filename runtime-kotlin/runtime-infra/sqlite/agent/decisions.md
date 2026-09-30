# SQLite boundary decisions

## [2026-09-30] Join the owned admission transaction for worker acquisition
Context: Atomic execution-plan admission acquires a worker lease inside an existing SQLite write transaction. Starting a second BEGIN there fails.
Decision: Worker acquisition joins the caller's owned transaction when present. Standalone acquisition still opens its own write transaction.
Reason: The workflow advance and worker lease must commit or roll back together. Joining the existing transaction preserves that ownership and keeps standalone acquisition atomic.
