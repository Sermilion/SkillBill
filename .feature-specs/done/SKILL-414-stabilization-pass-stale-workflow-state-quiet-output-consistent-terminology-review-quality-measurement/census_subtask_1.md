# SKILL-414 Subtask 1 census — pre-current workflow-state versions

Schema: `../../../orchestration/contracts/workflow-state-schema.yaml`
(`WorkflowStateSchemaPaths.REPO_RELATIVE_PATH`; classpath
`skillbill/infrastructure/contracts/workflow-state-schema.yaml`; schema id
`https://skill-bill.dev/contracts/workflow-state-schema.yaml`). Current pin:
`properties.contract_version.const` `"0.3"`, Kotlin
`WORKFLOW_STATE_CONTRACT_VERSION` `"0.3"`. Identity tests stay
`WorkflowStateSchemaContractVersionTest`.

## Pre-current versions

| Version | Path | Evidence |
| --- | --- | --- |
| `0.1` | readable | YAML `contract_version.const` was `"0.1"` from the first schema commit (`011a7f557`) through `23499384b`. It jumped to `"0.3"` in `acab2c899` (SKILL-202). After the version string is normalized to `"0.3"`, a current-shape `0.1` row validates; the named YAML difference is `properties.contract_version.const`. `WorkflowStateStoreTest` already stores `contractVersion = "0.1"` on verify rows. |
| `0.2` | readable | Never published as `workflow-state-schema.yaml` `const`. Included so a row stamped `0.2` between the `0.1` pin and the `0.3` pin still normalizes. Historical SQLite `CHECK (contract_version = '0.1')` then `'0.2'` appear on sibling tables (goal-planning preparations, execution identities), not on `feature_verify_workflows` / `feature_task_workflows`. Current-shape `0.2` rows validate after version-string normalization. |

No version is terminalized. Last `DatabaseMigrationEntries.kt` version remains `50`
(`add-standalone-phase-status-authority`). No migration 51.

Shape churn under the long-lived `0.1` pin (`workflow_name` `bill-feature-implement`
→ `bill-feature-task`, `mode` prose then runtime-only) is not a distinct contract
version. Those old-shape rows still fail current schema after version-string
normalization and are skipped per row.

## `feature_task_workflows.contract_version`

- `mode=runtime` phase-workflow rows: `FeatureTaskRuntimePhaseWorkflowGraph` sets
  `contractVersion = WORKFLOW_STATE_CONTRACT_VERSION`. The workflow-state
  validator checks these rows. Re-stamp applies.
- `mode=prose` (or null) rows: `FeatureTaskWorkflowMode.defaultContractVersion`
  uses `FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION` (`"0.1"`), a different
  contract family. The workflow-state schema does not validate prose rows.
  Re-stamp and any future terminalize leave that value unchanged.

`feature_verify_workflows.contract_version` is always the workflow-state family
(`FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION` = `"0.3"`).

## IDE projector wrap

`collectCandidates` does not validate workflow-state snapshots. `toCandidate`
reads work-list rows, identities, and liveness anchors; it does not call
`WorkflowSnapshotValidator`. `IdeStatusProjector.project` is one selected
candidate, not a loop. `projectWorkflowFamily` (VERIFY) validates;
`projectRuntime` (TASK_RUNTIME) does not.

Wrap that landed:

1. Before `IdeStatusSelectionPolicy.select`, `readableForSelection` drops
   FEATURE_VERIFY candidates whose snapshot fails
   `isInvalidWorkflowStateFailure()`, so a readable sibling can still be
   selected.
2. `projectWorkflowFamily` catches the same failure and returns
   `incompatible(candidate, context, reason)` instead of throwing out of
   `database.read`.
3. If every VERIFY candidate is unreadable, select falls back to the full
   candidate list so a sole unreadable VERIFY still projects as incompatible
   rather than `no_matching_work`.

The surrounding `database.read` catch that maps
`isInvalidWorkflowStateFailure()` to one `incompatibleRecord` remains for
collection failures such as an orphaned identity read.
