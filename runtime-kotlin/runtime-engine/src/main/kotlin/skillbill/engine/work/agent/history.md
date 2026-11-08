## [2026-11-08] SKILL-522 build views on board and detail
Areas: runtime-engine work; runtime-ports idestatus models; runtime-contracts identity/status; runtime-cli work; runtime-infra/contracts; orchestration/contracts
- MEDIUM feature. Both board paths and detail overlay the latest build after their main database reads return. Invocation identity precedes workflow identity; parent-run matching exposes child builds on goal items.
- WorkBuildView, WorkBuildPrevious and the CLI build mapper are reusable. History uses the build row's repository, command and start cutoff so the current build stays outside its own usual time and previous result.
- Open rows report running only for live work items. Unknown outcomes or open rows on finished items report finished; missing ends retain null completion and elapsed values.
- Build-read failures omit build data for the board page or detail and emit a best-effort warning. Cancellation propagates.
- Work-board advances to 1.3 and work-detail to 1.10 with optional nullable build views, shared keys and schema validation. Ide-status remains 0.6.
- Limits: the overlay can issue two indexed reads per item and does not rewrite persisted board projections.
Feature flag: N/A
Acceptance criteria: 3/3 read-surface subtask criteria implemented; covers parent criterion 3

## [2026-11-06] SKILL-506 Verify run detail projection
Areas: runtime-engine work and Verify operation, runtime-ports idestatus and telemetry, runtime-infra/sqlite, QA report mapping
- Work detail projects typed Verify artifacts, criteria, findings, QA results, verdict, supersession and stale reason for Verify runs.
- Receipt parsers retain raw findings with parsed fields; QA mapping shares finding conversion with standalone QA. reusable
- Verdict receipts store an optional work summary separately from the verdict. Older runs can lack summaries and receipts; stale reasons use the existing session column.
- CLI JSON does not serialize the Verify projection. Receipt contracts and per-step timing remain out of scope.
Feature flag: N/A
Acceptance criteria: 5/5 implemented across runtime and desktop

## [2026-11-04] SKILL-479 subtask 1: bind task identity without minting
Areas: runtime-engine work control; desktop core data and domain
- Goal and plan preparation bind an admitted first-token key, a complete typed key, or a tracker key in a URL. Missing identity returns inventory prefixes as NeedsKey without allocating.
- Occupied keys and used URLs are refused. Explicit typed reuse can pass terminal-only preflight for completed or abandoned runs; running, resumable, and continuation runs remain refused.
- Desktop carries reuseRequested to runtime preparation. Goal and plan Ready results report allocated=false, so reservation conflicts do not trigger a new key allocation.
- Limitation: desktop operator-message detection and the key-choice UI belong to the desktop chat boundary.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-30] SKILL-447 board executing agent
Areas: runtime-engine work, runtime-ports, runtime-contracts, runtime-infra/sqlite, desktop work and history
- Board projection carries the executing agent for goals, standalone runs and feature-task phases. Desktop uses it for display; launches retain the agent from run options.
- Migration 92 marks the persisted board model incomplete so the existing projector rebuilds rows with the additive executing-agent field. Older payloads decode the field as null.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-23] SKILL-435 subtask 5: shared incident presentation
Areas: runtime-engine work, diagnostics and goalrunner; runtime-ports idestatus and blockincident; runtime-domain blockincident; runtime-contracts work and persistence; runtime-infra sqlite; runtime-cli kernel/work, goal, featuretask and phase; desktop-tray data, domain, detail and bootstrap; build-identity; docs
- Added one runtime-built incident projection for terminal and desktop, with cause, progress, next action, evidence, recovery, report and telemetry status.
- Retained up to 10 earlier incidents newest first after resume; current incident selection requires the accepted live workflow or invocation identity.
- Followed runtime-owned recovery policy and read-only projection. Recovery attempts take precedence over eligibility when deriving progress.
- reusable: incident projection DTOs, stable wire keys, bounded history reads, and shared CLI JSON/text mapping and recover-command formatting under cli/kernel/work.
- Desktop maps typed recovery controls, refreshes detail after results, and presents prerequisite confirmation, preview, keyboard-accessible evidence and retained history.
- Build identity advertises incident-detail capability and minimum read schema; unsupported runtimes show the newer-runtime message before incompatible detail access.
- Compatibility: work detail adds incident and incident_history. Recovery commands include incident revision and prerequisite confirmations; no desktop writable database or recovery coordinator was added.
- Known limits: history is capped at 10; telemetry exposes search identifiers without a PostHog URL.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

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
