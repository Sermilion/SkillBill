# Subtask 1. Governed work board and detail CLI

## Scope

Land the two read-only JSON commands and everything they need to emit schema-valid payloads: YAML contracts, Kotlin versions, payload keys, failure codes, classpath locators, validators, engine query paths, CLI wiring on the existing `work` group, and the tests named under test obligations.

Add `WorkBoardCommand` and `WorkDetailCommand` next to `WorkListCommand` and `WorkStatusCommand` in `WorkTopLevelCommands` (`runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/work/WorkCliCommands.kt`), already wired through `CliCommandGroups.kt` as `CliMiscCommands(workCommands.command)`. Command names: `board` and `detail`.

Board is machine-wide, like `work list`. It has no `--repo-root`. Options: `--format json` only (copy `WorkStatusCommand`'s format rule; implement confirms the exact reject path) and `--finished-within` with values `6h`, `24h`, `7d`, default `24h`.

Detail takes one positional id. Match `workflowId` on work/goal rows, or `invocationId` on standalone phase records.

Reuse projections. Do not invent a second status formula.

## Implementation details

### Contracts

- Author `orchestration/contracts/work-board-schema.yaml` and `orchestration/contracts/work-detail-schema.yaml`.
- Copy them onto the classpath the same way `ide-status-schema.yaml` is copied.
- Add locators beside `IdeStatusSchemaPaths` in `WorkflowSchemaPaths.kt`: SOURCE `orchestration/contracts/<file>.yaml`, CLASSPATH `skillbill/infrastructure/contracts/<file>.yaml`, EXPECTED_SCHEMA_ID `https://skill-bill.dev/contracts/<file>.yaml`.
- Kotlin versions beside `IdeStatusContractVersions`: `WORK_BOARD_CONTRACT_VERSION` and `WORK_DETAIL_CONTRACT_VERSION`, both `"1.0"`. Each payload pins `contract_version` to that const.
- `WorkBoardPayloadKeys` and `WorkDetailPayloadKeys` own keys that are not already on `IdeStatusPayloadKeys` or `SharedPayloadKeys`. Nested snapshot objects reuse `IdeStatusSchemaValidator.toWirePayload` / `IdeStatusService.toWireMap` and must not restate 0.3 property names.
- `WorkFailureCode` in `skillbill.error.shellcontent` with `INVALID_WORK_BOARD_SCHEMA` and `INVALID_WORK_DETAIL_SCHEMA`. Throw `SkillBillRuntimeException` at every parse/emit seam, matching `IdeStatusSchemaValidator.validate`.
- Parity tests copied from the ide-status pattern: const matches Kotlin version, `$id` matches locator, required new properties present. Golden/validator tests for the new schemas only. Do not mutate `IdeStatusGoldenFixturesTest`, `IdeStatusSchemaValidatorTest`, or 0.3 fixtures to carry board fields.
- Treat new CLI maps as governed wire seams. If `WireVocabularyGovernedSeamInventory` lists work status, register board and detail the same way.

### Board envelope and items

Board JSON:

- `contract_version`
- `items` array
- `observed_at`
- the finished-within window actually used (wire key on `WorkBoardPayloadKeys`; implement names it to match schema, recommended `finished_within`)
- `diagnostics` array, empty when every identity decoded. Each diagnostic names the skipped identity and the reason. Required so a skipped row is visible without failing the poll.

Each item is one ide-status 0.3 snapshot wire object plus `repository_root` (decoded absolute path) plus enough identity for detail (`workflow_id` and/or `invocation_id` already on the snapshot/execution). Do not add those identity fields onto `IdeStatusSnapshot`; wrap at the board item layer.

Decode `repository_root` from `FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX` (`repo-root-realpath-v1:`) plus the absolute real Git top-level path (must start with `/`). `GoalRunnerStatusService.status` and the projector still need a `Path`: `Path.of(identity.removePrefix(REPOSITORY_IDENTITY_PREFIX))`. Design section 3: row name is basename of that path. Work list rows have no repo path; this decode is the join. Never invent a path.

`WorkListService.list` is already machine-global. Board uses the same `DatabaseSessionFactory`. If `!database.databaseExists()`, emit the same `absent_database` problem the status command already emits.

### IdeStatusService board path

`IdeStatusService.status(IdeStatusRequest)` is the wrong entry for board: it requires one repo root, scopes standalone rows to the process checkout branch, and `IdeStatusSelectionPolicy.select` picks a single snapshot.

Add a board method on this same service (same package, so it can call internal projector APIs) that projects every live candidate plus finished-within-window rows, across repository identities, without `SelectionPolicy` and without the current-branch filter. Reuse `collectCandidates`, `toCandidate`, `projectStandaloneLease`, `standaloneLifecycle`, `isExcludedGoalChild`. Keep `ideStatusValidator.validate(snapshot, sourceLabel)`.

`IdeStatusProjector.project` already calls `GoalRunnerStatusService.status` for `FEATURE_GOAL` and `FeatureTaskRuntimeStatusService.status` for runtime children. Board and detail keep using those calls through the projector.

Do not add fields to `IdeStatusSnapshot` for 0.3.

### Standalone phases

`StandalonePhaseStatusRepository.readEligible(repositoryIdentity, branchCorrelation, now)` is per repo and branch. Add a new read on the port and on `StandalonePhaseStatusStore` that returns live rows plus rows whose `finishedAt` is within the retention window, all identities. Record fields already include `invocationId`, `phaseId`, `workflowId`, `branchCorrelation`, `currentStep`, `currentActivity`, `finishedAt`, `terminalResult`, `lifecycleState`. Grow `StandalonePhaseStatusStoreTest.kt`. Keep monitor lease expiry copy in `projectStandaloneLease` (`phaseId == "monitor"` and expired lease becomes paused / `currentStep` `runner_interrupted`).

### Detail extras

Detail JSON is the same snapshot plus `repository_root`, plus design section 3 fields tagged new. Wire names: prefer table t3; the list below is the digest mapping. Nested objects reuse ide-status keys.

- Feature title from `GoalRunnerManifestStore.readByIssueKey(issueKey, repoRoot)` → `DecompositionManifest.featureName`.
- Subtask list from `DecompositionManifest.subtasks` (`DecompositionSubtask`): wire `id`, `name`, `status` (`pending`, `in_progress`, `complete`, `completed_no_change`, `skipped`, `blocked`), `commit_sha`, `blocked_reason`, `dependencies`. Implement confirms Kotlin member names on `DecompositionSubtask` and maps them to those wire fields. Do not guess extra members.
- Phase pipeline chips for the current subtask: `FeatureTaskRuntimeStatusService.status(FeatureTaskRuntimeStatusRequest(workflowId = currentChildWorkflowId)).phases` plus `currentPhaseId` / `completeCount` / `currentPhaseExecution` already used by `IdeStatusProjector.childOptionalContext`. Goal-finish chips `pr` and `monitor` belong on goal detail.
- `step_pipeline` on the detail payload so Compose does not hardcode it: `pr` = `commit_push`, `pr`, `monitor`; `monitor` = `commit_push`, `monitor`; `plan` = `preplan`, `plan`; `review` and `validation` are single-step. Include this for standalone phase detail and for goal current-subtask chips as the design table requires.
- From `GoalRunnerStatusService.status`: `activeAgent`, `executionLiveness` (`live`, `idle`, `unknown`), `latestObservabilityEvent` (`activitySummary`, timestamp, workflowPhase, subtaskId), `supervisorKillCount`, `phaseAttemptCounts`, `cumulativeFixIterations`, `reAttemptCauseCounts`, `findingsInScope`, `completedSubtaskValidation`, `latestWorktreeEdit` (`WorktreeEditSummary`: `recordedAt`, `phaseId`, `pathSample`, `netInsertions`, `netDeletions`). Ledger maps appear on the new schema even when empty enough for the UI to hide; omitting them when the projection is null is allowed. Do not invent a stop control on standalone phase detail.

CLI tests use the same harness as `CliWorkStatusTest.kt`: `CliRuntime.run(listOf("--db", ..., "work", "board"|"detail", ...), CliRuntimeContext)`. Assert schema validate, no stack traces. Do not change `WorkListCommand` or `WorkStatusCommand` behavior. `CliWorkListRuntimeTest` and `WorkListServiceTest` stay for list.

### Runtime coding rules

No `//` or block comments in runtime Kotlin. KDoc only on interfaces. Wire keys only via Keys objects. Loud-fail typed `SkillBillRuntimeException`. Follow `AGENTS.md` Runtime Contract Schemas, `docs/architecture-guidelines.md`, `runtime-kotlin/ARCHITECTURE.md`, and `docs/code-principles.md`.

Production edits may include engine, ports, sqlite adapter, contracts, CLI, and the tests named here. Do not edit `intellij-plugin` or VS Code plugin trees. Do not expand this subtask into the desktop app.

## Acceptance Criteria

1. `orchestration/contracts/work-board-schema.yaml` and `orchestration/contracts/work-detail-schema.yaml` exist, are copied onto the classpath like ide-status, and have locators with SOURCE, CLASSPATH, and EXPECTED_SCHEMA_ID matching the ide-status pattern. Kotlin `WORK_BOARD_CONTRACT_VERSION` and `WORK_DETAIL_CONTRACT_VERSION` equal `"1.0"` and match the schemas' `contract_version` / `$id` via parity tests beside the ide-status parity test.
2. `WorkBoardPayloadKeys` and `WorkDetailPayloadKeys` declare every new board/detail wire key not already on `IdeStatusPayloadKeys` or `SharedPayloadKeys`. Nested snapshot emission reuses ide-status wire maps and does not restate 0.3 property name strings. `WorkFailureCode` owns `INVALID_WORK_BOARD_SCHEMA` and `INVALID_WORK_DETAIL_SCHEMA`. Parse and emit seams throw `SkillBillRuntimeException`. If work status is in `WireVocabularyGovernedSeamInventory`, board and detail are registered there too.
3. `WorkTopLevelCommands` exposes `work board` and `work detail` without changing `work list` or `work status` behavior, payloads, or ide-status 0.3 semantics. `IdeStatusSnapshot` and existing 0.3 golden/validator fixtures are not modified to carry board or detail fields.
4. Board has no `--repo-root`, accepts `--format json` and `--finished-within` (`6h` | `24h` | `7d`, default `24h`), and emits an envelope with `contract_version`, `items`, `observed_at`, the window used, and `diagnostics`. Each item includes the ide-status snapshot wire fields plus decoded `repository_root`.
5. `IdeStatusService` has a board query that projects every live candidate plus finished-within-window rows across repository identities, without `IdeStatusSelectionPolicy` and without `status()`'s current-branch filter, reusing the projector helpers listed in Implementation details and validating each snapshot through `ideStatusValidator`.
6. `StandalonePhaseStatusRepository` and `StandalonePhaseStatusStore` expose a read of live rows plus finished-within rows for all identities. Board uses that read, not `readEligible`.
7. A missing or non-absolute `repo-root-realpath-v1:` identity is omitted from `items` and recorded in `diagnostics`. Board does not invent a path. When the database does not exist, board emits the same `absent_database` problem as `work status`.
8. Detail resolves one positional id as `workflowId` or standalone `invocationId` and emits snapshot plus `repository_root` plus feature title from `DecompositionManifest.featureName`, subtasks with id, name, status, commit sha, blocked reason, and dependencies, `step_pipeline` as specified, current-subtask phase records from `FeatureTaskRuntimeStatusService`, and the GoalRunnerStatus ledger / agent / liveness / observability / worktree / completed-validation fields listed in Implementation details. Standalone phase detail has no stop field.
9. Tests listed in test obligations exist and assert those behaviors. `CliWorkBoardTest` and `CliWorkDetailTest` use the `CliWorkStatusTest` harness.

## Non-goals

- Desktop app, tray UI, settings, installers, CI workflows, pause/stop CLI changes.
- Changing `WorkListCommand`, `WorkStatusCommand`, or ide-status contract 0.3.
- Opening SQLite from any new desktop code (no desktop code in this subtask).
- A Resume verb, launch/retry/abandon, or standalone phase stop.
- Re-deriving status beside `IdeStatusProjector`.
- Mutating IntelliJ or VS Code plugins.

## Dependency notes

No earlier subtask. Subtask 2 consumes these JSON contracts and must not begin until this commit lands the commands.

Assumption: `--finished-within` is the flag name. Assumption: `diagnostics` is a required array on the board envelope so skipped identities are visible. Assumption: default window `24h` matches settings default. Implement confirms `DecompositionSubtask` member names when mapping table t3 wire fields.

## Test obligations

1. `CliWorkBoardTest` (or an `IdeStatusService` board test on the same idea): one board JSON includes live items from two repository identities. Bug: copying `status()` would apply selection policy and checkout-branch scoping and hide the second repo.
2. `CliWorkBoardTest`: no database emits `absent_database` the way `work status` does, with schema validation and no stack traces. Bug: board assuming the DB always exists would crash or use a different problem code.
3. Board `--finished-within 6h` omits a terminal item older than 6h and includes one inside the window. Bug: unbounded terminal dump, or ignoring the window.
4. `CliWorkDetailTest`: a standalone `invocationId` returns that phase, including `step_pipeline`. Bug: lookup only by work-list `workflowId` misses standalone phases.
5. `CliWorkDetailTest`: a feature-goal detail includes manifest feature title, subtask wire fields, and GoalRunnerStatus ledger maps (`phase_attempt_counts`, cumulative fix iterations, findings in scope, re-attempt causes, supervisor kill count). Bug: wrapping only the ide-status snapshot drops design section 3 extras.
6. Schema parity tests for board and detail (version const, `$id`, required new properties). Governed; not optional.
7. New schema validator tests: invalid board/detail payloads throw `SkillBillRuntimeException` with `WorkFailureCode.INVALID_WORK_BOARD_SCHEMA` / `INVALID_WORK_DETAIL_SCHEMA`. Bug: silent skip or `InstallFailureCode` reuse.
8. `StandalonePhaseStatusStoreTest` covers the new all-identities live-plus-finished-within read. Bug: reusing `readEligible` would leave only the current checkout's phases.

Do not add sibling list-command tests. Existing list tests remain the regression net. Do not add extra 0.3 golden fixtures.

## Validation strategy

Validate phase runs the pack validation gate, which includes runtime-kotlin checks that execute the tests above. This subtask's implement and audit phases do not run Gradle check, compile, or tests. Audit inspects schemas, Kotlin versions, command wiring, query paths, and the named test sources.

## Next path

```bash
skill-bill goal SKILL-418
```
