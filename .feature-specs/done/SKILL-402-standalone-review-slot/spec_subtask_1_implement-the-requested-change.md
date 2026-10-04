# SKILL-402 Subtask 1 - Implement the requested change

Parent spec: [.feature-specs/SKILL-402-standalone-review-slot/spec.md](spec.md)
Issue key: SKILL-402

## Scope

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


Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection. Only an explicit unresolved tracker reference without requirements needs lookup through its connected tracker before planning. Use the returned requirements, not the URL title. If that lookup fails, block with the returned reason before implementation; never infer or substitute requirements.

## Acceptance Criteria

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

## Non-Goals

- None

## Dependency Notes

Depends on: none
The full goal owns planning and execution of the supplied requirements.

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.

## Next Path

Complete the goal and prepare its pull request.

## Spec Path

.feature-specs/SKILL-402-standalone-review-slot/spec_subtask_1_implement-the-requested-change.md

## Implementation Details

This plan uses only the supplied upstream preplan digest. AC-001 through AC-010 refer to the unchanged acceptance criteria above. Execute the tasks in order within this existing subtask. There are no dependencies or unresolved product questions and no new decomposition.

### 1. Separate the operator slot from feature-run composition

Serves AC-001, AC-002, AC-003, and AC-007.

In `../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime`, append `STANDALONE_REVIEW` to `model/skeleton/PhaseSlot.kt`, with wire value `standalone_review` and sole step `present_findings`. Add the step to `model/core/FeatureTaskRuntimePhaseIds.kt` and expose it through `phase/task/FeatureTaskRuntimePhaseWorkflowDefinition.kt`. Retain unique step ownership and the existing canonical transition graph, entry gates, and backward edges.

Declare the current nine feature-run slots once as an explicit ordered list in the domain skeleton package. Derive `SkeletonDefinition.STANDALONE`, `GOAL_CHILD`, and `ResolvedPhaseExecutionPlan.unselectedStepIds` from that list. Preserve the existing semantic revisions and slot order. Change only `SkeletonDefinition.REVIEW` to the new slot, retaining `IN_MEMORY`. Let `SkeletonDefinitionTransitions` derive its one-step forward path without repair edges. Do not add definition-specific traversal branches.

Test obligations: extend `PhaseSlotTest`, `SkeletonDefinitionTest`, and `ResolvedPhaseExecutionPlanImmutabilityTest` at their digest-listed domain paths. Catch the realistic bugs where enum growth silently adds operator review to full runs, duplicate step ownership misroutes dispatch, or a full-run skipped-step projection includes `present_findings`. Retain exact full-run transition assertions and replace only the standalone review repair-loop expectation. These are contract checks, not tests of private list construction.

### 2. Admit standalone review through the existing accepted binding

Serves AC-004, AC-005, and AC-009.

In `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/attempt/PhaseAttemptRunHost.kt`, extend `requireReviewOwner()` to admit the exact `present_findings` and `STANDALONE_REVIEW` pair under the selected review role. Preserve the existing `review` and `CODE_REVIEW` admission. In `runloop/state/FeatureTaskRuntimeRunLoopStepBindings.kt`, admit the new slot's declared review role without granting it verification or repair roles.

Use `PhaseReviewStepBinding` for pinned-target access and pass recording. Preserve coordinator admission, run and request identity, selected strategy and policy checks, required-write rejection, and binding closure. Strategies receive accepted capabilities and fitting runner dependencies, never raw run state, checkpoints, Git writers, or composition containers. Do not call `amendReviewRemediationCheckpoint`.

Extend `StrategyCapabilityTransitiveGraph.reviewConsumer(path)` to recognize the exact new `slot.standalonereview` package. Keep raw-state prohibitions and non-review rejection intact. No baseline growth or blanket strategy exemption is allowed.

Test obligations: extend the `RequiredPhasePersistenceTest` pattern to prove accepted standalone target access and pass recording, with rejection for unrelated steps, mismatched strategies, and closed bindings. Catch unauthorized review-state access that would otherwise bypass active-attempt checks. Extend `StrategyCapabilityBoundaryArchitectureTest` through `violations(sources, roots)` with allowed standalone and rejected non-review synthetic sources. This catches transitive capability leakage even when runtime tests cover only valid dispatch.

### 3. Implement local strict report admission and the inline strategy

Serves AC-004, AC-005, AC-006, AC-008, and AC-009.

Create the standalone strategy package under the engine slot boundary, containing the two strategies and local report admission. Implement the `inline` strategy for only `present_findings`, explicitly selecting the review execution binding. Set `mutating`, `fileMutating`, and `extendsOwnedInventory` to false. Follow the existing start, retained-output, completion, and block lifecycle through the accepted binding.

Reuse `ReviewTargetResolver.resolve` and repository observations for dirty/clean default resolution and commit validation. Pin the resolved target through the binding. Preserve explicit staged, unstaged, PR, revision, and supplied-diff targets. Reuse fitting target preparation without durable goal-pass reservations, owned-delta remediation, or checkpoint machinery.

Launch one session through `PhaseRunner` with the new step identity. Compose separate standalone guidance requesting the register and exactly one canonical verdict line. Forbid editing, staging, committing, amending, resetting, and recursive standalone review commands. Preserve the existing full-run directive resources byte for byte. Do not construct `CodeReviewSlot`, invoke existing repair strategies, or install review-loop rules. Keep the in-memory checkpoint rejection unchanged.

Reuse `ParallelReviewFindingParser.parse` for findings and citation diagnostics. Require exactly one `verdict: approved` or `verdict: changes_requested`. Block malformed candidates and blank or truncated output, missing, duplicate, or unknown verdicts, and severity contradictions. Require `changes_requested` for any Blocker or Major. Permit Minor-only approval. Use `NO_FINDINGS` as the canonical explicit empty declaration, valid only with approval. Do not reuse permissive verdict defaults or nonblank-output success admission.

Retain admitted findings, raw available output, and captured process diagnostics before deciding completion. Record the available result through the accepted binding before an output or execution block. Valid reports complete for either verdict. Launch refusal, timeout, nonzero exit, truncation, and unusable output block without repair. Expected rejections are values or blocked outcomes. Terminal runtime failures use owner-declared codes and `SkillBillRuntimeException`; cancellation propagates. Emit bounded degradation records under the observability policy and never turn an exception into an empty approved report.

Test obligations: add a parameterized standalone admission boundary test for false approval caused by malformed candidates, verdict defects, empty-report defects, severity disagreement, or truncation. Include valid approval, Minor-only approval, and changes-requested reports without duplicating parser internals. Failure cases must assert retained findings and block reasons. Execution failure coverage belongs to task 6's run-level fixture.

### 4. Use existing delegated execution with a standalone report contract

Serves AC-005, AC-006, AC-008, and AC-010.

Implement the `delegated` strategy in the same engine package using `ParallelCodeReviewRunner`. At `../../../runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review`, add an explicit request-owned standalone report contract to `model/ParallelCodeReviewModels.kt` and consume it in `parallel/runner/ParallelCodeReviewRunnerParentPrompt.kt` and `ParallelCodeReviewRunnerLaneLaunch.kt`.

The standalone parent requests the register and canonical verdict and passes all read-only restrictions to lanes. Remove recursive standalone commands from this selected prompt contract. Preserve the default full-run prompt bytes, provider-specific launch components, specialist attribution, and refusal without fallback. Do not create another delegation framework or silently substitute inline execution.

Preserve merged findings, parent status, coverage, integration, accounting, citation diagnostics, and session metadata from `ParallelCodeReviewRunOutcome`. Inspect clean selected-lane coverage and review disposition as well as parent process success. Preserve available report data before blocking planning failure, failed or incomplete coverage, or report rejection. Keep existing result assembly and legacy soft admission unchanged for other consumers; apply stricter admission only in standalone review.

Test obligations: cover a successful parent with incomplete selected-lane coverage, which must block while retaining available findings. Prove the standalone request reaches the existing delegated runner and its parent/lane prompts carry the report restrictions. Preserve existing default prompt compatibility checks. Task 6 supplies delegated worktree-preservation coverage; do not duplicate it here.

### 5. Wire selection and preserve CLI target and result semantics

Serves AC-003, AC-004, AC-006, AC-007, and AC-008.

In engine `slot/skeleton/SkeletonStrategyBindings.kt`, move only the `REVIEW` binding to the new slot and strategies through `ByFact`. Explicit delegated mode selects delegated; inline, auto, and omitted mode select inline. Preserve `sharedBindings()` and every existing full-run strategy registration. Add exactly two standalone registrations and fitting runner associations in `../../../runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/featuretask/RuntimeFeatureTaskSlotProvides.kt`. Let `PhaseStrategyLookup` use normal definition traversal.

In `../../../runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/kernel/cli/StandaloneCodeReviewTarget.kt` and `cli/codereview/CodeReviewCommand.kt`, preserve omitted targets as null until engine dirty/clean resolution. Keep explicit scope, paired revisions, named targets, and conflict rejection unchanged. Retain the operator CLI and add no flags.

Keep validity decisions in the engine and rendering and exit mapping in `cli/phase/PhaseCommand.kt` and `cli/codereview/CodeReviewCommand.kt`. Both handlers print completed registers and exit 0 for either verdict. Blocked reports print available findings and an explanatory block with exit 1, without unqualified successful approval or repair. Update code-review help text that advertises fixing.

Test obligations: update `PhaseStrategyCompositionTest`, `PhaseStrategyRegistryTest`, `PhaseStrategyTraversalTest`, and `RuntimeFeatureTaskSlotProvidesTest` to catch missing production registration or incorrect mode routing. Assert unchanged full-run and goal-child selection and repair steps. Extend `CodeReviewPhaseRequestTest` and `StandaloneCodeReviewTargetTest` for omitted dirty/clean resolution and preserved explicit targets. Exercise both actual CLI reporting paths using existing `PhaseRunErrorMappingTest` and `CliCodeReviewDriverRuntimeTest` support, covering both valid verdicts and blocked reports with retained findings. This catches exit-code and output loss bugs without mock-only interaction assertions.

### 6. Protect the dirty worktree regression and full-run compatibility

Serves AC-003, AC-004, AC-005, AC-006, AC-007, and AC-008.

Replace repair-required cases in engine `phaserun/PhaseReviewRunTest.kt`. Use the existing `PhaseRunTestSupport`, `ReviewPhaseRunners`, and `RecordingWorkflowGitOperations` alongside a real temporary Git repository. Start with staged and unstaged edits in the same tracked file plus an untracked file. Parameterize inline and delegated mode for a valid changes-requested report and an execution failure. Compare HEAD, index entries, tracked bytes, and untracked bytes before and after each run. Assert zero `stagePaths` and `commitSubtask` calls, only `present_findings` dispatch, and retained failure findings where available. The concrete bug is the original failed review staging an operator's dirty paths before the in-memory checkpoint throws.

Keep durable full-run review coverage proving fixes and checkpoint ownership on `PhaseRunState`. Preserve real past-bug regressions and governed parity coverage. Update only the operator-review capture script and empty-report fixture in `SlotBaselinePhaseRunCapture.kt` and related baseline tests. Retain existing resource paths. Full-run `standalone`, `goal-child-build`, and `goal-child-validate` workflow snapshots, records, handoffs, and prompts remain byte-identical; never regenerate those captures to excuse drift. Keep shared full-run approval fixtures unchanged.

Test obligations: the real Git matrix above proves data preservation across success and failure. Existing `SlotBaselineFixtureTest` and `SlotBaselineCaptureTest` remain the compatibility boundary for full-run drift. No separate tests are needed for trivial forwarding or private helper structure.

### 7. Reconcile authored and consumed guidance

Serves AC-010 and protects AC-003 and AC-007.

Update `../../../docs/runtime-command-guidance.md`, the relevant sections of `skills/skill-bill/content.md`, and `runtime-kotlin/ARCHITECTURE.md` to describe the standalone slot, explicit feature-run list, accepted review authority, report-only execution, verdict-independent successful exit, and blocked exit semantics. Preserve full-run verification, fix-in-session behavior, remediation, and durable checkpoints.

Reconcile standalone caller instructions in `../../../orchestration/review-orchestrator/PLAYBOOK.md`, `orchestration/review-delegation/PLAYBOOK.md`, and `orchestration/review-orchestrator/specialist-contract.md`. Recommendations may describe fixes; standalone instructions must not apply them or launch validation after fixing. Inspect the composed inline, delegated parent, and lane guidance for contradictory fix or checkpoint instructions. Leave full-run `review-directive.md` and `inline-review-directive.md` resource bytes unchanged. Author `content.md` only, never generated skills or pointers.

Test obligations: no new prose-mirroring tests. Review the consumed composed prompts and documentation against AC-010. Retain existing validator and prompt parity checks. Installation remains with the parent runtime after authored guidance changes and is outside this child plan's executable tasks.

### 8. Hand off evidence to the owning later phases

Serves all acceptance criteria.

Implement produces the repository end states and test changes above. It must first read the applicable architecture, code-principles, runtime command, source-generation, and repository instructions required for its edits. This planning phase does not reread them or rediscover the checkout. Apply A1, A3, A5, A7, and G7, accepted capability ownership, canonical wire vocabulary, dependency direction, and the Kotlin comment restrictions. Review runs the full architecture-guidelines section 5 checklist and cites rule IDs. Audit checks every criterion against the resulting code and evidence.

Only validate executes tests and the full required repository checks. Its digest-established obligations include root runtime Gradle check, strict agnix validation, `../../../scripts/validate_agent_configs`, the affected boundary tests, unchanged full-run baseline proof, and composed standalone prompt inspection. Only build, if scheduled by the runtime, owns compile/buildability commands. No command execution occurred during planning. Validation may repair production wiring, test setup, formatting, or lint as needed while preserving behavior, assertions, and architecture rules. History, commit, PR, and installation remain with their owning phases or the parent runtime. No child install refresh is scheduled here.

### Decisions, assumptions, and rollout constraints

The digest supplies enough evidence for every product choice. Use the explicit nine-slot list, `slot.standalonereview`, strict local admission, `NO_FINDINGS`, and the request-owned delegated prompt contract. Apply omitted-target dirty/clean behavior to both CLI entry points despite code-review's current default scoped translation. Preserve explicit target semantics.

Assume no durable-state migration or schema change is needed, as the digest identifies none. Implement confirms this against actual contract seams before making such a change; do not add a migration speculatively. Exact new type names and the delegated request field shape are implement-time choices within the stated owners, using typed vocabulary and the existing dependency direction. Keep them local unless a real cross-module contract requires shared ownership.

The active implementation base remains `base/SKILL-380-phase-slot-strategies` under the repository operator constraint. The runtime owns branch handling and the existing one-subtask sequence. Standalone review remains in-memory and gains no resume or report-and-fix mode. Valid changes-requested reports are successful execution, not a repair trigger. Invalid or failed reports block with available evidence. There is no fallback to the full-run repair slot.

The main rollout risks are full-run fixture drift, false delegated completion, discarded partial findings, and excess review authority. Tasks 1, 4, 3, and 2 address those risks respectively, with task 6 protecting the original dirty-index failure. No validation, build, installation, history, commit, or PR proof is claimed by this plan.
