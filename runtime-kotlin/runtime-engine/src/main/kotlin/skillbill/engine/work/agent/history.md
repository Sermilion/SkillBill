## [2026-10-11] SKILL-418 subtask 1: governed work board and detail CLI
Areas: runtime-engine work; runtime-cli work; runtime-contracts identity/status and failures; runtime-ports idestatus; runtime-infra contracts and sqlite; runtime-core workflow DI; orchestration/contracts
- Added read-only work board and work detail commands with schema-validated 1.0 envelopes around existing ide-status 0.3 snapshots.
- Board projects machine-wide work by durable repository identity, includes unfinished work and completion-window results, and reports omitted candidates through diagnostics.
- Detail resolves exact workflow or invocation ids outside board retention, with standalone pipelines and available goal manifest, phase, ledger, liveness, observability, worktree, and validation projections.
- Pattern: reuse status projection and lease handling; keep completion metadata on the new wrapper and schema/SQLite adapters behind ports.
- reusable: typed board/detail requests and results, payload keys, validators, and all-identities retention and exact-invocation persistence reads.
- Breaking changes: none intended for work list/status or ide-status 0.3. Desktop UI belongs to subtask 2.
- Known limit: the required goal-detail regression fixture covering historical-parent isolation and all detail extras is absent, leaving AC-009 partial.
Feature flag: N/A
Acceptance criteria: 8/9 implemented
