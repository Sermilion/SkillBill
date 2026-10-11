# Subtask 1. Governed work board and detail CLI

## Scope

Land the two read-only JSON commands and everything they need to emit schema-valid payloads: YAML contracts, Kotlin versions, payload keys, failure codes, classpath locators, validators, engine query paths, CLI wiring on the existing `work` group, and the tests named under test obligations.

Add `WorkBoardCommand` and `WorkDetailCommand` next to `WorkListCommand` and `WorkStatusCommand` in `WorkTopLevelCommands` (`../../runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/work/WorkCliCommands.kt`), already wired through `CliCommandGroups.kt` as `CliMiscCommands(workCommands.command)`. Command names: `board` and `detail`.

Board is machine-wide, like `work list`. It has no `--repo-root`. Options: `--format json` only (copy `WorkStatusCommand`'s format rule; implement confirms the exact reject path) and `--finished-within` with values `6h`, `24h`, `7d`, default `24h`.

Detail takes one positional id. Match `workflowId` on work/goal rows, or `invocationId` on standalone phase records.

Reuse projections. Do not invent a second status formula.

## Implementation Details

This plan covers subtask 1 only and uses the upstream preplan digest as its repository evidence. AC-001 through AC-009 refer to the numbered acceptance criteria below. The tasks are ordered by implementation dependency. Subtask 2 receives these contracts after this subtask lands; this phase neither plans nor changes its application work.

### Settled contract and query decisions

- Board is a version `"1.0"` envelope with `contract_version`, `items`, `observed_at`, `finished_within`, and required `diagnostics`. Expected database absence adds the existing `absent_database` problem representation and returns an empty items array. A healthy empty board has no problem.
- Each board item wraps the existing wire map under `snapshot`, alongside `repository_root`, nullable `finished_at`, and nullable `finished_at_source`. The nested snapshot retains contract version `"0.3"` and its existing serialized shape. In particular, its execution fields remain `execution_scope`, `execution_id`, `status_store_id`, `run_sequence`, `status_revision`, `invocation_id`, and `phase_id`. There is no new `execution.scope` object and no overwrite of the snapshot version.
- Detail is a version `"1.0"` envelope with `contract_version`, `observed_at`, and nullable `item`. A found item contains the same snapshot/root/completion wrapper as board plus the detail extras below. An expected miss has `item: null` and a structured problem. Use `absent_database` for a missing database, `unknown_work_item` for no exact match, `ambiguous_work_item` for distinct records sharing the requested id, and `invalid_repository_identity` when an exact match has no usable root. These new problem names are planning decisions. Implement must confirm that their representation fits the existing work-status problem convention. Expected absence is a schema-valid result with exit code zero; malformed contracts remain typed failures.
- Board diagnostics identify the affected workflow or invocation when available, the nullable repository identity, and a stable reason. Use `missing_repository_identity`, `invalid_repository_identity`, or `missing_completion_time`. Preserve existing observability behavior and emit a diagnostic for each omitted candidate rather than silently ignoring it.
- Retention uses completion evidence, never `authoritativeUpdatedAt`, freshness timestamps, or heartbeats. Include terminal items whose completion instant is between `observed_at - window` and `observed_at`, both boundaries inclusive. Unfinished projected work is included regardless of age. Determine terminal status after projection so a parked parent with a completed child can remain an active goal on `monitor`.
- Standalone completion uses `finishedAt`, with source `standalone_finished_at`. Workflow completion uses its recorded terminal-completion evidence, with source `workflow_completion`. A terminal goal uses recorded final-completion evidence first, with source `goal_completion`; only when that is unavailable may its state-entry time supply the fallback. Mark that fallback `goal_state_entry` or `estimated_goal_state_entry` according to `stateEnteredAtEstimated`. This fallback never applies to active, paused, or blocked goals. Terminal items with no usable completion time are omitted from board with `missing_completion_time`; exact detail may still return them with null completion metadata. The digest does not name the workflow/final-goal completion accessor, so implement must locate the owning evidence rather than substitute a freshness anchor. This is an explicit implementation assumption, not a proven accessor.
- Correlate durable identities before grouping and goal-child exclusion. Issue-key goal sets are local to each repository identity. Deduplicate an associated standalone status row and workflow row within that identity using the existing execution correlation; preserve the standalone invocation data needed for exact detail lookup.
- Decode only `FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX` followed by an absolute path. Do not inspect the filesystem to manufacture a path, resolve a process checkout as a fallback, or use the home directory. Use the stored decoded root to construct projection context. Missing, malformed, or non-absolute identities fail the item correlation, not the whole board.
- Detail searches exact workflow ids and standalone invocation ids independently of board retention. An associated workflow and standalone record representing the same execution are one match. Multiple unrelated exact matches produce `ambiguous_work_item` rather than selecting a winner.

### 1. Define the governed contracts and owning vocabulary

Serves AC-001, AC-002, AC-004, AC-007, AC-008, and AC-009.

Author Draft 2020-12 schemas at `orchestration/contracts/work-board-schema.yaml` and `orchestration/contracts/work-detail-schema.yaml`. Pin their envelope versions to `"1.0"` and their ids to `https://skill-bill.dev/contracts/work-board-schema.yaml` and `https://skill-bill.dev/contracts/work-detail-schema.yaml`. Specify the settled nesting, completion-source enum, retention enum, diagnostic shape, and mutually consistent found/problem detail outcomes. Validate nested snapshots against the existing ide-status schema without changing its definition. Ensure any schema reference works from both repository and packaged resources.

Place `WORK_BOARD_CONTRACT_VERSION`, `WORK_DETAIL_CONTRACT_VERSION`, `WorkBoardPayloadKeys`, and `WorkDetailPayloadKeys` beside `IdeStatusContractVersions.kt` under `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/identity/status/`. Reuse existing constants from `IdeStatusPayloadKeys`, `SharedPayloadKeys`, decomposition, validation, and worktree vocabulary. Declare a new shared item key only once and have the other contract reference its owner. Do not redeclare the snapshot's 0.3 property strings. Keep enum wire values on their owning enums.

Add locators beside `IdeStatusSchemaPaths` in `runtime-kotlin/runtime-infra/contracts/src/main/kotlin/skillbill/infrastructure/contracts/locator/WorkflowSchemaPaths.kt`. Follow the digest's actual locator members `REPO_RELATIVE_PATH`, `CLASSPATH_RESOURCE`, and `EXPECTED_SCHEMA_ID`; these provide the required SOURCE and CLASSPATH semantics. Register both files for resource copying in `runtime-kotlin/runtime-infra/contracts/build.gradle.kts`.

Add `WorkFailureCode` in `skillbill.error.shellcontent` with `INVALID_WORK_BOARD_SCHEMA` and `INVALID_WORK_DETAIL_SCHEMA`. Define typed requests/results and validator ports in their owning ports/model areas, keeping schema adapters out of engine and CLI. Use `WorkBoardRequest` with a constrained retention window and optional observation instant, plus `WorkBoardResult`; define corresponding detail request/result types. Completion metadata belongs to the work wrapper, never `IdeStatusSnapshot` or `WorkItem`.

Tests to add for validation later are board/detail parity cases beside `IdeStatusSchemaContractVersionTest` in `runtime-infra/contracts/src/test/kotlin/skillbill/infrastructure/contracts/workflow/goal`. The realistic failure is a deployed Kotlin version or locator id disagreeing with the emitted schema, or an envelope losing a required field. Governed parity coverage is mandatory.

### 2. Implement schema adapters and production bindings

Serves AC-001, AC-002, AC-003, and AC-009.

Implement board/detail validators in `runtime-infra/contracts` using `ClasspathContractSchemaLoader.compiledSchema`, `CompiledSchemaRequest`, `valueToTree`, and schema validation. Give each schema its own cache key and enforce the expected contract version and schema id. Schema load, parse, and emission defects must throw `SkillBillRuntimeException` carrying the owning work failure code. Preserve cancellation propagation; do not wrap cancellation as a schema defect or swallow load failures.

Bind validator ports through `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/workflow/RuntimeWorkflowValidatorProvides.kt` and the needed existing DI providers. Reuse `IdeStatusService.toWireMap` or the existing validator wire-payload conversion for nested snapshots. Validate each nested snapshot and the complete outer result before CLI output. Do not modify existing ide-status validators or golden fixtures to accept board/detail fields.

The digest establishes that `WireVocabularyGovernedSeamInventory` currently registers neither work status nor ide-status. AC-002's conditional registration is therefore not triggered. New seams still use owning keys. If implementation adds an enforceable inventory entry, it must cover the new board/detail seams without expanding baselines, exemptions, or tolerated violations.

Add new rejection cases beside `IdeStatusSchemaValidatorTest`, leaving that existing test and its 0.3 fixtures unchanged. One strong rejection case per new contract must assert the exact work failure code for a malformed outer payload or invalid nested snapshot. Exercise packaged loading as part of governed parity/validator coverage. The bug is accepting a structurally incompatible CLI response or reporting an unrelated install failure. Avoid separate tests for trivial DI forwarding.

### 3. Expose the required standalone persistence reads

Serves AC-005, AC-006, AC-008, and AC-009.

Extend `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/idestatus/StandalonePhaseStatusRepository.kt` with an all-identities read of unfinished rows plus rows with authoritative `finishedAt` in the inclusive retention interval. Implement it in `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/workflow/featuretask/StandalonePhaseStatusStore.kt`. Make the temporal bounds explicit in the port and use the same observation instant as board projection. Keep `readEligible` and its repository/branch semantics unchanged.

Add or reuse an exact invocation-id read for detail that does not apply a retention cutoff. The digest establishes a unique `invocation_id` and the needed columns in `standalone_phase_status`; this read alone requires no migration. Preserve decimal-string sequence/revision values and read-only lease projection. Reuse `projectStandaloneLease` so ordinary expired active phases become paused with `runner_interrupted` and monitor keeps its existing special handling. Do not adopt a new monitor lifecycle rule from the older notes.

Extend `StandalonePhaseStatusStoreTest.kt` with one boundary fixture containing live rows from multiple identities/branches, an old unfinished row, terminal rows at both boundaries and outside them, and a finished old invocation accessible by exact id. Assert the returned records, not SQL text or adapter call order. This catches a board query accidentally using `readEligible`, updated-at retention, or a retention-limited detail lookup.

### 4. Add the machine-wide board query without changing status

Serves AC-003, AC-004, AC-005, AC-006, AC-007, and AC-009.

Add `board(request: WorkBoardRequest): WorkBoardResult` to `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusService.kt`. Keep `status(IdeStatusRequest)` and `toWireMap(IdeStatusSnapshot)` compatible. Read all work through the existing `WorkListRepository.list` and the new standalone read inside one `DatabaseSessionFactory` read snapshot. Preserve `WorkListRepository`, `SQLiteWorkListRepository`, and `WorkItem` semantics; add a narrow read only if owning completion evidence cannot otherwise be obtained in the same unit of work.

Use `IdeStatusRepositoryCorrelation.kt`'s durable sources: feature-task execution identity, goal control state's repository identity, and workflow execution registration. Verify rows may use durable same-issue correlation only when it identifies a repository unambiguously; do not apply single-repository fallback to the machine-wide query. Group by decoded identity, build per-group goal issue-key sets, and preserve execution deduplication.

Share or extract only the needed candidate helpers: `collectCandidates`, `toCandidate`, `projectStandaloneLease`, `standaloneLifecycle`, and `isExcludedGoalChild`. A shared collector must accept the board's supplied rows so the board path never falls back to `readEligible`. Keep lifecycle formulas in these helpers and `IdeStatusProjector.project`. If the existing private helper signatures cannot express global collection, adjust the reusable boundary without copying lifecycle logic or changing single-repository status results.

Construct `IdeStatusProjectionContext` with the same unit of work, each candidate's identity/root and durable branch correlation, and one observation instant. Do not call `CheckedOutBranchSource` to filter candidates, `IdeStatusSelectionPolicy`, or `status()`. Project every eligible candidate, validate every snapshot with `ideStatusValidator.validate(snapshot, sourceLabel)`, then apply the completion policy and build the wrapper. Preserve finalization on monitor, pause semantics, blocked quality gates, freshness, and dead-runner duration-anchor behavior.

Check `database.databaseExists()` through the existing boundary and emit the established `absent_database` problem without creating a database. Report bad identities through the diagnostic path. Optional detail context that is unavailable remains absent; corrupt governed data remains a typed failure.

Use `CliWorkBoardTest` for the primary behavioral coverage in task 6. Its multi-repository fixture also includes equal issue keys and non-checkout branches, catching cross-repository child suppression and accidental branch filtering. Its temporal fixture catches stale heartbeats extending terminal retention and labels an estimated terminal-goal fallback. Preserve existing `IdeStatusServiceGoalProjectionTest`, `IdeStatusServiceBranchScopingTest`, freshness/timestamp coverage, and `IdeStatusReadSnapshotConcurrencyTest`. If the new collector introduces a distinct consistency path, extend the concurrency regression with the realistic interleaved-writer bug; do not duplicate its existing assertion solely to name the new method.

### 5. Add exact-id detail projection and governed extras

Serves AC-002, AC-003, AC-007, AC-008, and AC-009.

Implement the detail query in the engine work area beside `IdeStatusService`, sharing exact-id correlation, repository decoding, candidate conversion, snapshot validation, and wrapper mapping with board. Keep each read/projection within its owned read snapshot; do not independently reopen a database per field or resolve detail by searching board output. Preserve existing status-service resource ownership when consuming goal/runtime projections rather than inventing a new persistence access path.

For goal manifests call the digest's actual `GoalRunnerManifestStore.loadDurableByIssueKey(issueKey, repoRoot)`, not the older `readByIssueKey` name. Require the loaded manifest state's parent workflow to match the requested goal workflow before attaching extras. Title comes from `DecompositionManifest.featureName`. Map `DecompositionSubtask.id`, `name`, `status`, `commitSha`, `blockedReason`, and `dependencies`. Dependencies retain integer `subtaskId` plus boolean `optional` and `skipped`, using existing decomposition keys where owned. Do not return another historical run's title or subtasks merely because its issue key matches.

Use `GoalRunnerStatusService.status(GoalRunnerStatusRequest(...))` with repository path and diff-stat/selected-hunk options off. Attach `active_agent`, `execution_liveness`, `phase_attempt_counts`, `cumulative_fix_iterations`, `re_attempt_cause_counts`, `findings_in_scope`, `supervisor_kill_count`, `latest_observability_event`, `latest_worktree_edit`, and `completed_subtask_validation`. Reuse governed representations and `GoalRunnerSubtaskValidationEvidence.toStatusWire()`. Do not invent ledger values when the projection is unavailable. Preserve empty maps when the projection supplies them; optional panels may be omitted when no projection exists.

For the current child workflow, consume `FeatureTaskRuntimeStatusService.status(FeatureTaskRuntimeStatusRequest(workflowId))`. Emit ordered phase records with phase id, status, attempt count, resolved agent, finished flag, execution origin, continuation kind, launched model, and effort. Include its current phase and bounded execution counts. Do not decode phase artifacts again. Derive the goal current-subtask `step_pipeline` from those ordered phases and provide separate goal-finish steps `pr` and `monitor` so they do not misrepresent the child pipeline.

For standalone records, emit `step_pipeline` even without a workflow id: pr uses `commit_push`, `pr`, `monitor`; monitor uses `commit_push`, `monitor`; plan uses `preplan`, `plan`; review and validation each use their single step. Use owning enum wire values where available. Standalone detail never has a stop field or synthetic goal extras.

New detail names `feature_title`, `subtasks`, `phases`, `step_pipeline`, and `goal_finish_pipeline` are settled planning choices for the wrapper. Reuse existing owners for nested projection keys. The digest does not supply every nested event/agent/phase wire name; implement must confirm those mappings against the existing governed serializers and design v2 table t3, retaining the chosen envelope and snapshot separation. This is a bounded mapping assumption, not permission to change ide-status 0.3.

Add `CliWorkDetailTest` outcome fixtures for an old standalone invocation without workflow id, the specified standalone pipeline branches, and a goal with manifest dependencies, phase data, complete ledger extras, liveness/agent data, observability, worktree edit, and governed validation evidence. Include a wrong historical parent for the same issue key and assert its extras do not leak into the requested run. Exercise an unknown exact id as a structured problem. These catch missed standalone work, truncated tray data, and cross-run data leakage. Assert schema-valid payload values rather than service interactions.

### 6. Wire CLI commands and finish the boundary regression coverage

Serves AC-001 through AC-009.

Add `WorkBoardCommand` and `WorkDetailCommand` beside the existing commands under `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/work/`, and register them in `WorkTopLevelCommands` in `WorkCliCommands.kt`. Use the existing command-group wiring rather than creating a second work group. Production DI changes required to construct the new query/validator dependencies are part of this task.

Board accepts only the existing JSON format convention and `--finished-within` values `6h`, `24h`, `7d`, default `24h`; it has no `--repo-root`. Detail requires exactly one positional id and accepts `--format json`. Follow `WorkStatusCommand`'s rejection behavior for unsupported format. Map typed expected problems to their schema-valid envelopes and typed defects through established CLI error handling without stack traces in JSON output. Validate before writing output.

Create `CliWorkBoardTest` and `CliWorkDetailTest` in `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli` using `CliWorkStatusTest`'s harness: `CliRuntime.run`, isolated database/home fixtures, `CliRuntimeContext`, `decodeJsonObject`, schema validation, and exit-code assertions. Board tests cover all specified windows/default selection, rejection of unsupported options, multi-repository/non-checkout-branch inclusion, same-issue isolation, missing database, malformed/missing identity diagnostics, and retention boundaries. Combine related boundary assertions in fixtures rather than adding sibling cases with only different literals. Detail tests cover exact invocation lookup outside retention, pipelines, complete goal extras, safe historical-manifest correlation, and expected missing results. Never mirror private helper structure.

The test obligations below remain mandatory. Do not add unrelated list-command tests or new 0.3 golden fixtures. Existing `CliWorkListRuntimeTest`, `WorkListServiceTest`, ide-status golden/validator tests, service projection regressions, and read-snapshot concurrency coverage remain the compatibility net. No test is needed solely for constructor wiring, map forwarding, or naming a new class.

### Constraints and phase ownership

Implement produces contracts, persistence reads, query projections, command wiring, and the named test sources. Audit inspects every acceptance criterion against those end states. Validate owns execution of the new tests, existing regressions, architecture guards, and the pack's full repository gate. This plan phase executes no tests, compilation, builds, or validation commands. Build proof, if required by the runtime, belongs to its build phase. Review and validate may repair required production wiring, test setup, formatting, or lint while preserving behavior, assertions, and architecture rules.

Apply the architecture/code requirements supplied by the digest: inward dependencies, ports owned by the consuming boundary, concrete schema/SQLite adapters in infrastructure, DI construction in runtime-core, typed owner failures, shared schema loading, and owning wire keys. Keep production files bounded by extracting cohesive helpers only when needed. Do not introduce forbidden Kotlin comments or inline FQNs, duplicate lifecycle formulas, or loosen guards/baselines/exemptions. Later runtime review applies the architecture checklist and cites its rule ids. Any unavailable extra is handled through the declared optional/result representation; fallback or degradation is visible through existing observability and board diagnostics.

Do not change `IdeStatusSnapshot`, work list/status behavior, existing 0.3 fixtures, plugin trees, or desktop code. Required production/resource bindings and high-value test setup remain within the CLI/contracts scope. No skill-source or install-sync work is planned. Branch preparation, commits, history, PR work, and the independently shippable desktop subtask remain with their owning phases or parent runtime. No acceptance criterion is marked proven by this plan.

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
