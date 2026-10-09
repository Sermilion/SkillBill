# SKILL-409 Subtask 1: Purge all goal-owned state

Parent spec: `spec.md`. Read its
Root-cause findings and Decisions sections first. This subtask implements all of them.

## Scope

Make `skill-bill goal purge <issue-key>` remove every piece of runtime state the goal owns,
preserve `spec.md`, report what it removed, and fail loudly on leftovers.

Owned paths (from preplan; elided segments are `...`):

- `runtime-cli/.../cli/goal/purge/GoalPurgeCommand.kt`, `GoalCliPurgeFormatting.kt`,
  `GoalRunnerPurgePayloadKeys.kt`: output and exit code.
- `runtime-engine/.../goalrunner/status/GoalRunnerStatusService.kt` (`purge`, ~line 63).
- `runtime-engine/.../goalrunner/reset/GoalRunnerPurgeCoordinator.kt`: ordering, directory
  deletion, spec-bundle reset, verification, idempotent empty result.
- `runtime-engine/.../goalrunner/model/GoalRunnerPurgeModels.kt`: result model.
- `runtime-engine/.../goalrunner/manifest/WorkflowGoalRunnerManifestStore.kt`
  (`purgeDecomposedGoal` ~line 385; repo-root-aware `loadDurableByIssueKey` overload ~147-154).
- `runtime-engine/.../goalrunner/status/GoalRunnerStatusProjectionAssembler.kt`
  (`resolvePurgeBlockingLiveness` ~109-122), only if Task 6 requires it.
- `runtime-infra/sqlite/.../SQLiteRepositories.kt` (`SQLiteUnitOfWork.purgeDecomposedGoal`
  ~110-140) and the workflow parent-discovery code in
  `WorkflowStateRepositoryParentDiscovery.kt` (~101-130).
- `runtime-domain/.../DecompositionManifestRestart.kt` (`resetManifest`) only if the written
  YAML can still carry runtime fields.
- Path helpers to reuse, not copy: `FeatureTaskImplementationChecklist.kt` (tracking dir via
  `pathSegment`) and `FeatureTaskRuntimeRunEvidenceAddress.kt` (run-evidence dir via
  `pathSegment`).
- Port defaults in `testFixtures`: `UnitOfWorkDefaults.kt`, `GoalRunnerManifestStoreDefaults.kt`,
  `GoalPlanningPreparationRepositoryDefaults.kt`, `WorkflowStateRepositoryDefaults.kt`, plus
  any new port method.
- Tests: `CliGoalPurgeCommandTest.kt`, `GoalRunnerPurgeCoordinatorTest.kt`,
  `GoalRunnerPurgePersistenceTest.kt`.

## Implementation Tasks

1. **Repo-scoped ownership discovery.** Purge must find the parent by issue key and the repository
   identity of the repo root. Today it does not, so a same-key goal in another repo can be hit.
   Pass `repoRoot` into `loadDurableByIssueKey`. Collect the parent id, every child workflow id
   linked to it, and every subtask `workflow_id` in the goal's manifest. Do this before any delete,
   so a re-run still finds the ids. A row that matches issue key and repository identity but fails
   `decompositionRuntime()` decoding must not be skipped silently. Either include it by its id or
   report it as a leftover.
   *Assumption to confirm:* child workflows are reachable from the parent id without a repository
   identity column of their own.
   *Assumption to confirm:* goal tables that key only on issue key cannot hold two repositories'
   rows for the same key. If they can, scope those deletes through the repo-scoped parent instead.
2. **Complete DB coverage.** The five stores below survived the WE-5006 purge. In the same
   transaction as today's deletes, delete the owned ids' rows from `worktree_edit_journal`,
   `producer_output_evidence`, `rejected_output_diagnostics` and `agent_activity_stamps` (all keyed
   by `workflow_id`). Delete leases and `feature_task_execution_identities` explicitly, because
   cascade depends on `PRAGMA foreign_keys`. Collect the `session_id` values of the owned
   `feature_task_workflows` before deleting them. Then delete each `feature_task_runtime_sessions`
   row that no surviving workflow references. The transaction returns per-table removed counts.
3. **Repo-local directories.** These directories survived the WE-5006 purge. Before the DB
   transaction, delete `<repo>/.skill-bill/feature-task-tracking/<pathSegment(id)>/` and
   `<repo>/.skill-bill/run-evidence/<pathSegment(id)>/` for every owned id. Use the existing
   address helpers. Record removed paths. A path that fails to delete becomes a leftover; the
   remaining steps still run.
4. **Spec-bundle reset.** `spec.md` must stay byte-identical, and generated artifacts must not
   survive. Run this step after the DB transaction. Never write, restore or delete the manifest's
   parent spec path.
   - If the manifest is untracked at HEAD, delete it and every untracked subtask `spec_path` it
     names.
   - If the manifest is tracked, keep today's `resetManifest(hard=true)` write and
     restore-missing-from-HEAD. The written YAML must have parent and subtask `status: pending`,
     null runtime fields, `current_subtask_intent` at subtask 1 `start`, and no top-level
     `workflow_id` or `last_resumable_step` keys.

   Record each action (deleted, reset, restored).
   *Assumption to confirm:* a fresh `skill-bill <spec.md>` launch regenerates an absent manifest
   and subtask specs. The WE-5006 run generated `spec_subtask_1_*.md` itself, which supports this.
   If it does not, reset an untracked manifest in place instead of deleting it, and record that
   deviation in `census_subtask_1.md`.
5. **Verification and reporting.** A silent partial purge must be impossible. After all steps,
   count the surviving owned rows in every covered table, by owned ids and issue key, and the
   surviving owned directories. Extend `GoalRunnerPurgeResult` and the CLI payload and text with
   per-store removed counts, removed paths, spec-bundle actions, the retained `telemetry_outbox`
   note and leftovers. Exit with code 1 when leftovers are non-empty or a refusal is set.
   Otherwise exit 0. Stop discarding the checkpoint prune count; report it.
6. **Idempotence and liveness.** A re-run must not fail, and the WE-5006 paused shape must be
   purgeable.
   - Replace the `missingPurgeResult` refusal with an empty success that reports "nothing to
     remove".
   - Confirm which liveness a goal resolves to when it is paused with `operator_stop` and has no
     live lease holder. If that goal resolves to UNKNOWN or LIVE, narrow
     `resolvePurgeBlockingLiveness` so it resolves to not-live. Keep refusing goals with a live
     lease holder or process.
7. **Documentation.** The telemetry decision must be written down. State in the `goal purge`
   help/description text that `telemetry_outbox` rows are retained as anonymised history and do
   not affect relaunch. Update any docs page that describes `goal purge` the same way.

## Implementation Details

Paths are under `../../../runtime-kotlin`. Implement the steps in this order. Steps 1 to 7 each leave the
tree compiling only together, so they all land in one commit.

### Settled decisions (from the preplan digest)

- **Liveness (Task 6, AC 7).** `resolveParentExecutionLiveness` already returns IDLE when there
  is no lease, or when the lease holder process is `NotRunning`. `ExactLive`, `OwnershipMismatch`
  and `Unsupported` map to LIVE. Do not change `resolvePurgeBlockingLiveness`. The WE-5006 test
  (step 8c) proves that a paused `operator_stop` goal is purgeable. The existing live refusal stays.
- **Child scoping (Task 1, first assumption).** This assumption holds. Children are linked by the
  parent workflow id (`json_extract(artifacts_json, '$.goal_continuation.parent_workflow_id')`
  joined to a `goal_child` identity). The parent id is a unique `wftr-…` id, and it is discovered
  repo-scoped, so the children it reaches are repo-scoped too.
- **Goal tables (Task 1, second assumption).** This assumption holds for the goal tables.
  `goal_runner_controls` and `goal_issue_progress` delete by `parent_workflow_id`.
  `goal_run_sessions`, `goal_subtask_events` and phase settlements delete by `workflow_id`. None
  of them deletes by issue key alone. *Assumption to confirm:* the planning tables. Check which key
  `goalPlanningPreparations.deleteByGoal(parent)` uses. If shared-preplan or plan rows are keyed
  by (`normalized_issue_key`, `repository_identity`) rather than by parent workflow id, also delete
  them by that pair inside the same transaction. The pair is repo-scoped, so this is safe. The
  pair is also how a planning attempt under an earlier parent id gets removed. WE-5006
  `planning-log` showed two attempts.
- **Several matching parents.** Every decomposed parent row that matches the issue key and the
  repository identity belongs to this goal. A second one is a stale earlier launch. This applies
  whether the row decodes or not. Purge them all, together with their children.
  **Deviation from the digest.** The digest recommended reporting this case as a leftover. That
  would leave the exact stale state this issue is about, with no way to remove it.
  A standalone workflow with the same key carries no decomposition artifact. It is not a parent
  candidate and survives, as the existing persistence test requires.
- **Directory failure stops before the DB (Task 3).** **Deviation from Task 3's "the remaining
  steps still run".** The parent Decision requires that ids stay discoverable until the last step.
  Directory paths are derived from workflow ids, so running the DB delete after a failed directory
  delete would orphan that directory. So if any directory delete fails:
  - skip the DB transaction, the checkpoint prune and the spec reset;
  - report the failed paths, plus "database and spec-bundle steps deferred", as leftovers;
  - exit 1.

  A re-run then finds the same ids.
- **No snapshot-restore on DB failure.** Specs are now touched only after the DB transaction
  succeeds. Remove `restorePurgeSpecBundle` and the snapshotting in
  `purgeDatabaseAndRestoreOnFailure`. A SQL exception propagates as it does today. Directories may
  already be gone, but the ids stay discoverable, so a re-run completes the purge.
- **Relaunch regenerates the bundle (Task 4 assumption).** The WE-5006 run generated the manifest
  and `spec_subtask_1_*.md` itself, which confirms this. Delete untracked manifests. No
  `census_subtask_1.md` is needed.
- **Directory port.** Add one purpose-named port for both directories rather than reuse
  `SpecScratchStore`, whose KDoc scopes it to linear-mode spec scratch. Implement may reuse
  `FileSystemFeatureTaskRuntimeSharedEvidenceStore`'s delete only if it is already an injectable
  port that takes a directory path. Open that file once to check. Otherwise, use the new port for
  both directories.
- **Failure model.** Add no new exception class. Expected failures (a directory not deleted, a
  surviving row, a checkpoint prune diagnostic) are leftover data. Programming defects use
  `require` or `check`.

### Ordered tasks

1. **Port and model types (AC 1, 3, 5).**
   - In `runtime-ports/.../persistence/`, next to `UnitOfWork.kt`:
     - Add `GoalPurgeTarget(parentWorkflowIds: Set<String>, workflowIds: Set<String>)`.
       `workflowIds` covers parents, SQL-discovered children and verified manifest ids.
     - Add `GoalPurgeTableCounts(byTable: Map<String, Int>)`, with keys equal to the table or
       store names.
     - Change `UnitOfWork.purgeDecomposedGoal(parentWorkflowId: String)` to
       `purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts`.
     - Add `countDecomposedGoalState(target: GoalPurgeTarget): GoalPurgeTableCounts`, which runs
       the same table set as read-only `COUNT(*)` queries.
   - Mirror both methods on `GoalRunnerManifestPurgeCommands` in
     `runtime-engine/.../goalrunner/manifest/GoalRunnerManifestStore.kt`.
   - Add these to the same interface:
     - `discoverPurgeOwnership(issueKey, repoRoot: Path): GoalPurgeOwnership`. The engine model
       holds the decoded durable manifests, all parent workflow ids, and the ids of the parents
       that did not decode.
     - `verifyOwnedWorkflowIds(candidateIds, parentWorkflowIds, issueKey, repoRoot): Set<String>`.
   - *Assumption to confirm:* the raw-map guard accepts a typed `Map<String, Int>` inside a ports
     data class. If it rejects it, use `List<GoalPurgeTableCount(table, count)>`.
2. **SQLite purge and census (AC 1, 13).** In
   `runtime-infra/sqlite/.../SQLiteRepositories.kt` (`SQLiteUnitOfWork.purgeDecomposedGoal`
   ~110):
   - Union `target.workflowIds` with `listGoalChildWorkflowIdsByParent(p)` for each parent.
   - Read the distinct non-empty `session_id` values of those `feature_task_workflows` rows.
   - Run the explicit `DELETE … WHERE workflow_id IN (…)` statements first, through the existing
     `deleteByWorkflowIds` helper. Cover `worktree_edit_journal`, `producer_output_evidence`,
     `rejected_output_diagnostics`, `agent_activity_stamps`, `feature_task_runtime_worker_leases`
     and `feature_task_execution_identities`. Record each `executeUpdate` count.
   - Keep the existing goal, planning, settlement, progress and workflow deletes, and record their
     counts (stop discarding the `deleteByGoal` `Int`). Workflow deletes come **after** the
     explicit lease and identity deletes, so the counts are not masked by the cascade.
   - Then run
     `DELETE FROM feature_task_runtime_sessions WHERE session_id IN (…) AND NOT EXISTS (SELECT 1 FROM feature_task_workflows w WHERE w.session_id = feature_task_runtime_sessions.session_id)`.
   - Return all counts. `countDecomposedGoalState` counts the same tables by the same keys. For
     runtime sessions, it counts the collected session ids that are still present and unreferenced.
   - Wire both through `WorkflowGoalRunnerManifestStore` (~385), each in
     `database.transaction { … }`.
3. **Repo-scoped ownership discovery (AC 3).**
   - In `WorkflowStateRepositoryParentDiscovery.kt` (~101), add a non-throwing, repo-scoped
     listing that returns every decomposed parent candidate for (issue key, repository identity).
     It covers both decoded candidates and `ParentDiscoveryCandidate.Corrupt` ones, and is modelled
     on `findDecomposedParentOrCorruptFallback`.
   - It must call `listFeatureTaskWorkflowsForParentDiscovery` with a non-null identity, from
     `repositoryEnclosingRootPort.repositoryIdentity(repoRoot)`.
   - *Assumption to confirm:* the classification that already yields `Corrupt` means "carries a
     decomposition artifact that fails to decode". A row with no decomposition artifact is not a
     candidate.
   - `WorkflowGoalRunnerManifestStore.discoverPurgeOwnership` maps that listing to
     `GoalPurgeOwnership`.
   - `verifyOwnedWorkflowIds` keeps a manifest-recorded id only if one of these holds:
     - its `feature_task_execution_identities` row has the same `normalized_issue_key` and
       `repository_identity`;
     - it is a `goal_child` whose `goal_continuation.parent_workflow_id` is one of the parents.

     Unverifiable ids are dropped silently. They are not owned.
   - Status and other callers keep using `loadDurableByIssueKey` unchanged.
4. **Directory port (AC 2, 12).**
   - Add the port `GoalRuntimeStateFileStore` in `runtime-ports`. Put it beside the workflow
     ports, in a package chosen under the `PackageSiblingCountArchitectureTest` ceilings. It has:
     - `directoryExists(path: Path): Boolean`;
     - `deleteDirectoryTree(path: Path): GoalRuntimeDirectoryDeletion`, a sealed result:
       `Deleted`, `Absent` or `Failed(reason)`.
   - Implement it in `runtime-infra/workflow/.../filesystem/` as a reverse-order `Files.walk`
     delete. Reuse `FsContentPrimitives.deleteRecursively` if the module may depend on host.
     Catch `IOException` into `Failed`.
   - Bind it in `RuntimeComponent`, then count generated child-component readers. Add
     `UnavailableGoalRuntimeStateFileStore` and a recording or failing double to `testFixtures`,
     following `UnavailableSpecScratchStore` and `RecordingSpecScratchStore`.
   - Add `implementationChecklistDirectory(workflowId)` beside
     `implementationChecklistRelativePath` in `FeatureTaskImplementationChecklist.kt`. It returns
     `"$IMPLEMENTATION_CHECKLIST_STORE_ROOT/${FeatureTaskRuntimeRunEvidenceAddress.pathSegment(workflowId)}"`.
     Make `implementationChecklistRelativePath` use it. The run-evidence path is
     `FeatureTaskRuntimeRunEvidenceAddress.workflowStoreRoot(workflowId)`. Resolve both paths
     against the repo root. Then `require(resolved.normalize().startsWith(repoRoot.normalize()))`.
5. **Coordinator rewrite (AC 2 to 7).** File:
   `runtime-engine/.../goalrunner/reset/GoalRunnerPurgeCoordinator.kt`. Inject the new port.
   `purge()` becomes:
   1. **Discover.**
      - Call `discoverPurgeOwnership(issueKey, repoRoot)`.
      - Find disk manifests with `findMatchingDecompositionManifests(repoRoot, issueKey, false)`.
      - For each parent, call `listOwnedGoalChildWorkflowIds`.
      - Pass the manifest subtask `workflowId` values (disk and decoded DB) through
        `verifyOwnedWorkflowIds`.
      - Build the `GoalPurgeTarget`.
      - The source manifest is the disk manifest when it exists, otherwise the first decoded DB
        manifest. `manifestPath` becomes nullable.
      - When the disk manifest is gone, derive the manifest path from the DB manifest's parent spec
        directory. *Assumption to confirm:* use the helper that `findMatchingDecompositionManifests`
        uses for the bundle file name, and do not hard-code it.
   2. **Refuse.** Run `resolvePurgeBlockingLiveness` for each parent. If any parent is LIVE or
      UNKNOWN, return a refusal as today.
   3. **Nothing to remove.** If the target is empty, every owned directory is absent, no untracked
      manifest exists, and any tracked manifest already equals its hard-reset encoding, return a
      success with nothing removed. This replaces the `missingPurgeResult` refusal (AC 6).
   4. **Directories.** For every id in `target.workflowIds`, delete the tracking and run-evidence
      directories through the port. Record `Deleted` paths as repo-relative removed paths. On any
      `Failed`, return early with leftovers, as decided above.
   5. **DB.** If the target is non-empty, call `purgeDecomposedGoal(target)` and keep the counts.
   6. **Checkpoints.** Call `pruneGoalPurgeCheckpointRefs`. Keep its `Int` as
      `checkpointRefsPruned`. Collect the `record` strings into the leftovers.
   7. **Spec-bundle reset (AC 4).**
      - Never include `parentSpecPath`. Drop it from the restore loop, and skip any subtask
        `specPath` equal to it.
      - Probe `gitOperations.readHeadTrackedFile(repoRoot, manifestRelativePath)`. `Failed` means
        untracked.
      - **Untracked manifest:** for each subtask `specPath` that is a regular file and untracked
        (same probe), call `manifestFileStore.deleteIfExists`. Delete the manifest **last**. Record
        each as `DELETED`.
      - **Tracked manifest:**
        - Run `resetManifest(hard = true)`, encode the result, and `writeBundleAtomically` it.
          Record the write as `RESET`, unless the bytes are unchanged.
        - Restore missing tracked subtask specs from HEAD and record each as `RESTORED`.
        - A missing **untracked** subtask spec is no longer an error. Skip it.
   8. **Verify.**
      - Run `countDecomposedGoalState(target)`. Each non-zero table becomes the leftover
        `"<table>: <n> rows"`.
      - Re-run `directoryExists` on every computed directory. Each existing one becomes a
        leftover path.
      - Re-run `discoverPurgeOwnership`. Any parent still found becomes a leftover.
      - Any untracked manifest or untracked subtask spec still on disk becomes a leftover.

   Keep `GoalRunnerStatusService.purge` as is. It already defaults `repoRoot`. If the coordinator
   exceeds detekt size limits, extract the spec-bundle reset into an `internal` class in the same
   package, within the sibling ceiling.
6. **Result, CLI and docs (AC 5, 6, 8).**
   - **Result model.** In `GoalRunnerPurgeModels.kt`, `GoalRunnerPurgeResult` gains these fields:
     - `parentWorkflowIds: List<String>`
     - `deletedChildWorkflowIds`
     - `removedRowCounts: Map<String, Int>`
     - `removedPaths: List<String>`
     - `specBundleActions: List<GoalRunnerPurgeSpecAction(path, kind: DELETED | RESET | RESTORED)>`
     - `checkpointRefsPruned: Int`
     - `leftovers: List<String>`
     - `refusalReason: String?`

     Derive `nothingToRemove`. `specRestored` is replaced by `specBundleActions`. Keep the
     `spec_restored` payload key only if implement finds a consumer of it.
   - **Allowlist.** Add the new public type names to the `RuntimeEngineInboundApiTest` allowlist
     (~141).
   - **CLI payload.** `GoalCliPurgeFormatting.toGoalPurgeCliMap` adds `GoalRunnerPurgePayloadKeys`
     entries for:
     - `removed_row_counts`
     - `removed_paths`
     - `spec_bundle_actions`
     - `checkpoint_refs_pruned`
     - `leftovers`
     - `nothing_to_remove`
     - `retained_note`

     `status` is `ok`, `incomplete` (leftovers present) or `refused`.
   - **Exit code.** `goalPurgeExitCode` returns 1 when `refusalReason != null ||
     leftovers.isNotEmpty()`.
   - **Text output.** `goalPurgeText` lists the non-zero counts, the removed paths, the spec
     actions, the pruned refs and the leftovers. It prints "Nothing to remove for X." when
     `nothingToRemove` holds. It always ends with the telemetry sentence.
   - **Telemetry sentence.** Use one constant: "`telemetry_outbox` rows are retained as anonymised
     history and do not affect relaunch." Put it in the `GoalPurgeCommand` `DocumentedCliCommand`
     description (AC 8) and in the text and payload.
   - **Docs.** Update `../../../docs/runtime-command-guidance.md` and any other doc that describes `goal
     purge` or its missing-goal refusal. Search for "restore the feature-spec tree to an unlaunched
     shape".
7. **Fixtures and call sites.**
   - Update the defaults in `UnitOfWorkDefaults.kt` and `GoalRunnerManifestStoreDefaults.kt` for
     the new and changed methods. Update `GoalPlanningPreparationRepositoryDefaults.kt` and
     `WorkflowStateRepositoryDefaults.kt` if they gain methods.
   - Update the anonymous `GoalRunnerManifestStore` overrides in `GoalRunnerPurgeCoordinatorTest`
     (~67, 126, 157, 220).
   - Update every other override that implement finds by grepping for `purgeDecomposedGoal(`.
   - Wire the new port into `GoalRunnerTestFactory` and `GoalRunnerSharedTestFactory` wherever
     they construct the coordinator.
8. **Tests.** There are five new tests and one in-place conversion. Each names the bug it catches.
   Test environments pass a non-empty `environment` map, because `emptyMap()` falls back to the host
   environment. Mocks use `relaxUnitFun = true`.
   - a. **`GoalRunnerPurgePersistenceTest`: extend the existing purge test (AC 1, 13).**
     - Seed these rows for the parent and the child:
       - `worktree_edit_journal`;
       - `producer_output_evidence`;
       - `rejected_output_diagnostics`, with payload and lifecycle values that satisfy its CHECK
         constraint;
       - `agent_activity_stamps`;
       - a lease;
       - two runtime sessions. Session A is used only by owned workflows. Session B is shared with
         the standalone sibling.
     - Assert:
       - zero owned rows remain;
       - the lease and identity counts returned are at least 1, which proves the explicit deletes
         ran before the cascade;
       - A is deleted and B survives;
       - the telemetry outbox count is unchanged, as it is today.
     - Bug caught: tables skipped, or a session that is shared with a surviving workflow gets
       deleted.
   - b. **`GoalRunnerPurgeCoordinatorTest`.**
     - Add a leftover test (AC 12). A failing double of the directory port returns `Failed` for one
       path. Assert that the path is in `leftovers`, that `purgeDecomposedGoal` was never invoked
       (outcome: the store's recorded purge list is empty), and that `refusalReason` is null.
     - Extend the existing tracked-bundle test's `assertUnlaunched` (~243) with assertions on the
       written YAML text (AC 4):
       - `status: "pending"` for the parent and the subtasks;
       - no top-level `workflow_id:` or `last_resumable_step:`;
       - `current_subtask_intent` at subtask 1 `start`.

       If this fails, fix `resetManifest` or `encodeDecompositionManifestYaml`.
     - Convert `missing untracked spec refuses before database purge` (~191) in place. It now
       asserts that the purge completes and that the DB purge ran. Bug caught: a stale refusal path
       that leaves the DB intact.
   - c. **`CliGoalPurgeCommandTest`: WE-5006 regression (AC 9, 7).**
     - Write one seed helper, `seedWe5006Goal(repoRoot)`, that seeds everything AC 9 lists through
       real SQLite and the filesystem.
     - The lease must name a holder process that is not running, so liveness resolves IDLE. Use an
       unused pid, or a holder token the liveness probe classifies as `NotRunning`. *Assumption to
       confirm:* the exact lease fields.
     - Keep `spec.md` tracked or authored, and record its bytes.
     - Run purge and assert every outcome AC 9 lists. The `goal status` and `goal planning-log`
       checks run through the CLI.
     - Bug caught: the shipped WE-5006 failure.
   - d. **`CliGoalPurgeCommandTest`: relaunch (AC 10).**
     - Run `seedWe5006Goal`, then purge, then `runPreflight`.
     - Assert that preflight reports `new_work` and that its payload has no
       `goal_planning_migration` `unsafe_import` refusal.
     - If `GoalCliFixture` exposes planning-migration admission, also assert CURRENT.
     - This extends the existing `confirmed goal purge restores an unlaunched bundle and preflight
       reports new work` test to the planning-row fixture, rather than adding a sibling test.
   - e. **`CliGoalPurgeCommandTest`: isolation (AC 11).**
     - Seed the target with `seedWe5006Goal`.
     - Seed a second goal under a different issue key in the same repo, with its rows,
       directories and spec bundle.
     - Seed a second temp repo root, giving a different repository identity, holding a workflow
       with the **same** issue key, plus its rows and directories.
     - Make the target's untracked manifest list that other repo's workflow id as a subtask
       `workflow_id`.
     - Purge the target. Assert that the other goal's and the other repo's row counts, directories
       and spec bytes are unchanged.
     - Bug caught: unscoped parent lookup, and manifest ids trusted without verification.
   - f. **CLI exit on leftover (AC 12).**
     - If `GoalCliFixture` can bind a failing `GoalRuntimeStateFileStore`, add a CLI case that
       asserts exit 1 and `leftovers` in the payload.
     - Otherwise, cover AC 12's exit-code half with one assertion in the CLI module that
       `goalPurgeExitCode` returns 1 for a result whose only problem is a leftover. Record the
       choice in the implement summary.
   - **Not tested:** the help text, formatting glue, and the liveness logic, which is unchanged.

### Constraints

- Do not change `GoalPlanningMigration`, the LIVE refusal policy, the telemetry outbox, or git
  branch and worktree cleanup.
- Do not delete arbitrary manifest ids without verification.
- Do not add custom exception classes.
- Implement runs no builds, tests, `./gradlew check` or installers. The build and validate
  phases own those.
- Validate must watch these architecture guards:
  - the `RuntimeEngineInboundApiTest` allowlist;
  - the `PackageSiblingCountArchitectureTest` ceilings;
  - the raw-map guard;
  - the kotlin-inject accessor census;
  - the custom-exception baseline.

## Acceptance Criteria

1. `SQLiteUnitOfWork.purgeDecomposedGoal`, or its replacement, deletes owned rows from
   `worktree_edit_journal`, `producer_output_evidence`, `rejected_output_diagnostics`,
   `agent_activity_stamps`, leases and `feature_task_execution_identities` with explicit
   statements. It also deletes `feature_task_runtime_sessions` rows that no surviving workflow
   references. These deletes run alongside the existing goal, planning and workflow deletes and
   return per-table counts.
2. The purge coordinator deletes `feature-task-tracking/<pathSegment(id)>` and
   `run-evidence/<pathSegment(id)>` under `<repo>/.skill-bill/` for the parent and every child
   workflow id. It deletes them before the DB transaction, using the existing address helpers.
3. Purge discovers the parent with the repo root's repository identity. It never silently skips
   a matching row that fails to decode; such a row is either purged or reported as a leftover.
4. The spec-bundle reset never writes or deletes the manifest's parent spec path. An untracked
   manifest is deleted together with the untracked subtask specs it names. A tracked manifest is
   written with `status: pending`, null runtime fields, `current_subtask_intent` at subtask 1
   `start`, and no top-level `workflow_id` or `last_resumable_step` keys.
5. `GoalRunnerPurgeResult` and the CLI payload and text carry per-store removed counts, removed
   paths, spec-bundle actions, the checkpoint prune count, the retained-telemetry note and
   leftovers. The CLI exits 1 when leftovers or a refusal are present.
6. A purge that finds no owned state returns success with a "nothing to remove" report.
   `missingPurgeResult` no longer sets `refusalReason` for this case.
7. A goal paused with `operator_stop` and no live lease holder is not refused by
   `resolvePurgeBlockingLiveness`. A goal with a live lease holder is still refused.
8. The `goal purge` help text states that `telemetry_outbox` rows are retained.
9. `CliGoalPurgeCommandTest` contains a WE-5006 regression test. It seeds, through real SQLite
   and the filesystem:
   - a prepared shared preplan and a subtask plan;
   - a paused `operator_stop` runner control;
   - parent and child `feature_task_workflows` with a runtime session, a lease and an execution
     identity;
   - rows in all four `workflow_id`-keyed evidence and journal tables;
   - tracking and run-evidence directories;
   - an untracked `in_progress` manifest with a generated subtask spec.

   It runs purge and asserts:
   - exit 0;
   - zero owned rows in every listed table and no owned directories;
   - `spec.md` bytes unchanged and the generated artifacts gone;
   - `goal status` reports no goal and `goal planning-log` reports nothing;
   - a second purge exits 0 and reports nothing to remove.
10. A test asserts that, after purging the WE-5006 shape, planning-migration admission returns
    CURRENT with no `unsafe_import` refusal and preflight reports `new_work`. The diagnostic line
    itself may still print.
11. A test seeds the target goal, a second goal in the same repository and a workflow under a
    different repository identity. It purges the target and asserts that the other goal's and
    the other repository's rows, directories and spec files are unchanged.
12. A test makes one owned item fail to delete through a fake port. It asserts that the purge
    result lists the item as a leftover and that the CLI exit code is non-zero.
13. `GoalRunnerPurgePersistenceTest` asserts that a `feature_task_runtime_sessions` row still
    referenced by an unowned workflow survives the purge.

## Test Obligations

- AC 9: catches the shipped bug. Purge reports success while five stores, the directories and
  generated specs survive, and a second run exits 1.
- AC 10: catches a purge that leaves preplan or plan rows, which sends relaunch down the
  historical-import branch.
- AC 11: catches deletes that are keyed only on issue key or on an unscoped parent lookup.
- AC 12: catches a silent partial purge.
- AC 13: catches session deletion that orphans an unrelated workflow's session.
- Not tested separately: the tracked-manifest YAML shape (AC 4). Add one assertion to an
  existing tracked-bundle purge test if one exists; do not add a new test. The help text and
  formatting glue are not tested.
- Mocks use `relaxUnitFun = true`, never `relaxed = true`.

## Non-Goals

- No change to `GoalPlanningMigration` semantics or its seam diagnostic.
- No new telemetry event, and no deletion of `telemetry_outbox` rows.
- No change to the LIVE refusal for goals with a live lease holder or process.
- No new cleanup of git branches or worktrees.

## Dependency Notes

None. This is the only subtask, and it runs on the current tree.

## Validation Strategy

- The build phase runs the pack build command.
- The validate phase runs the pack validation gate and `./gradlew check`.
- Run Gradle from a local clone, not a linked `git worktree`, because spotless fails there.
- If spotless reports a stale JVM-local cache, rerun with `--no-configuration-cache`.
- Implement and audit do not run builds or tests.

## Next Path

```bash
skill-bill goal SKILL-409
```
