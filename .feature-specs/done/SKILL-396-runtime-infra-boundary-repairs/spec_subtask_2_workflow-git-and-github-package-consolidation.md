# SKILL-396 Subtask 2 - Workflow git and GitHub package consolidation

Parent spec: [.feature-specs/SKILL-396-runtime-infra-boundary-repairs/spec.md](spec.md)
Issue key: SKILL-396

## Scope

F-006, mechanical only.
- Move GitReadinessTreeIdentityOperations.kt, GitWorkflowGitOperations.kt and GitWorkflowGitOperationsFingerprint.kt from skillbill.infrastructure.workflow.git.workflow to skillbill.infrastructure.workflow.git.
- Move GhCommandRunner.kt, GhGoalPullRequestPort.kt and GhPullRequestIdentityLookup.kt (from workflow.git.goal) and GhPullRequestReviewThreads.kt (from workflow.git.github) into skillbill.infrastructure.workflow.github, beside GitHubPullRequestCheckDiscovery.kt.
- Move the matching test files to mirror the new packages.
- Rewrite imports in every consumer: workflow, engine test, core main and test, cli test and repoTest, mcp test.
- No declaration, signature, visibility or behaviour change.

## Acceptance Criteria

1. No .kt file under runtime-kotlin declares or imports a package starting with skillbill.infrastructure.workflow.git.workflow.
2. No .kt file under runtime-kotlin declares or imports package skillbill.infrastructure.workflow.git.github.
3. GitReadinessTreeIdentityOperations.kt, GitWorkflowGitOperations.kt and GitWorkflowGitOperationsFingerprint.kt declare package skillbill.infrastructure.workflow.git.
4. GhCommandRunner.kt, GhGoalPullRequestPort.kt, GhPullRequestIdentityLookup.kt and GhPullRequestReviewThreads.kt declare package skillbill.infrastructure.workflow.github, and no file in skillbill.infrastructure.workflow.git.goal references GhCommandRunner.
5. GhPullRequestReviewThreadsTest and GhPullRequestIdentityLookupTest declare package skillbill.infrastructure.workflow.github.
6. The moved declarations keep their names, visibility and bodies, apart from package and import lines.
7. No file under runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines gained a row.

## Non-Goals

- Renaming classes or changing GhCommandRunner visibility or its test seam.
- Moving any other workflow package.
- Behaviour or wire changes.

## Dependency Notes

Depends on: 1
Depends on subtask 1. Consumers overlap SKILL-390 (engine test, 11 files), SKILL-392 (cli test and repoTest), SKILL-395 (mcp test) and SKILL-389/388 (core main). It waits for no other issue: move the files present and rewrite the import lines in every consumer present when it runs. Whichever bundle lands second keeps the other's edits.

## Validation Strategy

Goal build gate: compile all runtime-kotlin modules and run the workflow, engine, core, cli and mcp tests, the repoTest acyclicity guard and spotless. There is no new test because the change is import-only.

## Implementation Details

Scope is F-006 only. Subtask 1 (F-001 to F-005) touches runtime-infra/contracts, sqlite, skills, runtime-domain and one core DI import for the add-on store. None of the files below overlap with it, so this plan applies to the tree as it stands when the task runs. All paths are relative to `../../../runtime-kotlin/runtime-infra/workflow/src` unless stated otherwise. Use `git mv` so history follows the files. Only `package` and `import` lines change.

Census taken on `fe6244253`. Rerun the step 6 greps before editing, and treat any extra hit as one more consumer to rewrite.

### 1. Move the three git.workflow main files into `git` (AC-001, AC-003, AC-006)
- `git mv main/kotlin/skillbill/infrastructure/workflow/git/workflow/{GitReadinessTreeIdentityOperations,GitWorkflowGitOperations,GitWorkflowGitOperationsFingerprint}.kt main/kotlin/skillbill/infrastructure/workflow/git/`
- In each file, change `package skillbill.infrastructure.workflow.git.workflow` to `package skillbill.infrastructure.workflow.git`.
- Leave the imports in `GitWorkflowGitOperations.kt` (`git.checkpoint`, `git.goal.GitGoalSubtaskReviewOperations`, `git.scoped`, `git.standard`) unchanged. Its declarations, including `GitRepositoryFingerprintOperations`, keep their names and visibility.
- Delete the now-empty `main/.../git/workflow/` directory.

### 2. Move the four gh adapters into `github` (AC-002, AC-004, AC-006)
- `git mv` `main/.../git/goal/{GhCommandRunner,GhGoalPullRequestPort,GhPullRequestIdentityLookup}.kt` and `main/.../git/github/GhPullRequestReviewThreads.kt` into `main/kotlin/skillbill/infrastructure/workflow/github/`, beside `GitHubPullRequestCheckDiscovery.kt`.
- Change each `package` line to `package skillbill.infrastructure.workflow.github`.
- In `GhPullRequestReviewThreads.kt`, delete the three imports that are now in the same package: `git.goal.GhCommandRunner`, `git.goal.ProcessGhCommandRunner` and `git.goal.describeFailure`.
- Keep `GhCommandRunner`/`ProcessGhCommandRunner` `internal` and keep the internal-constructor test seams. No renames.
- Delete the now-empty `main/.../git/github/` directory.
- `git.goal` keeps `FileSystemPullRequestTemplateFiles`, `GitGoalReviewReads` and `GitGoalSubtaskReviewOperations`. None of them references any Gh* symbol and the Gh* files reference none of them (checked), so the second half of AC-004 holds once the move is done.

### 3. Mirror the tests (AC-001, AC-002, AC-005, AC-006)
- `git mv` the 7 files in `test/kotlin/skillbill/infrastructure/workflow/git/workflow/` into `test/.../workflow/git/`, and set their package to `skillbill.infrastructure.workflow.git`. The files are `GitWorkflowGitOperationsRecoveryTest`, `GitWorkflowGitOperationsDiffTest`, `GitReadinessTreeIdentityOperationsTest`, `GitWorkflowGitOperationsBaselineTest`, `GitRepositoryFingerprintOperationsTest`, `GitWorkflowGitOperationsTestSupport` and `GitSuppressionEvidenceOperationsTest`.
- `git mv test/.../git/github/GhPullRequestReviewThreadsTest.kt` and `test/.../git/goal/GhPullRequestIdentityLookupTest.kt` into `test/kotlin/skillbill/infrastructure/workflow/github/`, and set both packages to `skillbill.infrastructure.workflow.github`.
- In `GhPullRequestReviewThreadsTest`, delete `import skillbill.infrastructure.workflow.git.goal.GhCommandResult`, which is now same-package.
- Delete the empty test directories `git/workflow/`, `git/github/` and `git/goal/`. The `git.goal` main package has no other tests.

### 4. Rewrite consumer imports (AC-001, AC-002)
Make these line-for-line replacements: `skillbill.infrastructure.workflow.git.workflow.X` becomes `skillbill.infrastructure.workflow.git.X`; `skillbill.infrastructure.workflow.git.goal.Gh*` and `skillbill.infrastructure.workflow.git.github.Gh*` become `skillbill.infrastructure.workflow.github.Gh*`.
- workflow main: `git/standard/GitStandardWorkflowGitWorktreeOperations.kt` (`GitRepositoryFingerprintOperations`).
- workflow test: `git/checkpoint/GitCheckpointHistoryOperationsTest.kt`, `git/scoped/GitScopedStagingOperationsTest.kt`, `git/standard/GitLocalBranchUnpushedCommitsTest.kt`, `git/standard/GitProtectedBranchPushGuardTest.kt`.
- runtime-core main:
  - `runtime-core/src/main/kotlin/skillbill/di/operation/RuntimeOperationProvides.kt` (`GhPullRequestReviewThreads`)
  - `runtime-core/src/main/kotlin/skillbill/di/core/RuntimeOptionalCallbackProvides.kt` (`GhGoalPullRequestPort`, `GhPullRequestIdentityLookup`, `GitWorkflowGitOperations`; `FileSystemPullRequestTemplateFiles` stays in `git.goal`)
- runtime-core test: `runtime-core/src/test/kotlin/skillbill/di/core/AbsentOptionalPortResolutionTest.kt`.
- runtime-cli: `runtime-cli/src/test/kotlin/skillbill/cli/CliRuntimeShellCommandsTest.kt`, `runtime-cli/src/repoTest/kotlin/skillbill/cli/CliWorkflowUpdateRuntimeTest.kt`.
- runtime-mcp: `runtime-mcp/src/test/kotlin/skillbill/mcp/workflow/McpVerifyWorkflowToolsTest.kt`.
- runtime-engine test (11 files under `runtime-engine/src/test/kotlin/skillbill/engine/`):
  - `operation/ChecklistOperationHarness.kt`
  - `operation/prreviewfix/PrReviewFixHarness.kt`
  - `operation/release/ReleaseOperationTest.kt`
  - `operation/verify/VerifyOperationHarness.kt`
  - `goalrunner/reset/GoalRunnerReplanTest.kt`
  - `featuretask/lifecycle/checkpoint/FeatureTaskRuntimeCheckpointRefPruneTest.kt`
  - `featuretask/lifecycle/subtask/FeatureTaskRuntimeSubtaskFinalisationTest.kt`
  - `featuretask/lifecycle/remediation/RemediationBaseReconciliationUnderAmendTest.kt`
  - `featuretask/review/goal/GoalSubtaskReviewStateDurablePersistenceTest.kt`
  - `featuretask/phaserun/PhasePullRequestRunTest.kt`
  - `featuretask/slot/pullrequest/PrDescriptionRunSupport.kt`
- Re-sort each edited import block in ASCII order to keep the ktlint/spotless import-ordering rule:
  - `...workflow.git.GitX` (uppercase) sorts before `...workflow.git.goal.*`.
  - `...workflow.github.*` sorts after every `...workflow.git.*` line.
- If a rewrite leaves two identical imports, drop the duplicate. Do not add star imports.

### 5. Guards and baselines (AC-007)
- Add no baseline rows, and edit no baseline file.
- The runtime-infra-workflow package-cycle scan is `FIRST_SEGMENT_MUTUAL_PAIR` over `skillbill.infrastructure.workflow.`. After the move, `github` imports nothing under `skillbill.infrastructure.workflow.*`, and nothing in runtime-infra/workflow main imports `github`. No `git`↔`github` pair can form, so `runtime-infra-workflow-package-cycle-baseline.txt` stays empty.
- No baseline, `RuntimeModuleCatalog` entry or `build.gradle.kts` line names the moved files or packages, so nothing else needs editing.
- `runtime-kotlin/ARCHITECTURE.md:1128` names `GhPullRequestReviewThreads` by class only, so leave it as is. Do not edit `../../../agent/history.md` or `decisions.md` here; write_history owns them.

### 6. Self-check greps for the implement/audit phases (no compile or test runs here)
- `grep -rlE --include='*.kt' 'skillbill\.infrastructure\.workflow\.git\.(workflow|github)' runtime-kotlin` must return nothing (AC-001, AC-002).
- `grep -rn 'GhCommandRunner' runtime-kotlin/runtime-infra/workflow/src/*/kotlin/skillbill/infrastructure/workflow/git/goal` must return nothing (AC-004).
- `grep -n '^package' <moved files>` must show the target packages (AC-003, AC-004, AC-005).
- `git diff -M --stat` must show the 16 moved files as renames. A per-file `git diff -M` of each moved file must show only `package`/`import` hunks (AC-006).
- `git diff -- runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines` must be empty (AC-007).

### Tests
- Add no new tests. The change is import-only and no realistic bug would be caught by a new test that the moved suites miss, so `test_obligations` is empty.
- The 9 moved test files, plus the consumer tests in workflow, engine, core, cli (including repoTest) and mcp, are the regression coverage. The build gate and the validate phase run them, along with the repoTest acyclicity, test-package-mirroring and ambient guards and spotless. This phase and the implement phase do not run them.

### Constraints
- Do not change any declaration, signature, visibility, body or behaviour (AC-006). Do not rename anything, and do not move any other workflow package.
- Do not add a module, guard, port, `settings.gradle.kts` include or `build.gradle.kts` dependency line.
- Keep sibling-bundle edits already in the consumer files (SKILL-389/388 core DI, SKILL-392 cli, SKILL-395 mcp, and SKILL-390 engine tests if it has landed). Change only the import lines.
- Run spotless in a regular clone, not a linked worktree. If it reports a stale configuration cache, rerun with `--no-configuration-cache`.

## Next Path

skill-bill goal SKILL-396

## Spec Path

.feature-specs/SKILL-396-runtime-infra-boundary-repairs/spec_subtask_2_workflow-git-and-github-package-consolidation.md
