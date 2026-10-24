# SKILL-442 subtask 3 - accepted-criterion re-check and unknown-scenario surfacing

## Scope

Parent criterion AC-003, and the AC-002 rule that `unknown` never blocks and is recorded as a diagnostic, passed to review, and listed in the PR description. Uses the `0.2` contract fields, path extractor, trace scope, and `recordInteractionTrace` from subtask 2. Telemetry belongs to subtask 8.

Shared decisions restated: re-checks share the subtask 2 `interaction_trace` invocation of the `audit` step. Re-check verdicts are prose lines `Recheck <AC-NNN>: holds evidence <citations>` or `Recheck <AC-NNN>: regressed evidence <citations>`, where `<citations>` is a comma-separated `path:line` list. Malformed output gets one re-ask, then a `RuntimeDiagnosticsBestEffortWarning`, and every pending re-check counts as `holds` with its prior evidence kept. Regressed criteria use the existing three-outcome budget.

## Acceptance Criteria

1. Every audit repair round records which accepted criteria it touched.
   After each `audit_implement_fix` round settles (`slot/audit/AuditImplementFixStep.kt`), the runtime takes the round's changed files from `capture.fileManifest`. It then adds to `interaction_trace.recheck_refs` every accepted criterion, other than the one under repair, whose closure evidence paths (subtask 2's extractor) intersect those files.
   An accepted criterion whose evidence yields no path goes into `recheck_any_change_refs` whenever the round changed any file. The runtime records one `RuntimeDiagnosticsBestEffortWarning` per such criterion naming its ref. A round that changed no file adds nothing. A criterion whose cited files no round touched is never added.
   The set update is written in the same repair-state persistence write that records the round's outcome, so a resume never loses it. A domain function on `AcceptanceCriterionRepairState` computes the update; the engine passes it the manifest.
   This criterion does not run the re-check (criterion 2). Audit confirms it by reading the set computation, the warning, and the persistence call.
2. Re-checks run batched with the trace before audit settles.
   When every criterion is accepted and `recheck_refs` or `recheck_any_change_refs` is non-empty, the state enters `interaction_trace_required`. The `audit` step's trace scope from subtask 2 lists those refs with their current `closure_evidence_ref` and asks for one `Recheck` line each. When scenarios are also pending they share the same invocation. When the catalog has no scenarios and no pairs, the re-check runs as its own invocation of the same step and scope. When neither scenarios nor re-checks are pending, no agent launches.
   The subtask 2 parser reads `Recheck` lines into the audit result's `rechecks` array, reporting a malformed result when a listed ref has no line. `recordInteractionTrace` applies them. A `holds` criterion stays accepted and its `closure_evidence_ref` is replaced with the new evidence. A `regressed` criterion goes into `regressed_refs` and the repair queue exactly like a broken scenario owner. Both re-check sets are cleared once applied.
   This criterion does not change admission, which subtask 2 already opened for `interaction_trace` output. Audit confirms it by reading the scope population, the batching rule, the parser extension, and the transition.
3. `unknown` scenario verdicts reach operators without blocking.
   When a trace settles with `unknown` refs, the runtime records one `RuntimeDiagnosticsBestEffortWarning` per ref naming the ref and its reason text. The CODE_REVIEW prompt for that subtask gains a short context section, built from the repair state's `unknown_scenario_refs` and the catalog, that lists each unknown scenario's id, entry point, and outcome as context to read, not as findings scope.
   The goal PR body built by `GoalRunnerBranchPlan.toPullRequestRequest` lists, per subtask, `Interaction scenarios not traced:` followed by each unknown ref and its reason. The text comes from a per-subtask lookup populated in `GoalRunnerFinalization.openPullRequest` the same way `qaSummary` is. A subtask with no unknown refs adds no line.
   An unknown verdict never changes a criterion's stage or blocks the subtask. Audit confirms it by reading the warning call, the review prompt section, and the PR body lines.

## Criterion Sizing

1. About 110 production lines, 4 files, 2 areas (`runtime-domain` model/audit, `runtime-engine` slot/audit and persist). Fits.
2. About 150 production lines, 5 files, 3 areas (`runtime-domain` model/audit, `runtime-engine` slot/audit, `runtime-engine` persist/acceptance). Fits.
3. About 120 production lines, 5 files, 3 areas (`runtime-engine` diagnostics call site in persist/acceptance, `runtime-engine` slot/review prompt, `runtime-engine` goalrunner execution). Fits.

## Interaction Scenarios

1. IS-001. Criteria: AC-001, AC-002.
   Entry point: an `audit_implement_fix` round for AC-002 followed by its targeted audit.
   Preconditions: AC-001 is accepted citing `src/a.ext:10`; the repair edits `src/a.ext`; the catalog has no scenarios.
   Steps: the round settles; AC-002's targeted audit accepts it.
   Outcome: AC-001 is in `recheck_refs`; one invocation runs with only the re-check scope; a `regressed` verdict reopens AC-001 under its existing budget.
2. IS-002. Criteria: AC-001, AC-002.
   Entry point: the same repair round.
   Preconditions: AC-003 is accepted with prose evidence that cites no path.
   Steps: the round changes one file.
   Outcome: AC-003 is in `recheck_any_change_refs`, one warning names AC-003, and the re-check invocation lists it.
3. IS-003. Criteria: AC-002, AC-003.
   Entry point: goal finalization opening the PR after the subtask commits.
   Preconditions: the trace left IS-002 `unknown` after the second malformed output.
   Steps: review runs, the subtask commits, the PR is built.
   Outcome: the review prompt lists IS-002 as context, the PR body lists IS-002 under its subtask, and the subtask was never blocked.

## Non-Goals

- Telemetry counts and `goal-stats` (subtask 8).
- Re-checking criteria whose cited files no repair touched.
- Offering same-file pairs to review as hunks (parent open question, left open).

## Dependency Notes

Depends on subtask 2 for the contract fields, extractor, trace scope, parser, and transition.

## Test Obligations

- Regression test from the parent constraints: a repair round that edits a file cited by an accepted criterion puts that criterion in `recheck_refs`, and a round editing an uncited file does not. Bug caught: the set ignores the manifest or adds every accepted criterion.
- Batching test: with no pending scenarios and no re-check refs, no agent launches. Bug caught: an empty trace launches on every accepted subtask and burns an agent run.

## Implementation Details

Paths are relative to `runtime-kotlin/`. `D` denotes `runtime-domain/src/main/kotlin/skillbill`; `E` denotes `runtime-engine/src/main/kotlin/skillbill/engine`. Criterion references below refer to this sub-spec's numbered criteria as AC-001 through AC-003.

### Evidence and prerequisite ownership

The supplied preplan digest is the starting evidence. Its original S2 grouping is superseded by this assigned sub-spec's explicit dependency on subtask 2. This plan owns touched-criterion scheduling, re-check application, and unknown-scenario surfacing. Subtask 2 owns the `0.2` schemas and keys, evidence-path extractor, catalog binding, trace stage, admission, trace parser, and `recordInteractionTrace`. Those are authorized local prerequisites, not claims that future implementation has already landed. Reuse their eventual production names and implementations. No external service capability is needed.

Targeted source inspection resolved the following implementability facts. No build or tests ran.

- `E/featuretask/phase/core/FeatureTaskRuntimePhaseSafetyPolicy.kt:9-15` defines `capture.fileManifest.introduced` as newly dirty paths plus re-edited paths for the round. `FeatureTaskRuntimePhaseStateRequest.fileManifestIntroduced` carries it into settlement, as recorded in the existing plan evidence. `AuditImplementFixStep` does not write repair state. The transaction owner is `AcceptanceCriterionRepairPersistence`, reached through `AcceptanceCriterionRepairPhaseRecorder.commit`.
- `E/featuretask/persist/acceptance/AcceptanceCriterionRepairPersistence.kt:720-738` applies `ContinueRepair`, `RepairCompleted`, and `Outcome`. `RepairCompleted` explicitly detects replay. The recorder's `repairAdmissionMutation` can settle a repair through a subsequent read-only admission audit, so its empty manifest must not overwrite files already accumulated from the repair round.
- `D/workflow/taskruntime/model/audit/AcceptanceCriterionRepairState.kt:64-78,129-155` holds closure evidence, source disposition, reservations, and `proseAudits`. `AcceptanceCriterionProseAudit` includes id, revision, prose, tree digest, and checkpoint. `D/workflow/taskruntime/artifact/AcceptanceCriterionRepairStateWire.kt:58,97,265-276` serializes and restores the full prose. The current schema's `prose_audits` property permits a null criterion ref and non-blank prose. This supplies durable reason transport without adding a wire field. The new trace transition must explicitly retain its admitted prose record; current `recordProseAudit` does not admit an interaction trace by itself.
- `E/featuretask/slot/codereview/InlineReviewPromptSections.kt`, `PhaseLaunchPreparation.composeLaunchPromptInputs`, and `FeatureTaskRuntimePhasePromptComposeInputs` are the review composition seams identified in the digest and existing plan evidence.
- `E/goalrunner/persist/WorkflowGoalRunnerOutcomeStore.kt:366-407` contains `WorkflowGoalRunnerReviewBridge`, the database-read owner for per-child review and QA results. The outer outcome store delegates to that bridge. `E/goalrunner/execution/support/GoalRunnerBranchPlan.kt:21-67` accepts per-subtask summary lookups. `GoalRunnerFinalization.openPullRequest` supplies them. Extend this path for durable unknown refs and reasons.

### Ordered tasks

1. Compute the touched-criterion update in the domain. Serves AC-001 and IS-001, IS-002.

   Add a focused helper beside `D/workflow/taskruntime/model/audit/AcceptanceCriterionRepairState.kt`, using subtask 2's path extractor. Its input is the pre-round repair state and the round's changed paths. Its typed result contains the updated pending sets and newly pending refs whose evidence had no path. Eligibility uses the existing accepted/source-closed invariant, closure evidence, and unresolved queue, and excludes the live reservation's criterion. Capture that exclusion before any outcome transition clears the reservation.

   Compare repository-relative paths after normalizing separators and removing a leading `./`. Reuse the extractor's production-path classification. Match cited paths against the manifest, without expanding all accepted criteria into the set. An empty manifest adds nothing. A criterion with no extractable evidence path enters `recheck_any_change_refs` after any file change. Retain distinct, sorted, disjoint pending sets and union updates across rounds. Test-only edits still trigger the no-path fallback. Do not infer acceptance solely from a non-null evidence string.

   Test obligation: extend a domain regression test with cited, uncited, no-path, and currently repaired criteria. Assert the pending sets and returned no-path refs after changed-file and empty-file rounds. Bug caught: ignoring the manifest or reopening unrelated/current criteria. Keep one fixture family for these branches.

2. Persist the update atomically with every settled repair round. Serves AC-001 and resume behavior in IS-001, IS-002. Depends on task 1.

   Extend `E/featuretask/model/audit/AcceptanceCriterionRepairPersistenceModels.kt` and the recorder/persistence call sites so the same admitted settlement carries the repair round's `phase.fileManifestIntroduced`. Carry changed paths on the in-memory repair mutation or commit facts, not on a new durable outcome field. Apply it for completed, continued, and unsuccessful repair rounds. Include clarification/progress settlements when they retain edits, so a report that requires admission cannot lose touched-file information. Read-only audit/admission settlements contribute no new file set and preserve the accumulated sets.

   Compute task 1's update from the pre-transition state, then merge its pending sets into the successful outcome transition before the existing persistence write. Preserve existing revision, reservation, checkpoint, compare-and-set, and replay rules. A refused write records neither the round nor the sets. A replay preserves the committed union and does not run a second logical transition. A projection failure after commit must retain the authoritative sets.

   Emit one `RuntimeDiagnosticsBestEffortWarning` for each newly pending no-path ref after successful authoritative commitment. Reuse or inject `RuntimeDiagnostics` through the existing composition root. Carry the newly added refs and whether this write actually applied a transition in the internal persistence result if needed; do not recompute warnings from stale expected state on a replay. Keep diagnostics outside the transaction and preserve cancellation. Add only the minimal constructor and fixture updates.

   Test obligation: extend `AcceptanceCriterionRepairPhaseRecorderTest` with a repair editing a cited file, persistence reload, and repeated settlement. Assert the durable pending refs survive reload and do not duplicate or disappear on replay. Include a retained-edit continuation/admission sequence in this fixture. Bug caught: scheduling only in memory or dropping the files during the later admission audit.

3. Schedule and compose one batched trace invocation. Serves AC-002 and IS-001, IS-002. Depends on tasks 1 and 2.

   Extend subtask 2's trace-due predicate and its call sites in `FeatureTaskRuntimeRunEntry.inspectRepairState` and the post-targeted-audit domain transition. Once all criteria are accepted, either pending re-check set is sufficient to enter `interaction_trace_required`, including when no scenario or pair exists and the catalog was already traced. Preserve the existing scenario and pair scheduling rules. No pending scenario, pair, or re-check means no agent launch.

   Populate the existing trace scope through `PhaseLaunchPreparation` and the prompt-compose input with the union of pending refs and their current `closure_evidence_ref`. Render only instructions for work actually in scope. Pending scenarios and re-checks share one read-only `audit` invocation. A re-check-only invocation uses that same step and admission mode, without introducing a new phase or agent tier.

   Test obligation: extend `FeatureTaskRuntimeSerialRepairTest` to observe forward progression without an audit launch for an empty scope, and one trace settlement for re-check-only scope. Assert the resulting workflow state rather than helper call counts. Bug caught: repeated empty agent launches or skipping re-checks when the catalog has no scenarios.

4. Parse and apply re-check verdicts through the existing trace transition. Serves AC-002 and IS-001. Depends on task 3.

   Extend subtask 2's parser beside `AcceptanceCriterionProseAudit.kt` and its trace prompt renderer. Require exactly one line for every scoped ref, using `Recheck <AC-NNN>: holds evidence <citations>` or `Recheck <AC-NNN>: regressed evidence <citations>`. The citation list contains comma-separated `path:line` citations. Reject missing, duplicate, unlisted, invalid-verdict, and malformed-citation entries through subtask 2's typed malformed-output path. Produce its existing `rechecks` array and put regressed refs into `regressed_refs` consistently with the contract coherence rule.

   Extend `recordInteractionTrace`. Holds keeps acceptance and replaces closure evidence with the new citations. Keep its closure tree/checkpoint provenance coherent with the new trace evidence. Regressed uses subtask 2's broken-owner transition, clears closure fields, records gap/source-transition evidence, rebuilds the stable unresolved queue, and retains prior repair outcomes and the existing three-outcome budget. Clear both pending sets only when the whole admitted batch applies.

   Preserve the operator-specified fallback. Re-ask malformed output once. On the second malformed output, warn, clear the pending re-check sets, retain each criterion's prior closure evidence unchanged, and treat pending scenarios as unknown. This fallback does not establish fresh source verification. Do not change targeted-audit admission or introduce a new block reason.

   Test obligation: extend the serial fixture from task 3 with a regressed re-check and assert routing to `audit_plan_fix` with the prior budget intact. Extend the existing trace parser/transition fixture for holds and second-malformed fallback, asserting new evidence for holds and unchanged prior evidence for fallback. Bug caught: accepting a regression, resetting its budget, or fabricating fresh evidence after malformed output. Preserve subtask 2's governed contract and malformed-output coverage.

5. Retain unknown reasons and emit diagnostics. Serves AC-003 and IS-003. Depends on task 4 and subtask 2's trace transition.

   Extend `recordInteractionTrace` to retain the admitted trace prose in the existing `proseAudits` collection alongside the unknown refs, atomically with trace settlement. Use the existing audit identity, revision, digest, tree, checkpoint, size limits, and replay checks. Store canonical reason-bearing unknown lines in that prose; use subtask 2's parser grammar for normal verdicts. For the second-malformed fallback, persist canonical unknown lines with reason `malformed audit output` for each pending scenario. This is a local transition change using an existing wire field, not an optional dependency on diagnostics surviving process exit.

   Add a pure typed projection that joins `unknown_scenario_refs` to the latest admitted trace line for each ref. Retain reasons across subsequent traces that do not revisit that scenario. Share this projection between review/PR assembly where useful. Do not add a duplicate unknown-reason state field. Current prose admission and restore/coherence checks must admit these trace records through subtask 2's interaction path, without weakening the initial and targeted audit rules.

   After a newly committed trace settlement, emit one best-effort warning per unknown ref with its reason. Use the transition's replay identity to avoid duplicate logical warnings. Unknown alone never reopens a criterion, consumes a repair outcome, or blocks progression. Co-occurring broken results may still reopen their owning criteria.

   Test obligation: include reason durability in task 7's reload-to-PR fixture. Bug caught: the PR loses the actual reason because only in-memory parser output retained it. No test solely for warning wording.

6. Add review context for unknown scenarios. Serves AC-003 and IS-003. Depends on task 5.

   Extend `FeatureTaskRuntimePhasePromptComposeInputs` with a typed optional/default-empty list of unknown scenario context containing ref, entry point, and outcome. In `PhaseLaunchPreparation`, populate it for CODE_REVIEW from the current repair state and bound scenario catalog. Add a short conditional section in `InlineReviewPromptSections.review` that lists these fields and instructs review to read them as context rather than findings scope. Empty lists render no section. Keep standalone review behavior and existing targeted audit instructions intact.

   Test obligation: add an assertion to the existing prompt-composer integration fixture that a non-empty state reaches CODE_REVIEW context with its entry point and outcome. Bug caught: the new renderer exists but production launch composition never supplies its input. Avoid a separate literal-text snapshot test.

7. Read durable unknowns into the per-subtask PR summary. Serves AC-003 and IS-003. Depends on task 5.

   Add a typed read method to `GoalRunnerReviewOutcomeStore` and implement it in `WorkflowGoalRunnerReviewBridge`, with the outer `WorkflowGoalRunnerOutcomeStore` delegation. Read the workflow artifact family `FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR` in one existing database read transaction, decode with the authoritative wire decoder, and use task 5's projection to return refs and exact reasons. Declare the small result model in the engine goal-runner model package. Use an abstract method and update concrete implementations/test substitutes; do not introduce an empty default implementation to hide missing wiring.

   Keep absence, successful empty results, and malformed durable data distinguishable with a typed read result. Handle only the owning typed schema/decode failures at this best-effort reporting boundary. Return failure details to the finalization caller, which emits a warning before omitting unavailable context. Do not broadly catch defects or cancellation. A valid trace with an unknown ref but no recoverable reason is a diagnosed inconsistency, not a silent `not traced` replacement.

   Add the per-subtask lookup beside `qaSummary` in `GoalRunnerFinalization.openPullRequest`, resolving child workflow IDs through the manifest. Pass typed summaries into `DecompositionManifest.toPullRequestRequest`. Under a conditional interaction-scenario section, render `Interaction scenarios not traced:` with every unknown ref and its reason under the owning subtask. Escape or flatten reason text for a readable Markdown list without dropping its content. No unknown refs means no added line. A child lookup failure warns and leaves the goal on its normal publication path.

   Test obligation: extend the existing goal outcome-store/PR fixture to load a persisted trace, including a fallback unknown reason, and render the PR after reload. Assert subtask attribution, actual reasons, and absence of lines for a second empty subtask. Bug caught: a summary callback that works with fixtures but loses durable evidence in production. Extend existing typed decode-failure coverage only where this new read boundary differs.

### Constraints and later validation

- Apply architecture rules A1, A2, A6, A7, A9 through A12 and the section 5 review checklist. Domain transitions and extraction stay pure; the engine owns transactions, diagnostics, and prompt/PR assembly. Keep wire keys with their existing owner and enum tokens on `wireValue`. Use narrow visibility and typed results. Avoid new raw maps, collaborator bags, broad context helpers, and port defaults that substitute for implementation.
- Use the subtask 2 `0.2` contract fields and failure code. This plan adds no schema shape or version. Retaining trace prose uses the existing `prose_audits` shape. Keep its governed parity, coherence, restoration, and malformed-output tests; do not relax guards to admit inconsistent state.
- Keep new logic in focused files within the owning noun family and maintain package/file-size limits. No Kotlin line/block comments; KDoc only on interfaces and their members. No baseline, exemption, assertion, or architecture-rule weakening.
- Telemetry and `goal-stats` remain with subtask 8. Offering same-file pairs to review remains unclaimed. This subtask does not change tracing admission, add an agent phase, reset budgets, or alter parent/sibling specifications.
- Implementation authors the production and test changes. The build phase owns build proof. Validate owns test execution and the repository's required full validation strategy, including runtime checks and repoTest guards. Review/audit inspect the production paths and scenarios. History, commits, and publication remain with their runtime phases. Necessary wiring, test setup, formatting, and lint repairs remain permitted within the implementation scope.
- No build, test, or validation commands ran during planning. Planned tests target the concrete bugs named above; combine fixtures where they exercise the same branch. Preserve existing regression and contract coverage. Warning wording and helper names are non-critical implementation choices.

## Validation Strategy

The validate phase runs the runtime-kotlin Gradle `check` with repoTest guards. Nothing runs here.

## Next Path

```bash
skill-bill goal SKILL-442
```
