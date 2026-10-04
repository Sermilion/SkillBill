# SKILL-402 - Standalone review slot

Issue key: SKILL-402
Origin: follow-up to SKILL-380 (`../done/SKILL-380-phase-slot-strategies`), filed in the SKILL-380 checkout. That bundle is complete. A second directory keyed SKILL-380 would match both specs.

## Outcome

`skill-bill phase review` and `skill-bill code-review` review a target and print the findings register. They do not edit files, stage paths, or create a commit.

They run a new phase slot, `standalone_review`, with one step, `present_findings`. They do not run `PhaseSlot.CODE_REVIEW`.

A full feature run and a goal child keep `code_review` (`review`, `verify_findings`, `implement_fix`), including its fixes and its durable checkpoint commit.

## What failed

On 2026-10-04, `skill-bill phase review` in `readian-android` on `feat/NEWS-152-mobile-topic-onboarding-redesign` exited 1 after the review agent finished. The worktree was already dirty. The last lines were:

```
Feature-task-runtime checkpoint has no durable file manifest for any writing phase; the whole working-tree delta is treated as this workflow's own writes.
Feature-task-runtime checkpoint adopted owned path(s) '...' whose index or working-tree content diverged from what this run wrote. The working-tree content is committed to 'feat/NEWS-152-mobile-topic-onboarding-redesign' as this workflow's work rather than blocking the run.
An in-memory phase run keeps no durable state and cannot write a subtask checkpoint commit; run the full feature-task workflow instead.
```

HEAD stayed at `54aff03d`. The index was left staged. No new commit was created.

`SkeletonDefinition.REVIEW` is `listOf(PhaseSlot.CODE_REVIEW)` with `SkeletonRunStateKind.IN_MEMORY`. That slot always runs all three steps. `InlineReviewStrategy` and `DelegatedReviewStrategy` both delegate to `CodeReviewSlot`, so both modes run `implement_fix`.

`ImplementFixStep.policy` sets `fileMutating` and `extendsOwnedInventory`. After that step the run loop reaches `FeatureTaskRuntimeRunLoopRepairReceipt.stageAndWriteCheckpoint`, which stages owned paths and calls `FeatureTaskRuntimeRunLoopCheckpoint.writeSubtaskCommit`. That calls `PhaseRunCheckpoints.commitSubtask`.

`InMemoryPhaseRunCheckpoints.commitSubtask` throws `InMemoryPhaseRunUnsupportedError("write a subtask checkpoint commit")`. Staging happens before the throw, so a failed review can change the index.

The empty-manifest warning comes from `FeatureTaskRuntimeRunLoopCheckpoint.writingPhaseIntroducedPaths`. An in-memory run has no phase records. When the worktree delta is non-empty, the checkpoint treats every dirty path as the phase's own writes, then adopts paths whose content diverges (`FeatureTaskRuntimeCheckpointScope`).

`InlineReviewPass.policy` is also `fileMutating`, and `InlineReviewDirective` tells the agent to fix every Blocker and Major in the same session and says the runtime owns the review checkpoint. That is the full-run contract. It is the wrong contract for a phase review.

`PhaseReviewRunTest` (`inline phase review finds, verifies, and fixes without a commit...`) drives a scripted runner in a clean temp repo. It requires `implement_fix` to edit the file and asserts no commit. It does not start from a dirty operator worktree, so it never hits this checkpoint.

## Slot shape

SKILL-380 makes a slot run every step it owns, and a strategy does not branch on which definition selected it. `code_review` therefore cannot sometimes stop after `review`. A phase review needs its own slot.

Add `PhaseSlot.STANDALONE_REVIEW` (`wireValue` `standalone_review`) after `PULL_REQUEST`. Its only step is a new id, `present_findings`, owned by that slot alone. `FeatureTaskRuntimePhaseIds.all` includes it. `PhaseSlot.slotForStep("present_findings")` returns this slot.

`FeatureTaskRuntimePhaseWorkflowDefinition.transitions` stays the graph SKILL-380 shipped. `present_findings` has no entry gate and no backward edge. The `review` definition's forward path is that one step.

`SkeletonDefinition.STANDALONE` is `PhaseSlot.entries` today, and `GOAL_CHILD` is `PhaseSlot.entries` without `PULL_REQUEST`. `ResolvedPhaseExecutionPlan.unselectedStepIds` is `PhaseSlot.entries.flatMap(PhaseSlot::steps) - selectedStepIds`. Leaving those as they are would pull `present_findings` into every full run's unselected set and could change workflow snapshots.

Replace both with an explicit feature-run slot list of the nine slots that exist today, in today's order. `unselectedStepIds` is computed from that list. `present_findings` never appears in a full run's plan, handoff, or fixture.

`SkeletonDefinition.REVIEW` becomes `listOf(PhaseSlot.STANDALONE_REVIEW)`, still `IN_MEMORY`. `phase review` and `skill-bill code-review` keep their CLI. `mode:` and `target:` stay. An omitted target is still uncommitted when the worktree is dirty, and `HEAD` when it is clean.

## Strategy

One new strategy package. It does not construct `CodeReviewSlot` and does not call `InlineReviewStrategy` or `DelegatedReviewStrategy`.

Selection matches review mode the way `code_review` does:

- `inline`, `auto`, and omitted mode run one agent session through `PhaseRunner`.
- `delegated` runs the existing multi-agent review through `ParallelCodeReviewRunner`. Its lanes do not edit.

The step policy is `mutating = false`, `fileMutating = false`, `extendsOwnedInventory = false`. There is no `review_fix` loop.

The prompt asks for the findings register and one verdict line, `verdict: approved` or `verdict: changes_requested`. `changes_requested` when any Blocker or Major is present. The prompt tells the agent not to edit, stage, commit, amend, or reset, and not to launch `skill-bill phase review`.

The CLI already prints `PhaseRunResult.reviewResult.output` from `writePhaseResult`. The new step puts the register on that field. A blocked run prints the same register, then the block reason.

In-memory `recordReviewRun` still records the pass, as SKILL-380's review-run recording does. The durable state still records none for `code_review`.

## Acceptance criteria

1. `PhaseSlot` order is the current nine wire values, then `standalone_review`. `standalone_review` owns only `present_findings`. Every step id, including `present_findings`, belongs to exactly one slot.
2. `SkeletonDefinition.REVIEW` contains only `standalone_review`. `STANDALONE` and `GOAL_CHILD` list the same slots they list today. Their derived transition declaration is unchanged.
3. `ResolvedPhaseExecutionPlan.unselectedStepIds` for a full run does not contain `present_findings`. Full-run workflow snapshots, phase records, handoffs, and prompt fixtures stay byte-identical.
4. `phase review` and `skill-bill code-review` launch only `present_findings`. They do not launch `review`, `verify_findings`, or `implement_fix`.
5. On a dirty worktree, both modes leave the index and HEAD unchanged, leave reviewed file bytes unchanged, and never call `stagePaths` or `commitSubtask`. The process prints the findings register and exits 0 when the step completes.
6. `mode:delegated` uses `ParallelCodeReviewRunner` and still does not edit or commit. `inline`, `auto`, and omitted mode use one agent session.
7. A full run and a goal child still select `inline` or `delegated` on `code_review` and still run `review`, `verify_findings`, and `implement_fix`. Durable checkpoint commits for that slot stay on the durable `PhaseRunState`.
8. `runtime-kotlin/ARCHITECTURE.md` describes `standalone_review`, the feature-run slot list, and that `phase review` only reports findings.

## Constraints

- Read `runtime-kotlin/ARCHITECTURE.md`, `docs/code-principles.md`, and `AGENTS.md` before editing.
- Kotlin under `runtime-kotlin` has no `//` comments and no non-KDoc block comments. KDoc only on interfaces.
- A new slot is one enum constant, one strategy registration, and one selection entry. No second run loop and no branch on definition id inside `InlineReviewStrategy` or `DelegatedReviewStrategy`.
- Do not make `InMemoryPhaseRunCheckpoints.commitSubtask` succeed or no-op. A phase run that reaches a checkpoint commit must still fail. This slot must not reach it.
- Full-run review behaviour stays. This spec does not change fix-in-session, `verify_findings`, `implement_fix`, or the `review_fix` cap.

## Non-goals

- Stopping a full run from fixing findings or from writing its checkpoint commit.
- A review mode that both reports and fixes.
- New operator flags.
- Resume for phase runs.
- Replacing the structured findings register with prose.

## Validation

- Replace the `PhaseReviewRunTest` cases that require `implement_fix` to edit a file. Add one dirty-worktree test per mode that fails if the index, HEAD, or a reviewed file changes.
- Assert `SkeletonDefinition.STANDALONE` and `GOAL_CHILD` declarations against the current forward step list.
- `cd runtime-kotlin && ./gradlew check` for the modules this touches.
- One test per rule. Do not add a sibling that repeats the same branch with different literals.
