# SKILL-402 - Standalone review slot

Issue key: SKILL-402
Implementation base: `base/SKILL-380-phase-slot-strategies`, as required by `../../../AGENTS.md`.
Origin: follow-up to SKILL-380 (`../done/SKILL-380-phase-slot-strategies`), filed in the SKILL-380 checkout. That bundle is complete. A second directory keyed SKILL-380 would match both specs.

## Outcome

`skill-bill phase review` and `skill-bill code-review` review a target and print the findings register. They do not edit files, stage paths, or create a commit.

This changes their documented review-and-fix behavior to report-only. A completed report exits 0 even when it requests changes. An execution or output-contract failure exits 1 and reports why the review is incomplete.

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

Declare one authoritative feature-run slot list of the nine slots that exist today, in today's order. `STANDALONE`, `GOAL_CHILD`, and `unselectedStepIds` derive from that list. Do not repeat it in separate declarations. `present_findings` never appears in a full run's plan, handoff, or fixture.

`SkeletonDefinition.REVIEW` becomes `listOf(PhaseSlot.STANDALONE_REVIEW)`, still `IN_MEMORY`. `phase review` and `skill-bill code-review` keep their CLI. `mode:` and `target:` stay. An omitted target is still uncommitted when the worktree is dirty, and `HEAD` when it is clean.

## Strategy

One new strategy package contains two standalone strategies, with ids `inline` and `delegated`, registered under `STANDALONE_REVIEW`. Neither constructs `CodeReviewSlot` or calls `InlineReviewStrategy` or `DelegatedReviewStrategy`. Reuse existing target resolution, findings parsing, and delegated launch components where their contracts fit. Do not copy the full-run repair behavior or introduce forwarding wrappers.

The `REVIEW` definition selects these strategies through the existing `PhaseStrategyBinding.ByFact` mechanism:

- `inline`, `auto`, and omitted mode run one agent session through `PhaseRunner`.
- `delegated` runs the existing multi-agent review through `ParallelCodeReviewRunner`. Its lanes do not edit.

Full-run strategy selection stays unchanged. Today `SkeletonStrategyBindings.sharedBindings` maps every review mode to the inline full-run strategy. This bundle does not change that routing.

The step policy is `mutating = false`, `fileMutating = false`, `extendsOwnedInventory = false`. There is no `review_fix` loop.

The prompt asks for the findings register and exactly one verdict line, `verdict: approved` or `verdict: changes_requested`. `changes_requested` when any Blocker or Major is present. An empty review explicitly declares no findings. The prompt tells the agent not to edit, stage, commit, amend, or reset, and not to launch either standalone review command. These restrictions apply to the delegated parent and its lanes too. Standalone prompts contain no fix-in-session instruction or promise of a runtime checkpoint.

The new step puts the register on `PhaseRunResult.reviewResult.output`. Both CLI entry points print it through their existing output handlers. A blocked run preserves any available register and prints the block reason without presenting a successful approval.

In-memory `recordReviewRun` still records the pass, as SKILL-380's review-run recording does. The durable state still records none for `code_review`.

## Review authority

`present_findings` uses an accepted review execution binding for target access and review-run recording. `PhaseAttemptRunHost.requireReviewOwner` currently admits only `review` in `CODE_REVIEW`; the new step needs explicit admission under its selected review role. The binding must reject writes from an unrelated step, a mismatched strategy, and a closed attempt. Do not bypass admission or expose raw run state to the new strategies.

`StrategyCapabilityBoundaryArchitectureTest` currently recognizes review consumers by the `codereview` package. Extend its ownership classification to the standalone review package with allowed and rejected synthetic fixtures. Non-review strategies still cannot reach review authority or raw `PhaseRunner` authority. This is an explicit addition of a review owner, not a general exemption for strategy packages. Apply architecture rules A1, A3, A5, and G7.

## Report completion and failure

The findings verdict and execution outcome are separate. A complete, valid report returns `PhaseRunResult.Completed` whether its verdict is `approved` or `changes_requested`. Findings never enter a repair loop or block completion by themselves.

Blank or truncated output, a malformed findings candidate, a missing, duplicate, or unknown verdict, and a verdict inconsistent with finding severity cannot produce successful approval. An empty register is valid only with an explicit no-findings declaration and `verdict: approved`. Do not use the current permissive `InlineReviewEnvelope.extractReviewVerdict` default as standalone output validation. Keep this stricter admission local to standalone review so full-run output behavior stays unchanged.

Failed launch, timeout, nonzero agent exit, and incomplete or failed delegated coverage produce a blocked result with exit 1. Preserve available findings and diagnostics. Cancellation propagates through the existing cancellation boundary. Expected output rejection uses the existing blocked outcome; failures that end the run follow the owner-declared typed failure contract. No failure becomes an empty approved report. Apply A7 and the observability policy.

## Documentation

Update `../../../docs/runtime-command-guidance.md` to describe both standalone entry points as report-only. Update the relevant sections of `skills/skill-bill/content.md` and any consumed review guidance that still instructs standalone review to fix findings. `runtime-kotlin/ARCHITECTURE.md` describes the new slot, the shared feature-run slot list, accepted review authority, and report completion semantics. Preserve the full-run repair contract in all three sources.

Author skill changes in `content.md`, never generated `SKILL.md`. Read `../../../docs/skill-source-generation.md` and run `./install.sh` if authored skill source or generated support-pointer behavior changes.

## Acceptance criteria

1. `PhaseSlot` order is the current nine wire values, then `standalone_review`. `standalone_review` owns only `present_findings`. Every step id, including `present_findings`, belongs to exactly one slot.
2. `SkeletonDefinition.REVIEW` contains only `standalone_review`. `STANDALONE` and `GOAL_CHILD` list the same slots they list today. Their derived transition declaration is unchanged.
3. `ResolvedPhaseExecutionPlan.unselectedStepIds` for a full run does not contain `present_findings`. Full-run workflow snapshots, phase records, handoffs, and prompt fixtures stay byte-identical.
4. `phase review` and `skill-bill code-review` launch only `present_findings`. They do not launch `review`, `verify_findings`, or `implement_fix`.
5. On a worktree containing staged and unstaged changes in the same tracked file plus an untracked file, both modes preserve index entries, HEAD, and tracked and untracked file bytes. Neither calls `stagePaths` or `commitSubtask`, on completion or execution failure.
6. `mode:delegated` uses `ParallelCodeReviewRunner` and still does not edit or commit. `inline`, `auto`, and omitted mode use one agent session.
7. A full run and a goal child retain their current strategy selection and `code_review` steps, including `review`, `verify_findings`, and `implement_fix`. Durable checkpoint commits for that slot stay on the durable `PhaseRunState`.
8. A valid report prints its register and exits 0 for both verdicts through both CLI entry points. Invalid or incomplete output and execution failures exit 1, preserve available findings, and explain the block. Neither outcome launches repair.
9. Only an active, accepted standalone review binding can access the pinned target and record its review pass. Unrelated steps, mismatched strategies, and closed attempts retain their rejection behavior. Architecture guards admit the new review consumers and still reject non-review access.
10. Runtime command guidance, authored skill guidance, and runtime architecture describe standalone report-only behavior, its exit semantics, and unchanged full-run repair behavior. Consumed standalone prompts contain no conflicting fix or checkpoint instruction.

## Constraints

- Read `../../../runtime-kotlin/ARCHITECTURE.md`, `docs/code-principles.md`, and `AGENTS.md` before editing.
- Kotlin under `runtime-kotlin` has no `//` comments and no non-KDoc block comments. KDoc only on interfaces.
- The new slot adds one enum constant, two strategy registrations, and one mode-based selection binding for `REVIEW`. No second run loop and no branch on definition id inside `InlineReviewStrategy` or `DelegatedReviewStrategy`.
- Do not make `InMemoryPhaseRunCheckpoints.commitSubtask` succeed or no-op. A phase run that reaches a checkpoint commit must still fail. This slot must not reach it.
- Full-run review behaviour stays. This spec does not change fix-in-session, `verify_findings`, `implement_fix`, or the `review_fix` cap.

## Non-goals

- Stopping a full run from fixing findings or from writing its checkpoint commit.
- A review mode that both reports and fixes.
- New operator flags.
- Resume for phase runs.
- Replacing the structured findings register with prose.

## Validation

- Replace the `PhaseReviewRunTest` cases that require `implement_fix` to edit a file. Exercise each mode against a real temporary Git repository with staged and unstaged edits in the same tracked file plus an untracked file. Compare index entries, HEAD, and file bytes before and after a valid changes-requested report and an execution failure. Assert that staging and checkpoint capabilities were never invoked. This catches the dirty-index regression that scripted clean-worktree tests missed.
- Cover approved and changes-requested reports and execution/output failures through both CLI output handlers. Include malformed candidates, absent or conflicting verdicts, blank or truncated output, timeout, and incomplete delegated coverage. Parameterize cases that exercise the same boundary. These tests catch false approval and incorrect exit status without duplicating parser internals.
- Extend `SkeletonDefinitionTest`, execution-plan tests, and strategy-selection tests to prove criteria 1 through 4, 6, and 7. Keep existing full-run snapshots and prompt fixtures unchanged. The authoritative feature-run list must have one production declaration.
- Add accepted-binding tests for criterion 9 and allowed/rejected fixtures through `StrategyCapabilityBoundaryArchitectureTest`'s real entry point. Guards, baselines, and exemptions must not weaken.
- Review the composed standalone prompts and documentation for criterion 10. This is a review-only check for conflicting prose. Report implementation review against the architecture-guidelines section 5 checklist with rule IDs.
- Run all required repository checks discovered from `../../../AGENTS.md`, build configuration, scripts, and CI. Include `cd runtime-kotlin && ./gradlew check`; compilation alone does not complete validation. Run `./install.sh` when required by the source-generation contract.
- Write few tests over observable boundaries. Parameterize repeated cases and avoid tests that mirror implementation structure.
