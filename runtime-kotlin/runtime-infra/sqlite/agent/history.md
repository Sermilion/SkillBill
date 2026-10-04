## [2026-10-01] SKILL-396 subtask 1 — infra boundary and diagnostics repairs
Areas: runtime-kotlin/runtime-infra/sqlite, runtime-infra/skills, runtime-infra/contracts, runtime-domain goalrunner model, runtime-core architecture tests and review DI
- The connection-keyed diagnostics registry and session diagnostics/clock are gone; `RuntimeDiagnostics` is a required parameter through migrations, `DatabaseRuntime`, nested write transactions, stores, telemetry, reconciliation, accounting and the legacy ledger migration. reusable
- Stats reads now report malformed stored values (completed phase ids, gaps found, timestamps) to the diagnostics they receive; `DatabaseWriteReadinessGate` lost its defaulted schema-establishment hooks.
- Planning status snapshot derivation moved into runtime-domain; the sqlite copy and "prepared" literals were replaced by the domain wire value.
- The external agent addon source config store moved into the `externaladdon` package and shares one read/parse helper and path resolver with its sibling store; the ambient-environment baseline is now empty.
- The infra-skills import-direction architecture test was deleted and the contracts module no longer test-depends on runtime-application.
- Known limits: test-side overloads keep existing call sites unchanged; empty `skills/file` directories remain; wire, JSON, YAML, telemetry and persisted values are unchanged.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-09-18] SKILL-356 subtask 3 — ledger-owned schema evolution
Areas: runtime-kotlin/runtime-infra/sqlite core, goalrunner, review, runtime-kotlin/agent
- `DatabaseMigrations` now stamps `PRAGMA user_version` in the same transaction as the name-keyed ledger; readiness uses the stamped version and file identity. reusable
- One ledger migration owns final column, evidence, and work-list healing; named goal-runner control and telemetry-restamp migrations run once with migration diagnostics. reusable
- Migrate-on-access control and telemetry fallbacks are removed, and Early/Late schema and migration declarations are consolidated while preserving legacy repository behavior.
- Known limitation: the runtime-application legacy control migration remains intentionally separate; base schema DDL is unchanged.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-18] SKILL-356 subtask 2 — goal-runner stores implement their ports directly
Areas: runtime-kotlin/runtime-infra/sqlite/goalrunner, runtime-kotlin/runtime-core/di, runtime-kotlin/runtime-engine goalrunner fixtures
- `WorkflowGoalRunnerManifestStore` now implements `GoalRunnerManifestStore` directly; the internal delegate, context bag, and regrouped ops lattice were removed while persistence behavior and SQL stayed unchanged.
- `WorkflowGoalRunnerOutcomeStore` now has one injected construction path and implements the workflow outcome port directly; behavior-owning collaborators remain internal, and child-wedge repair always rewrites the projection.
- DI exposes only the manifest and outcome stores for goal-runner ports; fixtures supply the store dependencies explicitly. The goal-runner production surface is 27 files versus 31 in the baseline, with a net reduction of 504 lines. reusable
- Known limitation: goal-runner migration and schema consolidation remain in subtask 3.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-18] SKILL-356 runtime-infra-sqlite boundaries and simplicity
Areas: runtime-kotlin/runtime-infra/sqlite core/review/telemetry/workflow, runtime-kotlin/runtime-core DI/architecture, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-domain, runtime-kotlin/runtime-cli/engine/mcp tests
- One transaction path now owns read, write, and migration boundaries with typed database failures and rollback behavior; reusable
- SQLite construction resolves `EnvironmentContext`, `Clock`, and `RuntimeDiagnostics` at the DI boundary, removing ambient defaults and recording degradation decisions; reusable
- Unit-of-work routing owns feature-task settlements and goal-runner purge persistence; internal SQLite helpers, positional binding, and payload vocabulary are centralized
- Test fixtures provide explicit database/session setup, while architecture guards enforce dependency, clock, environment, visibility, and wire-key boundaries
- Known limits: well-formed SQL behavior remains unchanged; goal-runner store-lattice and migration/schema consolidation continue in subtasks 2 and 3
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-07] SKILL-168 Subtask 2 consistent read snapshot for ide-status

Areas: runtime-kotlin/runtime-infra-sqlite infrastructure/sqlite + db/workflow, runtime-kotlin/runtime-application work tests, runtime-kotlin/runtime-cli concurrency tests
- `DatabaseSessionFactory.read` now wraps its block in `BEGIN DEFERRED` / `COMMIT` (`inReadTransaction`), so every statement in one read block shares a single snapshot. Fixed at the seam, not in `IdeStatusService`, so all read-path consumers inherit it. reusable
- DEFERRED, never IMMEDIATE: IMMEDIATE would take a write lock and make the read path contend with the goal runtime's writers. Under WAL a deferred read snapshot stays stable without blocking writers, which is what makes holding it open across dozens of SELECTs acceptable. reusable
- Root cause it removes: untransacted reads let a writer commit mid-collection tear `IdeStatusService.collectCandidates`, dropping the only candidate and reporting `no_matching_work` for a durably `running` goal.
- `typedStatement` gained an explicit `DatabaseAccessOperation` parameter instead of hardcoding `OPEN`; read-path BEGIN/COMMIT failures now classify as `READ`. Any new transaction helper must pass the operation matching its seam. reusable
- `WorkflowStateStore` orphan-identity path throws typed `InvalidWorkflowStateSchemaError` instead of `error(...)`, so it lands in `IdeStatusService`'s existing catch list rather than escaping as an uncaught `IllegalStateException`.
- Deliberately untouched: `IdeStatusSelectionPolicy`, ide-status wire schema, `contract_version`, and golden fixtures (verified by zero-diff assertion).
- Known limit: correctness relies on the database file being in WAL mode; a rollback-journal database would have a long read transaction block writers. Tests cover both modes.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-06] SKILL-163 Subtask 3 anonymous telemetry redaction
Areas: runtime-kotlin/runtime-infra-sqlite db/telemetry + db/core, runtime-kotlin/runtime-application telemetry + telemetry/config, runtime-kotlin/runtime-core + runtime-mcp telemetry tests
- Redaction happens at payload construction, never at upload: `telemetry_outbox.payload_json` itself holds the substituted value, so an already-enqueued anonymous row can never leak the raw key even if the level later changes. reusable
- `redactIssueKey` is fail-closed by design: only the literal level `full` passes through; unknown, blank, or unresolved levels take the hashing branch. Any new level string is private by default. reusable
- Substitute is `iss_` + first 16 hex of `SHA-256(salt|issueKey)` — stable across events (so anonymous data stays correlatable by issue) and non-reversible without the salt. `redactIssueKeyReferences` applies the same substitution inside synthetic correlation ids like `SKILL-1:subtask:2`, keeping the id shape intact. reusable
- New `telemetry_local_secrets` table holds the machine-local salt (`telemetry_redaction_salt`, 32 random bytes, `INSERT OR IGNORE` + re-read so concurrent generation converges on one value). It is never a payload field and must never be added to one; it also survives a telemetry disable/enable cycle, so substitutes stay stable.
- `enqueueRuntimeException` now takes the level explicitly: at anonymous, `error_message` becomes the `[redacted]` constant and stack frames are filtered to `skillbill.` classes; `error_type` and `workflow_phase` are retained as non-sensitive.
- Governed `orchestration/contracts/telemetry-event-schema.yaml` is deliberately untouched — redaction changes values, not the event shape (asserted by zero-diff check).
- Known downstream limit: proxy/dashboard queries keying on raw `issue_key` see `iss_*` for anonymous installs from this release forward; historical raw rows are not rewritten.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-06] SKILL-163 Subtask 1 telemetry release attribution
Areas: runtime-kotlin/runtime-infra-sqlite db/core + db/telemetry, runtime-kotlin/runtime-infra-http telemetry proxy mappers, runtime-kotlin/runtime-ports persistence model, runtime-kotlin/runtime-core telemetry tests
- Telemetry events now carry the release that *produced* them: `telemetry_outbox.skill_bill_version` is stamped at enqueue time from `SkillBillVersion.VALUE` (constructor-injectable for tests), not read at upload time. An event enqueued under version A and uploaded under version B reports A. reusable
- `ensureColumn(telemetry_outbox, skill_bill_version)` must live in the `DatabaseColumnMigrations` pass that runs *after* `DatabaseMigrations.apply`: `TelemetryOutboxLastErrorMigration` rebuilds the table from an explicit column list and would silently drop a column added earlier. Ordering constraint documented inline. reusable
- Legacy/NULL version is a first-class case, not an error: `telemetryProperties` omits `skill_bill_version` entirely when null or blank rather than sending a sentinel, so pre-attribution rows still upload and are never dropped. Consumers must treat absence as "unknown release". reusable
- `TelemetryOutboxRecord.skillBillVersion` defaults to null so existing construction sites compile unchanged; the proxy payload keeps `install_id` and `$process_person_profile` untouched.
- Deliberately no change to `orchestration/contracts/telemetry-event-schema.yaml` — attribution rides as an event property, so the governed event schema stays untouched (verified by diff assertion).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-06] SKILL-136 Subtask 6 review store integrity, outbox signal, and lifecycle
Areas: runtime-kotlin/runtime-infra-sqlite db/core + db/telemetry + sqlite goal/review, runtime-kotlin/runtime-application review + goalrunner, runtime-kotlin/runtime-infra-fs snapshot gateway, runtime-kotlin/runtime-ports review + persistence, runtime-kotlin/runtime-cli review
- `telemetry_outbox.last_error` is now nullable-as-healthy: success writes `NULL`, `TelemetryOutboxLastErrorMigration` backfills legacy empty strings, and `latestError` excludes both `NULL` and legacy-empty rows so "has an error" is a real signal. Migration is idempotent on re-application. reusable
- `learnings` is neither dead schema nor a broken promotion target (AC-002 evidence recorded in `SQLiteLearningStore` header): it is live, user-driven curated memory behind `skill-bill learnings` and `ReviewLearningsPort`; `session_learnings` is a downstream per-session cache, not an upstream source. Promotion stays explicit, never a side effect of accepting a finding. reusable
- New `review_finding_outcomes` table (keyed `review_run_id` + `finding_id`, indexed) is the shared key joining the workflow review loop's `unaddressed_findings` to imported `review_runs`/`findings`, so triage dispositions and feedback join to the routed pack. Existing ledger rows migrate as unresolved. reusable
- Accepted/rejected outcomes are now loop-recorded for every run producing findings rather than only the `feedback_events` subset; `ReviewFindingStatsSupport` LEFT JOINs outcomes so runs without them stay visible.
- `DatabaseRuntime` is the only main-source `jdbc:sqlite` connection site (enforced by `RuntimeArchitectureTest`); every open applies migrations, so a `review-metrics.db` is absent or schema-complete. Regression covers the zero-byte working-directory file. reusable
- Snapshot retention: `ReviewSnapshotPruneService` + `skill-bill prune-snapshots` are opt-in and dry-run by default; no code path deletes snapshots automatically, and the live `review-metrics.db` is excluded from candidates.
- Migrations verified row-preserving against a real 107MB store copy and an 82MB legacy snapshot fixture; referential integrity sound, outbox still drains fully.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-03] SKILL-155 read-path write-lock removal
Areas: runtime-kotlin/runtime-infra-sqlite db/core + sqlite factory, runtime-kotlin/runtime-ports persistence, runtime-kotlin/runtime-application featuretask + workflow, runtime-kotlin/runtime-cli featuretask
- `DatabaseMigrations.apply` takes an optimistic read-only pre-check (`MigrationLedger.readState`) and returns early when nothing is pending, so a read open no longer grabs the write lock just to learn there is no work. The in-lock re-derivation stays the sole authority, so concurrent applies still run each migration exactly once. reusable
- Pending-work rules: missing ledger table and a version-keyed ledger both count as pending (`ensureNameKeyed` rebuilds the table under the lock); otherwise pending is any migration name absent from `applied_names`. reusable
- `DatabaseRuntime.openReadDb` opens existing databases with `SQLiteConfig.setReadOnly(true)`; an absent database is still bootstrapped write-capable because callers rely on first-use creation. This makes read-path writes fail loudly instead of silently contending.
- New `DatabaseSessionFactory.selfManagedWrite` (abstract; a `read` default would silently degrade a write seam) is the write-capable, no-outer-transaction seam for repository methods that own their own transaction boundary and cannot nest in `transaction`. Callers previously misusing `read` for writes (worker coordinator, planning checkpoint, rejected-output CLI) moved onto it. reusable
- Contended read and no-op apply both return under 1000 ms, well inside the 5000 ms busy_timeout; revert-proofed by tests that fail with SQLITE_BUSY / a completed write when either change is reverted.
- `MigrationLedger.State` owns `hasPendingWork` (detekt LargeClass on DatabaseMigrations); no schema or contract-version change, purely a locking/capability change.
- Goal-runner write-transaction lifetime (criterion 7 determination): no transaction spans child agent execution. `JvmAgentRunProcessRunner` holds no database session; it observes progress only through injected probes. Every goal-runner store operation (`GoalRunnerWorkflowStores`, worker coordinator, phase recorder) opens, commits, and closes its own short `database.transaction` per call. The minute-long contention that defeated the 5000 ms busy_timeout came from open frequency, not a held lock: the supervisor polls progress on a 250 ms cadence (`SUPERVISOR_POLL_CADENCE_NANOS`, memoized to ~200 ms via `TICK_MEMO_WINDOW_NANOS` in `GoalRunnerTickProgressReader`), and before this fix every one of those opens ran `DatabaseMigrations.apply`'s unconditional `BEGIN IMMEDIATE` — several write-lock acquisitions per second for the whole child run, starving any reader that also needed the write lock. No separate defect exists; there is no holding call path to name. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-10] SKILL-109 Follow-up 1 telemetry reliability remediation
Areas: runtime-kotlin/runtime-infra-sqlite reconciliation/telemetry, runtime-kotlin/runtime-application telemetry sync, runtime-kotlin/runtime-ports persistence, runtime-kotlin/runtime-domain review labels, orchestration/contracts telemetry schema
- Goal-issue abandonment is gated to last-segment-blocked candidates (last_blocked_at NOT NULL AND latest_segment_workflow_id = last_blocked_segment_workflow_id); never-blocked or newer-active issues are never abandoned. reusable
- Reconciliation candidate selection extracted to StaleReconciliationCandidateQuery (single static UNION-ALL CTE, ORDER BY stale_at, LIMIT batch); partial indexes back every candidate predicate; goal-issue progress recovery reconstructs aggregates from goal_run_sessions or suppresses emission loudly (never blank first_started_at). reusable
- Pattern: manual `skill-bill telemetry sync` forces reconciliation before flush (cadenceSeconds=0, bounded batch) while autoSync keeps the independent 300s cadence guard — backlogs drain across repeated bounded runs. reusable
- feature-task prose/runtime finished payloads now emit duration_seconds (schema + MCP inputSchema kept in parity, additive, contract_version unchanged); the request-taking TelemetryReconciliationRepository method is now primary so no impl silently reverts to an unbounded transaction.
- NormalizedStackLabel normalizes mixed KMP/Kotlin labels to kmp; reliability contract tests now assert against real emitted outbox rows with genuine mutation-style negatives (aggregate accuracy, routing normalization).
Feature flag: N/A
Acceptance criteria: 18/18 implemented

## [2026-07-09] SKILL-109 terminal telemetry completeness
Areas: runtime-kotlin/runtime-infra-sqlite reconciliation/schema, runtime-kotlin/runtime-application telemetry auto-sync, runtime-kotlin/runtime-ports persistence
- StaleSessionReconciler now runs from TelemetryService.autoSync through a UnitOfWork reconciliation port before outbox sync, making production sync the bounded terminal-event repair trigger. reusable
- SQLite reconciliation selects unfinished/unemitted feature implement, feature-task-runtime, feature verify, quality check, and abandoned goal-issue rows, marks terminal state, then emits through shared finished helpers for exact-once telemetry. reusable
- Goal issue progress stores last activity and last-blocked timestamps so inactive blocked goals can emit `goal_issue_finished` with `status=abandoned`; migrations self-heal legacy rows additively.
- Pattern: late normal finishes must respect already-emitted stale terminals, while feature implement may still count duplicate finish attempts without emitting duplicate terminal telemetry.
- Known limitation: leakage target under 5% still requires post-deploy analytics confirmation after reconciled rows are present in telemetry.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-07-09] SKILL-109 reliable lifecycle duration telemetry
Areas: runtime-kotlin/runtime-infra-sqlite lifecycle migrations/tests, orchestration/contracts telemetry schema, runtime-mcp goal telemetry parity
- Lifecycle session self-heal now ensures legacy feature implement, feature verify, and quality check tables have `started_at`; feature implement blank starts are recovered from matching workflow rows before falling back to `CURRENT_TIMESTAMP`. reusable
- Feature implement finished telemetry regression coverage builds a pre-column legacy database and asserts migrated duration_seconds reflects real elapsed time instead of a blank-start near-zero value.
- Goal terminal telemetry standardizes on `duration_seconds` at the event/schema boundary while preserving internal millisecond persistence.
- Known limitation: legacy feature verify and quality check blank starts still backfill to migration time because they lack a matching workflow-start recovery source.
Feature flag: N/A
Acceptance criteria: 4/4 implemented
