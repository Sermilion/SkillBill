# SKILL-407 Subtask 1 - Implement the requested change

Parent spec: [.feature-specs/SKILL-407-add-a-final-monitor-phase-to-the-skill-bill-workflow/spec.md](spec.md)
Issue key: SKILL-407

## Scope

SKILL-407 Add a final "monitor" phase to the skill-bill workflow

Add a new phase to the skill-bill workflow. It is the last phase of the workflow, named "monitor".

Behavior:
- It watches the PR created by the workflow and makes sure its CI passes.
- If CI fails, it fixes the failure and monitors again.
- It repeats this up to 3 times.
- After 3 failed fix attempts, it blocks and reports why it can't fix the CI.

Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection. Only an explicit unresolved tracker reference without requirements needs lookup through its connected tracker before planning. Use the returned requirements, not the URL title. If that lookup fails, block with the returned reason before implementation; never infer or substitute requirements.

## Acceptance Criteria

1. SKILL-407 Add a final "monitor" phase to the skill-bill workflow Add a new phase to the skill-bill workflow. It is the last phase of the workflow, named "monitor". Behavior: - It watches the PR created by the workflow and makes sure its CI passes. - If CI fails, it fixes the failure and monitors again. - It repeats this up to 3 times. - After 3 failed fix attempts, it blocks and reports why it can't fix the CI.

## Non-Goals

- None

## Dependency Notes

Depends on: none
The full goal owns planning and execution of the supplied requirements.

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.

## Implementation Details

Every task serves AC 1. Module paths are abbreviated: `runtime-domain/...` means `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/...`, and `runtime-engine/...` means `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/...`.

### Settled design decisions

These settle the open questions from preplan. Each one marked *assumption* rests on a fact the preplan digest did not verify. Implement confirms it against the code. If the code contradicts it, implement takes the stated fallback.

1. **Two phase ids.**
   - `monitor` is the last forward step. The runtime settles it, as it settles `commit_push`. It watches CI through a new port and decides the outcome without an agent session.
   - `monitor_fix` is a loop-only, agent-settled phase. It is reached only from `monitor` and repairs the CI failure.
   - *Assumption:* the runtime-settled mechanism behind `commit_push` (`agentSettledPhaseIds = stepIds - PHASE_COMMIT_PUSH`) can take a second phase id. If it is hard-wired to commit/push, generalise it to a set (`runtimeSettledPhaseIds = setOf(PHASE_COMMIT_PUSH, PHASE_MONITOR)`). Do not add a monitor-specific bypass.
2. **Fix loop routing.**
   - `monitor` has a backward edge to `monitor_fix` with cap 3, `capScope = PER_SUBTASK`, and BLOCK on exhaustion. This follows the write-history regeneration edge pattern.
   - `monitor_fix` goes into `loopOnlyPhaseIds`. Its `loopOnlySuccessors` entry is `commit_push`, so the fix is committed and pushed by the runtime.
   - The forward order then continues `commit_push -> pr -> monitor`.
   - Cap semantics: CI failure triggers fixes 1 to 3. When CI is still failing after the third fix, the 4th traversal request exhausts the edge, and the run BLOCKs.
   - *Assumption:* the cap counts allowed traversals, matching write-history's cap 2. Confirm against the existing transition evaluator, and set the number so that exactly 3 `monitor_fix` runs are possible.
   - *Assumption:* re-entering `pr` when a PR already exists reuses that PR, through `PullRequestIdentityLookup` returning `Found`. If the PR strategy would create a duplicate or fail on an existing PR, make it reuse the found PR and skip creation. Do not route around `pr`.
3. **Scope.**
   - `MONITOR` goes into `FEATURE_RUN_SLOTS`, which makes it part of `STANDALONE`, and into the `PR` skeleton (`COMMIT_PUSH`, `PULL_REQUEST`, `MONITOR`).
   - `GOAL_CHILD` excludes it: `filter { it != PULL_REQUEST && it != MONITOR }`.
   - Goal-level PRs (`GhGoalPullRequestPort`) are out of scope.
4. **What "CI passes" means.** All checks reported for the PR head count, which follows the AC literally.
   - `bucket` `pass` or `skipping` means OK.
   - `fail` or `cancel` means failed.
   - `pending` means wait.
   - Required-only filtering is not used. Its behaviour when the repository has no branch protection is unverified, and the AC says "its CI passes".
5. **Wait budget.**
   - Poll every 30 s, with a total watch timeout of 30 min per `monitor` entry.
   - "No checks reported" is treated as pending for a grace period of 3 min. The grace period covers a fresh push whose checks are not registered yet. After the grace period, `monitor` completes with the note "no CI checks configured for the PR head".
   - A timeout BLOCKs with a reason that names the checks still pending. A timeout does not consume a fix attempt.
   - Interval, timeout, and grace are constructor parameters with these defaults. A clock and a sleep function are injected so tests run without real time.
6. **gh failures never pass.**
   - These map to `Unavailable(reason)`, and `monitor` BLOCKs with that reason: a missing `gh`, missing auth, an unknown `--json` flag (older gh), a non-JSON output, or no PR found for the branch (`PullRequestIdentity.Absent`/`Unavailable`).
   - The block reason for an unsupported flag says "gh pr checks --json unsupported; upgrade gh".
   - *Assumption:* `gh pr checks` exits non-zero while checks are pending or failing (8 and 1) but still prints JSON. The implementation parses stdout whenever it is valid JSON, whatever the exit code. It falls back to `Unavailable` only when stdout does not parse.
7. **Contract version.** The new `monitor` and `monitor_fix` enum values are additive in `workflow-state-schema.yaml`. `WORKFLOW_STATE_CONTRACT_VERSION` is not bumped, because previously stored states never contain the new ids.
   - *Assumption:* no parity test ties enum contents to the version constant. If one does, follow what that test requires and record it.
8. **Exhaustion block reason.** The reason states:
   - that CI is still failing after 3 fix attempts;
   - the failing check names and links;
   - the last `monitor_fix` prose summary.

   `monitor_fix` may also block early through the normal phase-block path when a failure cannot be fixed in the repository, such as missing secrets or infrastructure outages. It states why.

### Ordered tasks

1. **Phase ids** (`runtime-domain/.../model/core/FeatureTaskRuntimePhaseIds.kt`)
   - Add `MONITOR = "monitor"` and `MONITOR_FIX = "monitor_fix"`.
   - Add `MONITOR` to `all`.
   - Include `MONITOR_FIX` in `all` only if the existing loop-only ids (for example `implement_fix`) are in `all`. Mirror their treatment exactly.
   - The execution-matrix (`ExecutionMatrixModels.kt`), compaction (`CompactionSettingsModels.kt`), and `FeatureTaskRuntimePhaseIdValidation.kt` validity checks then accept the ids with no further edits.
2. **Slot** (`runtime-domain/.../model/skeleton/PhaseSlot.kt`)
   - Add `MONITOR("monitor", listOf(FeatureTaskRuntimePhaseIds.MONITOR, FeatureTaskRuntimePhaseIds.MONITOR_FIX))` between `PULL_REQUEST` and `STANDALONE_REVIEW`.
   - List `MONITOR_FIX` under the slot only if the slot that owns `implement_fix` lists its loop phase the same way. Otherwise use `listOf(MONITOR)` and mirror the `implement_fix` placement.
3. **Skeletons** (`runtime-domain/.../model/skeleton/SkeletonDefinition.kt`)
   - Append `MONITOR` to `FEATURE_RUN_SLOTS`.
   - `GOAL_CHILD` filters out both `PULL_REQUEST` and `MONITOR`.
   - The `PR` skeleton becomes `COMMIT_PUSH, PULL_REQUEST, MONITOR`.
   - The ascending-ordinal `init` check holds because of the enum placement in task 2.
4. **Graph** (`runtime-domain/.../phase/task/FeatureTaskRuntimePhaseWorkflowGraph.kt`)
   - `stepLabels`: add "Phase 10: Monitor", and a `monitor_fix` label in the same style as `implement_fix`, for example "Phase 10: Monitor Fix".
   - `requiredArtifactsByStep`: `MONITOR` requires `COMMIT_PUSH` and `PR`. `MONITOR_FIX` requires `MONITOR`.
   - `resumeActions`: `MONITOR` resumes by re-reading PR and CI state, starting a fresh watch. It never re-applies a fix. The attempt count lives in the persisted per-subtask loop cap, not in the resume action. `MONITOR_FIX` resumes like `implement_fix`.
5. **Definition and transitions** (`.../FeatureTaskRuntimePhaseWorkflowDefinition.kt`, `.../FeatureTaskRuntimePhaseWorkflowTransitions.kt`)
   - Add the `PHASE_MONITOR` and `PHASE_MONITOR_FIX` constants, and a loop id `monitor_fix` next to `review_fix` and `audit_repair`.
   - Exclude `PHASE_MONITOR` from `agentSettledPhaseIds`, following decision 1.
   - Add the backward edge `monitor -> monitor_fix` (cap 3, `PER_SUBTASK`, BLOCK on exhaustion).
   - Add `monitor_fix` to `loopOnlyPhaseIds`, and add `loopOnlySuccessors[monitor_fix] = commit_push`.
6. **CI port** (`../../../runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/goalrunner/runner/PullRequestChecksLookup.kt`)
   - Add `fun interface PullRequestChecksLookup { fun lookup(root, prNumber: Int): PullRequestChecks }`. Match the parameter types of `PullRequestIdentityLookup`.
   - Result: `sealed interface PullRequestChecks` with these cases:
     - `Reported(checks: List<PullRequestCheck>)`
     - `NoChecks`
     - `Unavailable(reason: String)`
   - `PullRequestCheck(name, bucket: CheckBucket, link)`, where `CheckBucket` is `PASS, SKIPPING, FAIL, CANCEL, PENDING`.
   - Use typed values, not raw maps, because of the raw-map guard.
   - Keep it beside `PullRequestIdentityLookup`, so `RuntimeEngineBoundaryArchitectureTest` keeps passing without edits.
7. **gh adapter** (`../../../runtime-kotlin/runtime-infra/workflow/src/main/kotlin/skillbill/infrastructure/workflow/github/GhPullRequestChecksLookup.kt`)
   - Takes a `GhCommandRunner` and runs `gh.run(root, listOf("pr", "checks", "<n>", "--json", "name,state,bucket,link"))`.
   - Parse JSON with Jackson `ObjectMapper`, as `GhPullRequestIdentityLookup` does.
   - Map outputs following decision 6. An empty array, or the "no checks reported" stderr, maps to `NoChecks`.
   - Wire it in DI where `GhPullRequestIdentityLookup` is provided.
   - Every `Unavailable` mapping is a degradation, so it must emit a record per `../../../docs/observability-policy.md`, following the identity lookup's precedent.
8. **Watcher and monitor strategy** (new package `runtime-engine/.../slot/monitor/`)
   - `PullRequestCiWatcher` uses `PullRequestIdentityLookup` (PR number from the branch), `PullRequestChecksLookup`, the clock, the sleep function, and the interval, timeout, and grace parameters. It returns one of these outcomes:
     - `Passed`
     - `NoCiConfigured`
     - `Failed(failingChecks)`
     - `Blocked(reason)`, for timeout or unavailable.
   - `MonitorStrategy` and `MonitorOpus55Strategy` act on the watcher outcome:
     - `Passed` or `NoCiConfigured`: complete the phase.
     - `Failed`: request the backward edge to `monitor_fix`, carrying the failing check names and links as loop context. On exhaustion, the transition BLOCKs with the decision 8 reason.
     - `Blocked`: block with its reason.
   - `MonitorFixStrategy` and `MonitorFixOpus55Strategy` brief the agent with the failing checks and links. The agent reads logs (`gh run view <id> --log-failed`) and fixes the root cause in the working tree. It must not commit or push, because `commit_push` does that. It settles with uniform prose, or blocks with the reason when the failure is not fixable in the repository.
   - Model these strategies on the `slot/pullrequest/` strategies and on `commit_push`'s runtime-settled strategy.
9. **Bindings** (`runtime-engine/.../slot/skeleton/SkeletonStrategyBindings.kt`)
   - Add `MONITOR` to the `STANDALONE` and `PR` maps with `PhaseStrategyBinding.Fixed(MonitorStrategy.ID).withOpus(MonitorOpus55Strategy.ID)`. Add the `monitor_fix` binding where loop phases are bound.
   - Do not add it to `GOAL_CHILD`.
   - Register the new strategies wherever the PR strategies are registered. Implement locates this through the existing strategy registration.
10. **Contracts**
    - `../../../orchestration/contracts/workflow-state-schema.yaml`: add `monitor` to both `current_step_id` and `steps[].step_id`. Add `monitor_fix` too, if `implement_fix` appears there.
    - `../../../orchestration/contracts/feature-task-runtime-phase-output-schema.yaml`: add `monitor_fix` to the `uniformSettlement` `phase_id` enum (line 209). Do not add `monitor`, because it is runtime-settled like `commit_push`, which is absent from that enum.
11. **Docs.** Add `monitor` (with its fix loop, 3-attempt cap, and block behaviour) after `pr` in the phase lists in `../../../orchestration/workflow-contract/PLAYBOOK.md` and `runtime-kotlin/ARCHITECTURE.md`.
    - Edit only those lists and any adjacent loop table. Make no skill-source changes.
    - If skill text turns out to enumerate phases, update it in `../../../skills/skill-bill/content.md`. Leave install refresh to the operator: no `./install.sh` in this goal child.
12. **Existing pinned tests and fixtures.** Update them to the new phase set without weakening assertions:
    - Domain: `SkeletonDefinitionTest`, `FeatureTaskRuntimePhaseWorkflowDefinitionTest`, `FeatureTaskRuntimePhaseWorkflowDefinitionProjectionTest`, and `FeatureTaskRuntimePhaseWorkflowDefinitionTestSupport`.
    - Engine: `PhaseStrategyCompositionTest`, `PhaseModelProfileSelectionTest`, `FeatureTaskRuntimeExecutionPlanResolverTest`, `FeatureTaskExecutionPlanCreationTest`, and `PhasePullRequestRunTest`.
    - Fixtures: `TestPhaseStrategies.kt` and `TestExecutionPlan.kt`.
    - DI: `RuntimeFeatureTaskSlotProvidesTest`.
    - Slot baselines: regenerate `standalone` and `phase/pr` under `runtime-engine/src/test/resources/featuretask/slotbaseline/`, using the mechanism `SlotBaselinePaths.kt` uses. The goal-child baselines (`goal-child-build`, `goal-child-validate`) must come out unchanged. That is the regression check for the goal-child exclusion, so do not regenerate them blindly.
    - MCP golden: regenerate `runtime-mcp/src/test/resources/golden/mcp-tools-list.json` only if its `write_history` enums are phase-id enums that now include `monitor`.

### New tests (each names the bug it catches)

1. **Skeleton membership** (`SkeletonDefinitionTest`): `STANDALONE` and `PR` end with `MONITOR`, and `GOAL_CHILD` contains neither `PULL_REQUEST` nor `MONITOR`.
   - Bug caught: goal children wait forever on a PR they never open.
2. **Fix-loop cap** (transitions test beside `FeatureTaskRuntimePhaseWorkflowDefinitionTest`): a monitor failure routes to `monitor_fix`, whose successor is `commit_push`. Exactly 3 traversals are allowed. The 4th failure BLOCKs. The count survives a reload of persisted loop state.
   - Bugs caught: an off-by-one cap, a cap that resets on resume, and a fix that is never pushed.
3. **gh adapter** (`GhPullRequestChecksLookupTest`, with a fake `GhCommandRunner`), one test per branch:
   - Failing JSON with non-zero exit maps to `Reported` with `FAIL` entries, names, and links.
   - An auth error or unknown flag with non-JSON output maps to `Unavailable`. This covers the bug where a gh failure is read as CI passing.
   - An empty array maps to `NoChecks`.
4. **Watcher** (`PullRequestCiWatcherTest`, with a fake clock and sleep):
   - Pending then pass gives `Passed`.
   - Pending past the timeout gives `Blocked`, with the pending check names.
   - `NoChecks` beyond the grace period gives `NoCiConfigured`, while `NoChecks` followed by checks within the grace period is watched normally. This covers the bug of passing a fresh push before its checks register.

No tests for labels, constants, or strategy glue, beyond the pinned-test updates in task 12. Implement does not run build, check, or the suites. That belongs to the build and validate phases.

### Constraints

- Never use `relaxed = true` for mocks. Use `relaxUnitFun = true`.
- Typed results go through the existing failure model. Expected outcomes are results, not exceptions.
- Domain code must not import ports or `java.nio`.
- Test environment maps must be explicit and non-empty.
- Make no installer or install-sync commands in this goal child.
- Do not edit the parent spec or sibling bundle files.

## Next Path

Complete the goal and prepare its pull request.

## Spec Path

.feature-specs/SKILL-407-add-a-final-monitor-phase-to-the-skill-bill-workflow/spec_subtask_1_implement-the-requested-change.md
