# SKILL-414 Subtask 1 - Stale workflow-state rows must not break the runtime

Parent spec: `.feature-specs/SKILL-414-stabilization-pass-stale-workflow-state-quiet-output-consistent-terminology-review-quality-measurement/spec.md` (Area 1).

## Scope

Pre-current workflow-state rows get an upgrade path, plus a terminalize path for any version the current schema cannot read. Every caller that loops over workflow-state rows tolerates one unreadable row. Ignored verify write results are checked.

Paths below are under `runtime-kotlin/` unless marked repo-relative.

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
