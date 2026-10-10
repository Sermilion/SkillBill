# SKILL-414 Subtask 1 - Stale workflow-state rows must not break the runtime

Parent spec: `spec.md` (Area 1).

## Scope

Pre-current workflow-state rows get an upgrade path, plus a terminalize path for any version the current schema cannot read. Every caller that loops over workflow-state rows tolerates one unreadable row. Ignored verify write results are checked.

Paths below are under `../../../runtime-kotlin` unless marked repo-relative.

1. **Readable versions.** Add `WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS` beside `WORKFLOW_STATE_CONTRACT_VERSION` in `runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateContractVersions.kt`, mirroring `FEATURE_TASK_RUNTIME_READABLE_CONTRACT_VERSIONS`. `WORKFLOW_STATE_CONTRACT_VERSION` stays `"0.3"`, because schema-identity tests pin it and bumping it would make every row stale again.
2. **Validator.** In `runtime-infra/contracts/.../workflow/WorkflowStateSchemaValidator.kt`, normalize a readable older `contract_version` to the current one in the wire map before schema validation. A version outside the set still fails with the existing invalid-workflow-state error. Drop the WARNING drift log before the throw to `Level.FINE`, because the exception already carries the message and callers now tolerate it per row.
3. **Write re-stamp.** In `runtime-infra/sqlite/.../workflow/WorkflowStateWrites.kt`, `bindWorkflowRow` and `bindFeatureTaskWorkflowRow` persist `WORKFLOW_STATE_CONTRACT_VERSION` when the stored version is an older member of the readable set. A stale-but-readable row can then be abandoned, and the write stamps it current. Rows whose column holds a different contract family keep their value (see the assumption below).
4. **Terminalize path.** Decide per pre-current version from evidence: read the workflow-state schema (path `WorkflowStateSchemaPaths.REPO_RELATIVE_PATH`) and its git history.
   - A version whose rows validate against the current schema once the version string is normalized joins the readable set.
   - A version whose row shape differs stays out of the set. Add a new named `DatabaseMigration` in `DatabaseMigrationEntries.kt`, using the next free version (50 is the last known; recheck right before writing because concurrent sessions add migrations). For example `terminalize-unreadable-workflow-state-rows`. It marks non-terminal rows in `feature_verify_workflows` and the workflow-state-validated rows of `feature_task_workflows` whose `contract_version` is outside the set as abandoned, terminal, with `finished_at` and `state_entered_at` set. Follow the `terminalizeLegacyProseFeatureTaskWorkflowRow` SQL pattern. Leave their `contract_version` unchanged and keep the step idempotent.
   - When every pre-current version is readable, add no migration.
5. **Per-row tolerance.** Catch the failure per row with the existing `isInvalidWorkflowStateFailure()` predicate, rethrow anything else, skip the row, and record one best-effort diagnostic line per skipped row with the workflow id.
   - `WorkflowService.list` (runtime-application) returns the readable rows. `workflowCount` equals the number returned.
   - `WorkflowService.latest` returns the newest readable row of the family instead of failing on a stale newest row.
   - `get`, `resume` and `continueWorkflow` on an explicitly requested unreadable row may keep throwing.
   - `WorkListService` (`validateWorkflowSnapshots`) drops an unreadable `TASK_RUNTIME` or `VERIFY` row and keeps the rest. The existing missing-snapshot error is unchanged.
   - `IdeStatusProjector` (runtime-engine): find the loop over candidates. If one candidate's invalid row can propagate out of the loop, wrap it per candidate with the existing `incompatible(candidate, context, reason)` helper.
   - MCP `feature_verify_workflow_list` and `feature_verify_workflow_latest` delegate to `WorkflowService` and need no own tolerance.
6. **VerifyOperation** (runtime-engine `operation/verify/VerifyOperation.kt`):
   - `supersedeParked` must not return early because one row is unreadable. Remove the whole-list `skipUnreadable` wrapper once `list` tolerates per row, and keep the per-row wrapper.
   - Check the supersede `store.write` result. On `VerifyWrite.Rejected`, record a one-line best-effort diagnostic with the workflow id and continue. Supersede stays best effort and does not fail the new run.
   - In `failExtraction`, when the FAILED write returns `VerifyWrite.Rejected`, include the rejection in the returned `OperationOutcome.Failed` message.
7. **Census.** Write `census_subtask_1.md` in this spec folder. List each pre-current workflow-state contract version found, the path chosen (readable or terminalized), and the schema evidence. Also record what each table's `contract_version` column holds for `feature_task_workflows` rows, and whether the IDE projector loop needed wrapping.

### Assumptions to confirm during implement

- The plan did not know whether 0.1/0.2 rows differ from 0.3 in more than the version string. Scope item 4 resolves this from the schema history.
- The plan assumes `feature_task_workflows.contract_version` holds the workflow-state version for phase-workflow (`mode runtime`) rows, because `FeatureTaskRuntimePhaseWorkflowGraph` uses `WORKFLOW_STATE_CONTRACT_VERSION`. If the column also stores feature-task runtime contract versions for other rows, apply the re-stamp and terminalize rules only to rows the workflow-state validator checks.
- Skipped rows are not surfaced in list payloads. Skip plus a diagnostic keeps CLI and MCP JSON goldens stable. No new payload field is added.

## Implementation Details

Implement this subtask only. Do not bump `WORKFLOW_STATE_CONTRACT_VERSION`. Do not change schema shape. Do not add list-payload fields. Do not touch `SkillBillCommand.kt`. Do not run install, compile, tests, or `./gradlew check`. Mocks use `relaxUnitFun = true`. Recheck the last `DatabaseMigrationEntries.kt` version immediately before adding a migration.

Settled from the preplan digest (implement confirms in `census_subtask_1.md`, it does not reopen the product choice):

- **Readable vs terminalize.** Default `0.1` and `0.2` into `WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS`. Digest evidence: `WorkflowStateStoreTest` already stores `contractVersion = "0.1"` on verify rows; the named YAML difference is `properties.contract_version.const` `"0.3"`; historical `CHECK (contract_version = '0.1')` then `'0.2'` were table-creation constraints. If schema git history shows a row shape that still fails after version-string normalization, that version stays out of the set and is terminalized instead. If every pre-current version is readable, add no migration.
- **IDE collapse.** `IdeStatusProjector.project` is one candidate, not a loop. `projectWorkflowFamily` (VERIFY) validates; `projectRuntime` (TASK_RUNTIME) does not. `IdeStatusService` selects via `IdeStatusSelectionPolicy.select` then calls `projector.project`. Surrounding `database.read` maps any `isInvalidWorkflowStateFailure()` during candidate assembly or the selected VERIFY validate to one `incompatibleRecord` for the whole status call. Wrap unreadable candidates before select so a readable sibling can still project. When the selected VERIFY row fails validate, report it with existing `incompatible(candidate, context, reason)` instead of throwing out of `database.read`. Record whether `collectCandidates` validates snapshots, and which wrap landed, in the census.
- **`feature_task_workflows.contract_version`.** `FeatureTaskRuntimePhaseWorkflowGraph` sets `contractVersion = WORKFLOW_STATE_CONTRACT_VERSION` for phase-workflow (`mode runtime`) rows. Re-stamp and terminalize only rows the workflow-state validator checks. Leave a value from a different contract family unchanged.
- **`isInvalidWorkflowStateFailure()`.** Keep the existing predicate (`INVALID_WORKFLOW_STATE_SCHEMA` or `INVALID_CHECKPOINT_IDENTITY_VERSION`). Skipping checkpoint-identity failures on list is accepted; do not narrow it.

### Task 1 — Census of pre-current versions (AC-001, AC-004, AC-007)

Write `census_subtask_1.md`.

Read schema git history for `../../../orchestration/contracts/workflow-state-schema.yaml` (`WorkflowStateSchemaPaths.REPO_RELATIVE_PATH`; classpath `skillbill/infrastructure/contracts/workflow-state-schema.yaml`; schema id `https://skill-bill.dev/contracts/workflow-state-schema.yaml`). List every pre-current workflow-state contract version with path (readable or terminalized) and evidence. Record what `feature_task_workflows.contract_version` holds for non-runtime rows. Record the IDE loop/collection decision from Task 7.

Constraint: census is evidence, not a second product decision. Identity pins stay: schema const `"0.3"`, Kotlin const `"0.3"`, `WorkflowStateSchemaContractVersionTest` in `../../../runtime-kotlin/runtime-infra/skills/src/repoTest/kotlin/skillbill/scaffold`.

test_obligations: none (documentation).

### Task 2 — Readable-set constant (AC-001)

In `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateContractVersions.kt`, add `WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS` as a `Set<String>` mirroring `FEATURE_TASK_RUNTIME_READABLE_CONTRACT_VERSIONS` in `FeatureTaskRuntimeContractVersions.kt`. The set always contains `WORKFLOW_STATE_CONTRACT_VERSION` (still `"0.3"`) plus each pre-current version Task 1 lists as readable. Default membership is `"0.3"`, `"0.1"`, `"0.2"`.

test_obligations: none. Existing schema-identity tests already pin the current const; a new constant-membership test would only mirror the set literal.

### Task 3 — Validator normalizes readable versions (AC-002)

In `../../../runtime-kotlin/runtime-infra/contracts/src/main/kotlin/skillbill/infrastructure/contracts/workflow/WorkflowStateSchemaValidator.kt`, change the map overload of `validate(parsedYaml, slug)`: copy the map; if `contract_version` is in the readable set, replace it with `WORKFLOW_STATE_CONTRACT_VERSION` before `ClasspathContractSchemaLoader.validate`. Keep `invalidWorkflowStateSchemaError(...)` for any other version. Change `buildWorkflowStateSchemaDriftLog` from `Level.WARNING` to `Level.FINE`. The snapshot overload still forwards to the map overload.

Callers already tolerate per-row throws through `Throwable.isInvalidWorkflowStateFailure()` in `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ShellContentContractFailures.kt`.

test_obligations: none here. No FINE-log test. Observable upgrade and reject behavior is covered by Tasks 5, 6, 8, and 10.

### Task 4 — Persist current version on readable writes (AC-003, AC-011)

In `../../../runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/workflow/WorkflowStateWrites.kt`, `PreparedStatement.bindWorkflowRow(row, defaultContractVersion, insertionTimestamp)` and `bindFeatureTaskWorkflowRow(row, mode, implementationSkill, defaultContractVersion, insertionTimestamp)` currently bind `row.contractVersion.ifBlank { defaultContractVersion }`. Persist `WORKFLOW_STATE_CONTRACT_VERSION` when the stored version is an older member of the readable workflow-state set. Leave a value from a different contract family unchanged. `FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION` in the same file stays `"0.3"` and must remain equal to `WORKFLOW_STATE_CONTRACT_VERSION` (`WorkflowStateStoreTest` already requires that). Follow `Connection.terminalizeLegacyProseFeatureTaskWorkflowRow(row: WorkflowStateRecord)` for any terminalize SQL (UPDATE status/artifacts/step/`finished_at`/`state_entered_at`, WHERE `workflow_id`, `executeUpdate` must be 1).

test_obligations: one sqlite store test (Task 10) — a row stored with an older readable `contract_version` can be read, written to a terminal status, and the write persists `WORKFLOW_STATE_CONTRACT_VERSION`. Realistic bug: bind keeps `"0.1"`, so abandon re-validates the stale version and fails schema.

### Task 5 — Conditional terminalize migration (AC-004, AC-011)

Last known migration in `DatabaseMigrationEntries.kt` is version 50, `add-standalone-phase-status-authority`. Recheck immediately before writing.

If Task 1 lists any terminalized version, add the next free version (51 unless another session already took it), named like `terminalize-unreadable-workflow-state-rows`. Idempotent. Mark non-terminal rows in `feature_verify_workflows` and workflow-state-validated rows of `feature_task_workflows` whose `contract_version` is outside the readable set as abandoned and terminal, with `finished_at` and `state_entered_at` set. Leave `contract_version` unchanged. If every pre-current version is readable, add no migration.

test_obligations: only if the migration exists — one migration test that terminalizes an out-of-set non-terminal row, leaves current rows untouched, and is idempotent. Realistic bug: a second apply double-writes status, or a current `"0.3"` row is abandoned.

### Task 6 — Per-row list and latest (AC-005, AC-010)

In `../../../runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/workflow/service/WorkflowService.kt`, `list(kind, limit)` currently does `rows.map { workflowSnapshotValidator.validate(...); engine.summaryView(...) }` and sets `workflowCount = rows.size`. Catch per row with the existing `catch (error: SkillBillRuntimeException) { error.rethrowUnless(error.isInvalidWorkflowStateFailure()); ... }` pattern already on `open`/`update`. Skip the failing row, record one diagnostic line with the workflow id, return readable summaries, set `workflowCount` to the returned count. Skipped rows stay out of list payloads.

`latest(kind)` currently validates only `workflowStates.latest` and fails if that newest row is unreadable. Walk newest-first (list ordering is updated timestamp then rowid, per `WorkflowStateStoreTest`) and return the first readable row.

`get`, `resume`, and `continueWorkflow` stay loud-fail.

If `WorkflowService` has no diagnostics collaborator, inject `RuntimeDiagnostics` and record through `RuntimeDiagnosticsBestEffortWarning.record` as `VerifyOperation.skipUnreadable` does.

MCP `feature_verify_workflow_list` / `latest` delegate to `WorkflowService` and get this tolerance; add no MCP-side skip.

test_obligations: `WorkflowServiceTest` (`runtime-engine/.../persist/WorkflowServiceTest.kt`) — one list case and one latest case with an unreadable sibling. `McpVerifyWorkflowToolsTest` — one readable-plus-unreadable list case for `feature_verify_workflow_list`. Realistic bug: `rows.map` validation throws on the first stale row and the whole list (or MCP list) is lost; `latest` fails on a stale newest row and hides a readable older one.

### Task 7 — Work list and IDE status (AC-006, AC-007, AC-010)

`WorkListService.validateWorkflowSnapshots`: inject `RuntimeDiagnostics` (none today). Skip an unreadable `TASK_RUNTIME` or `VERIFY` item with one diagnostic and keep the rest. `FEATURE_GOAL` and `FEATURE_TASK_PROSE` already skip validation (`workflowFamily` returns null). Missing-snapshot `invalidWorkListRowError` stays.

`IdeStatusService` / `IdeStatusProjector`: wrap unreadable candidates before `IdeStatusSelectionPolicy.select` so one unreadable row during candidate assembly cannot replace every other candidate with a single `incompatibleRecord`. In `projectWorkflowFamily`, when the selected VERIFY validate fails, use `incompatible(candidate, context, reason)` so the failure stays on that candidate. Record the wrap (or why the loop could not propagate the failure) in `census_subtask_1.md`.

test_obligations: `WorkListServiceTest` — one test that drops an unreadable task-runtime or verify row and returns the remaining items. Realistic bug: one stale snapshot throws in `validateWorkflowSnapshots` and the whole work list is empty. IDE projector test only if the wrap landed — one test that another candidate still projects. Realistic bug: one unreadable candidate collapses status to a single incompatible snapshot.

### Task 8 — Verify supersede and failExtraction (AC-008, AC-009)

In `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/operation/verify/VerifyOperation.kt`: `supersedeParked` currently assigns `skipUnreadable(workflowId) { workflows.list(...).workflows } ?: return`, so one unreadable list throws away the pass. After Task 6, remove that outer wrapper and keep the per-row `skipUnreadable(row.workflowId) { ... }`. On `VerifyWrite.Rejected` from `store.write(...)` inside that loop, record a one-line diagnostic with workflow id and continue. Supersede stays best effort.

`failExtraction(workflowId, reason)` calls `store.write(...)` and always returns `OperationOutcome.Failed("Verify criteria extraction failed: $reason\nVerify workflow: $workflowId")`. When that write is `VerifyWrite.Rejected`, include the rejection in the Failed message.

test_obligations: `VerifyOperationTest` — unreadable sibling verify row (`contract_version` outside the readable set) beside an older parked same-repo row; assert the older row ends abandoned with `superseded_by` equal to the new workflow id. Realistic bug: outer `skipUnreadable` on the whole list returns early and parked rows stay pending (incident 2026-10-09). No extra test for the failExtraction message beyond exercising the Rejected branch if the existing test seam already writes FAILED; if it does not, fold the rejection text into this same incident test rather than adding a sibling.

### Task 9 — Constraints for later phases

Implement writes production code, tests, and `census_subtask_1.md`. Audit reads the census and the tree against each AC. Validate runs `./gradlew check` (spotless, detekt, architecture repo tests, render snapshots, agent-config validation, new unit tests). If spotless reports a stale configuration cache, validate reruns with `--no-configuration-cache`. This subtask does not edit rendered skills.

### Task 10 — Tests to add (AC-009, AC-010, AC-011)

Add only the tests named in Tasks 4–8. Empty test_obligations on Tasks 1–3 are intentional. Do not add a FINE-log test. Do not add an IDE test unless Task 7’s wrap landed. Do not run the tests in implement.

| Test | Bug it catches |
| --- | --- |
| `WorkflowServiceTest` list + unreadable sibling | `list` still `rows.map`s validation and drops the whole result |
| `WorkflowServiceTest` latest + unreadable newest | `latest` fails on the newest row instead of walking to the next readable |
| `WorkListServiceTest` drops unreadable TASK_RUNTIME or VERIFY | one stale snapshot empties the work list |
| `McpVerifyWorkflowToolsTest` readable + unreadable list | MCP list inherits the whole-list throw |
| `VerifyOperationTest` unreadable sibling + parked same-repo row | supersede pass cancelled; parked row stays pending |
| sqlite store: older readable version → terminal write stamps current | bind keeps the stale version so abandon fails schema |
| migration test, only if version 51 exists | out-of-set row not terminalized, current row mutated, or second apply is not a no-op |
| IDE projector test, only if wrap landed | one unreadable candidate collapses the other candidates |

## Acceptance Criteria

1. `WorkflowStateContractVersions.kt` declares `WORKFLOW_STATE_READABLE_CONTRACT_VERSIONS`, containing `WORKFLOW_STATE_CONTRACT_VERSION` (still `"0.3"`) and each pre-current version that `census_subtask_1.md` lists as readable.
2. `WorkflowStateSchemaValidator` validates a row whose `contract_version` is in the readable set as the current version, rejects other versions with the existing invalid-workflow-state error, and logs no WARNING before throwing.
3. `bindWorkflowRow` and `bindFeatureTaskWorkflowRow` persist `WORKFLOW_STATE_CONTRACT_VERSION` when the stored version is an older readable workflow-state version.
4. `census_subtask_1.md` lists every pre-current workflow-state contract version with its path and evidence. Every version listed as terminalized is handled by a new named, idempotent migration in `DatabaseMigrationEntries.kt` that marks those non-terminal rows abandoned and terminal.
5. `WorkflowService.list` skips rows that fail with an invalid-workflow-state error, returns the other rows with `workflowCount` equal to the number returned, and records one diagnostic line per skipped row. `WorkflowService.latest` returns the newest readable row.
6. `WorkListService` drops an unreadable task-runtime or verify row and returns the remaining work items.
7. `IdeStatusProjector` cannot lose the other candidates because one candidate's workflow-state row is unreadable. An unreadable candidate is reported through `incompatible(...)`, or `census_subtask_1.md` records why the loop could not propagate the failure.
8. `VerifyOperation.supersedeParked` keeps processing the other rows when one row is unreadable, and records a diagnostic when a supersede write is rejected. `failExtraction` includes a rejected FAILED write in its failure message.
9. `VerifyOperationTest` has a test where an unreadable sibling verify row (contract_version outside the readable set) sits beside an older parked same-repo row. It asserts the older row ends abandoned with `superseded_by` set to the new workflow id.
10. Tests assert that `WorkflowService.list` and `latest` (`WorkflowServiceTest`), `WorkListService` list (`WorkListServiceTest`) and MCP `feature_verify_workflow_list` (`McpVerifyWorkflowToolsTest`) each return the readable rows when an unreadable old-contract_version row is present.
11. A sqlite store test asserts that a row stored with an older readable contract_version can be read and written to a terminal status, and that the write persists `WORKFLOW_STATE_CONTRACT_VERSION`. If a migration was added, a migration test asserts it terminalizes an out-of-set non-terminal row, leaves current rows untouched, and is idempotent.

## Test Obligations

- Incident regression (AC 9): one unreadable sibling must not stop the supersede pass.
- Whole-list loss (AC 10): one test per caller the parent spec names. Each catches a regression to `rows.map` validation in that caller.
- Upgrade path (AC 11): catches a write that keeps the stale version, which would leave the row unabandonable.
- The migration test exists only if the migration does. It covers persistence integrity and idempotence on re-run.
- No test for the FINE log level or for the IDE projector unless the loop needed wrapping. If it did, add one test asserting another candidate still projects.

## Non-Goals

- No bump of `WORKFLOW_STATE_CONTRACT_VERSION` and no schema shape change.
- No new field in CLI or MCP list payloads.
- Explicit single-row `get`, `resume` and `continue` keep failing loudly on an unreadable row.
- No logging configuration change. That belongs to subtask 2.

## Dependency Notes

Independent of subtasks 2-4. Apply the rules to the tree as found. This subtask lowers the validator's own drift log, which no other subtask edits.

## Validation Strategy

The validate phase runs `./gradlew check`, covering the new and updated tests, schema-identity pins (`WorkflowStateSchemaContractVersionTest`, `WorkflowStateStoreTest`, `FeatureTaskRuntimePhaseWorkflowDefinitionTest`), migration tests and architecture repo tests. Implement and audit run nothing. Audit reads the tree against each criterion and checks `census_subtask_1.md`.

## Next Path

```bash
skill-bill goal SKILL-414
```
