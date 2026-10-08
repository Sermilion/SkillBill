# SKILL-408 No-change goal outcome with evidence

## Problem

If implementation decides the repository needs no change, it ends with an empty diff. The audit
then reads every acceptance criterion as unmet and keeps looping through `audit_implement_fix`
until the retries or the wall-clock cap run out. The block reason it reports is the generic
"Unidentified remaining criterion." (from the remaining-criteria prose parser in
`AcceptanceAuditRemainingCriteria.kt`), which does not explain anything. On WE-5006 in the webapp
repo this took about an hour: 2 audit attempts, 2 blocks, 0 files changed. The subtask plan had
predicted a backend-only fault, but the runtime had no way to finish on that outcome.

## Intended Outcome

Implementation can claim a structured no-change outcome and back it with evidence. The audit
checks that evidence instead of looking at the diff. If the audit confirms the claim, the goal
pauses for the operator and shows a report. If the evidence is missing or the audit rejects it,
the normal repair loop runs with the rejection reason. The operator then accepts, which completes
the goal as `completed_no_change` with the evidence persisted and no commit, push or PR. The
operator can instead reject, either with `retry_fix` plus instructions or with `abandon_subtask`.
`goal status` and telemetry report the outcome and its reason.

## Shared Contract (both subtasks use these names exactly)

- **Claim.** Implementation outputs (`implement` and `audit_implement_fix`) may carry
  `produced_outputs.no_change`. It is an object with these fields:
  - `reason`: one of `out_of_repo`, `already_satisfied`, `not_reproducible`.
  - `criteria`: a list of `{criterion_id, verdict, evidence}`. It needs one entry per criterion
    id in the run's `AcceptanceAuditCatalog`. `verdict` takes one of the three reason words, and
    `evidence` must be non-blank prose.
  - `citations`: a non-empty list of `path:line` or `path:start-end` strings for the code
    examined.
  - `boundary_trace`: non-blank prose that shows the boundary, for example "the webapp sends only
    ticket IDs; the server renders the report".
  - `owning_system`: optional. Expected for `out_of_repo` when the owner is known.
  - `suggested_handoff`: optional. When it is absent, the runtime derives one from `reason` and
    `owning_system`.

  The phase still settles with prose `produced_outputs.value` and status `completed`. Parsing is
  lenient about case and separators (`Out-Of-Repo` reads as `out_of_repo`) and strict about which
  fields are present.
- **Audit verdict words.** `no_change_confirmed` and `no_change_rejected`. A rejection carries its
  reasons in the prose value.
- **Child pause artifact.** `record_kind: "no_change_pause"`, read by a strict `fromArtifactMap`
  that checks the exact field set. Fields: `record_kind`, `reason`, `criteria`, `citations`,
  `boundary_trace`, `owning_system` (nullable), `suggested_handoff`, `audit_summary`,
  `operator_decision` (null, `accept_and_advance`, `retry_fix` or `abandon_subtask`),
  `operator_instructions` (nullable).
- **Goal stop reason.** `GoalRunnerStopReason.AWAITING_NO_CHANGE_DECISION`, wire value
  `awaiting_no_change_decision`. Telemetry `goal_finished` status is `paused`.
- **Goal terminal status.** `GoalRunnerTerminalStatus.COMPLETED_NO_CHANGE`, wire value
  `completed_no_change`, reported through a new `GoalRunnerRunReport` variant that has no PR
  fields. `Completed` keeps meaning "a PR exists".
- **Telemetry.** `goalRunnerStopReasonEnum` gains `awaiting_no_change_decision`.
  `goalFinishedStatusEnum` gains `completed_no_change`. `goal_finished` and `goal_issue_finished`
  gain an optional `no_change_reason` that takes the three reason words. The `contract_version`
  const stays `"1.12.0"`.
- **Operator decision wire values.** These are the existing `GoalSubtaskOperatorDecision` values,
  unchanged. The `goal operator-decision` command gains an `--instructions` option.
- **CLI pause line.** `awaiting_no_change_decision: ...`, in the same line format `OperationCommand`
  uses for `awaiting_confirmation`.

## Decisions (settled from the preplan digest)

1. The claim goes in `produced_outputs` and needs no phase-output contract bump: `produced_outputs`
   is an open object, and the const stays `"0.7"`. The schema description documents the claim. If
   the implement `allOf` branch closes `produced_outputs`, `no_change` is added there as an
   optional property.
2. The pause gets a new stop reason rather than overloading `AWAITING_OPERATOR_DECISION`, so
   status and telemetry can tell it apart from a review pause.
3. The pause lives in a child-workflow artifact. It does not live in the review-state `PAUSED`
   disposition, so `goal-subtask-review-state-schema.yaml` and `GoalSubtaskReviewState` stay
   unchanged. Decisions are routed by checking for an undecided `no_change_pause` artifact. Every
   other child-workflow decision keeps today's review-remediation rejection.
4. The evidence persists in the child-workflow artifact store, not in spec scratch, because
   `deleteGoalSpecScratchOnSuccess` deletes scratch. No file is written to the worktree, since a
   no-change goal makes no commit.
5. On accept, the subtask records `completed_no_change` and the goal moves to the next pending
   subtask. The goal finishes as `completed_no_change` only when it produced no subtask commits.
   A goal with commits finalizes and opens a PR as it does today.
6. The pause is a runner stop and not an in-flight wait, so no idle or wall-clock timer runs
   while it waits. Time spent paused does not count toward the wall-clock cap of a resumed run.
7. A claim that comes with changed files in the step is rejected, so uncommitted edits cannot be
   silently dropped by a no-change acceptance.
8. A claim skips `simplify`. A branch-diff simplify could otherwise edit files under a claim of no
   change.
9. The runtime checks the claim's structure deterministically. The audit agent checks its
   substance, reading the cited `path:line` locations and the trace without writing anything.
   The runtime does no filesystem checks on citations.

## Subtasks

1. `spec_subtask_1_child_no_change_claim_and_pause.md` covers the claim, the evidence audit, the
   routing that skips the repair loop on a confirmed claim, the pause artifact, the stop reason,
   the stop report, and pause telemetry.
2. `spec_subtask_2_operator_no_change_decision.md` covers the operator decisions (accept,
   `retry_fix` with instructions, abandon), the `completed_no_change` terminal path with no
   commit, push or PR, persisted evidence, status, completion telemetry, and the dispatcher.

The split exists because a single implement pass cannot carry both halves to a reviewable end.
The child-workflow half alone has three unresolved runtime facts to settle (listed under
Assumptions). The first subtask also ships value on its own: it ends the hour-long repair loop
with an explicit pause.

## Acceptance Criteria

1. An implementation output carrying a structurally valid `no_change` claim that the audit
   confirms stops the run with a persisted `no_change_pause` artifact. It never enters
   `audit_implement_fix` and leaves the repair-retry count unchanged (subtask 1).
2. A claim that is missing required evidence, or that the audit rejects, routes to
   `audit_implement_fix` with the specific rejection reason as the fix input (subtask 1).
3. A confirmed claim pauses the goal with stop reason `awaiting_no_change_decision`. The stop
   report shows the reason, the verdict and evidence for each criterion, the citations, the
   boundary trace, the owning system when present, and a suggested handoff (subtask 1).
4. Operator `accept_and_advance` on a no-change pause completes a goal that has no commits as
   `completed_no_change`, persists the evidence, and calls no commit, push or PR code. `retry_fix`
   carries operator instructions into a fix round. `abandon_subtask` abandons the subtask
   (subtask 2).
5. `goal status` and the `goal_finished` and `goal_issue_finished` telemetry report the no-change
   pause and the `completed_no_change` outcome with `no_change_reason` (both subtasks).
6. The `/skill-bill` dispatcher shows the no-change report, asks the user once, and runs
   `goal operator-decision` with the answer (subtask 2).

## Non-Goals

- Completing a goal without the operator's decision.
- Writing to the tracker, such as commenting on or reassigning the issue.
- Investigating other repositories, including resolving or checking citations that point outside
  this worktree.
- Changing the review-remediation operator-decision rejection or the review-state schema.
- Bumping the phase-output `"0.7"` or the telemetry `"1.12.0"` contract-version consts.

## Constraints

- Mocks use `relaxUnitFun = true`, never `relaxed = true`.
- Base state: the working tree has uncommitted SKILL-407 edits to
  `orchestration/contracts/feature-task-runtime-phase-output-schema.yaml`,
  `orchestration/contracts/telemetry-event-schema.yaml`,
  `orchestration/contracts/workflow-state-schema.yaml`,
  `FeatureTaskRuntimeVerdict.kt`, `FeatureTaskRuntimePhaseWorkflowGraph.kt`,
  `FeatureTaskRuntimePhaseWorkflowTransitions.kt` and
  `FeatureTaskRuntimeRunLoopBackwardEdge.kt`. When one of these files is in the tree a subtask
  runs on, keep every existing edit and add only this feature's lines.
- Every new artifact field set is the same on the write side and the strict read side.

## Execution Rule

Every subtask runs on whatever tree is current and declares no manifest dependency. Each subtask
creates any Shared Contract element its criteria need when that element is absent, and keeps it
unchanged when it is present. When both subtasks touch a shared file, the one that lands second
keeps both edits.

## Risks

- Changing `GoalOperatorDecisionService.record` could also change review-remediation rejections.
  An existing test guards that path, and it must stay green.
- The claim is self-reported. The deterministic structure check plus the audit agent's read-only
  check of the citations are the controls.
- The mutating-phase gates might reject an implement completion with zero changed files.
  Subtask 1 has to find and handle that.

## Assumptions for implement to confirm

1. Where idle and wall-clock timeouts are enforced has not been located. Decision 6 assumes the
   pause ends the runner invocation.
2. A reject site for a missing `reconciled_state` on a mutating implement has not been found. If
   one exists, it must accept a claim-bearing output with zero changed files.
3. Whether the checkpoint and transition code accept a completed implement with zero changed files
   has not been read.
4. How `GoalRunnerStatusProjectionAssembler.kt` carries the stop reason and terminal status into
   `goal status` has not been read.
5. `GoalRunnerAcceptanceCoordinator.accept` resolves commit evidence. The no-change path is
   assumed to bypass it.
6. The repository default branch is taken to be `main`, which the manifest uses as
   `base_branch`. The SKILL-407 edits listed under Constraints are uncommitted on
   `feat/SKILL-407-…`. If they have not landed when the goal launches, the operator must land
   them or set `base_branch` to that branch.

## Validation Strategy

The validate phase runs `./gradlew check` (with `--no-configuration-cache` if Spotless reports a
stale configuration cache). This covers the unit tests, the repo tests (including
`DispatcherSkillInstallRepoTest`), schema and contract checks, and agent-config validation.
Implement, audit and review do not run builds or tests.

## Next Path

```bash
skill-bill goal SKILL-408
```
