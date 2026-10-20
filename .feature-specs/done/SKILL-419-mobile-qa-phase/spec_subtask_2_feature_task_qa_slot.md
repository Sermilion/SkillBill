# SKILL-419 subtask 2: Feature-task `qa` and `qa_fix` in the goal runtime

Parent spec: `spec.md`

## Scope

Put the subtask 1 QA strategy into the full run after a satisfied acceptance audit, with bounded `qa_fix` repair, operator selection, invalidation, and safe resume of older workflows.

- Add `PhaseSlot.QA` to `FEATURE_RUN_SLOTS` between AUDIT and CODE_REVIEW. Add `PHASE_QA`, `PHASE_QA_FIX`, `QA_FIX_LOOP_ID`, `MAX_QA_FIX_ATTEMPTS = 3`, and a `QA_RECEIPT` projection contract to `FeatureTaskRuntimePhaseWorkflowDefinition`. Add step labels ("Phase 4d: Mobile QA", "Phase 4e: QA Fix"), required artifacts (qa needs plan and audit; qa_fix needs qa, audit, plan), and resume actions to the workflow graph. The qa_fix resume action reconciles existing edits and continues to re-audit and QA without replaying a completed repair.
- Transitions: an entry gate where qa requires audit verdict SATISFIED; a backward edge qa(findings)→qa_fix with a cap of 3, BLOCK on exhaustion, PER_SUBTASK; qa_fix in `loopOnlyPhaseIds`; qa_fix ADVANCE→audit, then audit→qa. Add a QA findings verdict to `FeatureTaskRuntimeVerdict`. Review's entry gate does not require qa (decision D8), because qa is optional and the traversal already orders it before review.
- Cross-slot edge (decision D1): `ResolvedPhaseExecutionPlan.phaseTraversal()` currently drops backward edges whose endpoints sit in different slots. Add a typed, explicit cross-slot allowance on the edge declaration and let phaseTraversal keep only edges that carry it. Use it for qa_fix→audit and for the invalidation edges below. All four phaseTraversal callers (PhaseRunEntry, FeatureTaskRuntimeRunLoopDurableState, FeatureTaskRuntimeRunPreparation, FeatureTaskRuntimeRunnerExecutePrepared) keep working on the shared result.
- Re-audit scope after qa_fix (decision D2): the audit pass that follows a qa_fix attempt checks only the criteria named in the open findings' `affected_criteria`. Previously accepted criteria not named there stay accepted, which keeps the "never re-check an accepted criterion" rule for unrelated criteria. If a finding names no criterion, the audit re-checks nothing and QA alone decides closure. The audit prompt states this scope.
- `QaVerdictRule`: `passed`, `skipped`, and infrastructure-only `incomplete` advance; `findings_open` routes to qa_fix. `incomplete` that carries findings stays findings_open. A later skip or incomplete receipt keeps earlier open findings open, and a qa_fix output never clears findings; only a later QA receipt on the changed tree with those findings absent and the flows exercised closes them. Contract errors, corrupt state, runtime defects, and cancellation propagate as failures and never become skips.
- `QaResumeRules` and a qa-repair state record (follow `acceptance-criterion-repair-state-schema.yaml` as the attempt-budget precedent, with schema, Kotlin version, keys object, failure code) persist qa_fix identity, consumed attempts, latest open findings, and repair output across resume.
- Invalidation (AC-010): the QA receipt carries the tree identity from the checkpoint-identity contract. A review repair, implement_fix, or any later edit that changes the tree before write_history invalidates the receipt and routes back to audit and qa through a cross-slot edge. A message-only amend with an unchanged tree keeps the receipt. Resume re-checks target and revision validity rather than reusing a stale receipt.
- Selection (decision D4): `FeatureTaskRuntimeQaSelection` enum `AUTO | OFF` with `fromWire` throwing a typed unknown-selection error, carried on `ResolvedPhaseExecutionPlan` as `qaSelection`, as a strategy selection fact in `PhaseStrategyLookup`, through the CLI flag `--qa auto|off` and an env var mirroring `SKILL_BILL_QUALITY_GATE_SELECTION` in `FeatureTaskRuntimeGoalContinuationLaunchTokens`, the request assembly, the goal continuation artifact, and the governed seam inventory. Omission means `auto`. `off` produces a `skipped` receipt with `disabled_policy` and no probing. Duplicate, conflicting, or unknown values are usage errors. A resumed request whose qa selection differs from the recorded one is refused.
- Execution plan contract: `feature-task-runtime-execution-plan.yaml` becomes "0.3" with `qa_selection`, the Kotlin version and key constants update, and the codec decodes "0.2" records with qa_selection absent, meaning "pre-QA composition, QA not composed" (decision D5), never auto.
- Pre-QA resume (decision D3): the semanticRevision 2 GOAL_CHILD and STANDALONE definitions stay resolvable with their pre-QA slot lists, and new definitions at semanticRevision 3 include QA. The `SkeletonDefinition` lookup supports two revisions per id without `byId` picking the wrong one for new launches. A recorded revision-2 plan resumes on its recorded composition with no qa step; a new launch gets revision 3.
- If adding a QA policy id, register it in `FeatureTaskRuntimeEffectivePolicies` and in the compatibility policy set.
- Each full-run QA settlement writes its outcome (attempted, passed, findings, skipped, incomplete), skip or incomplete reason, and consumed qa_fix attempts to the runtime database, and the pull request the goal opens summarizes each subtask's QA outcome. Progress, IDE status, and CLI reports do not display the full-run outcome yet; the stored record is what a later change surfaces. Every fallback, target substitution, retry, and cleanup failure emits a diagnostic record without credentials or device log bodies.
- `../../../skills/skill-bill/content.md` documents `qa:auto|off` on the full run, its translation to `--qa <value>`, and the usage error when `qa:` reaches a form that does not accept it. `docs/runtime-command-guidance.md` updates the child phase sequence to show audit, optional qa, then review.

## Acceptance Criteria

1. `FEATURE_RUN_SLOTS` places a QA slot holding `qa` and `qa_fix` between audit and code review, with step labels, required artifacts, and resume actions declared in the workflow graph.
2. The transition declaration gates qa on a SATISFIED audit, routes QA findings to qa_fix with a cap of three and BLOCK on exhaustion, routes qa_fix back to audit and then qa, and `phaseTraversal()` retains that cross-slot edge through an explicit typed allowance while still dropping undeclared cross-slot edges.
3. `QaVerdictRule` advances on passed, skipped, and infrastructure-only incomplete receipts, routes findings to qa_fix, keeps previously open findings open across a later skip, incomplete receipt, or qa_fix claim, and propagates contract errors and cancellation as failures.
4. The audit pass after a qa_fix attempt is scoped to the criteria named in the open findings, and that scope is stated in the audit prompt.
5. A versioned qa-repair state contract with Kotlin version, keys object, and typed failure code persists qa_fix identity, consumed attempts, open findings, and repair output, and resume continues to re-audit and QA instead of replaying a completed repair.
6. A QA receipt bound to one tree identity is treated as invalid after any tree-changing edit before write_history, which routes back to audit and qa, while a message-only amend with the same tree keeps it.
7. `qa:auto|off` reaches the execution plan through the `--qa` flag, its environment variable, and the goal continuation artifact; omission resolves to auto; `off` yields a `disabled_policy` skip with no probing; unknown, duplicate, or conflicting values raise usage errors; and a resume with a different selection is refused.
8. The execution-plan contract is at "0.3" with `qa_selection`, "0.2" records decode with QA not composed, and revision-2 goal-child and standalone definitions remain resolvable so a pre-QA workflow resumes on its recorded composition while new launches use the QA-bearing revision.
9. Each full-run QA settlement durably records its outcome (attempted, passed, findings, skipped, or incomplete), its skip or incomplete reason, and its consumed qa_fix attempts in the runtime database; the pull request the goal opens states each subtask's QA outcome and reason; and every fallback, target substitution, retry, and cleanup failure emits a diagnostic record without credentials or device log bodies. Displaying the full-run outcome in progress, IDE status, or CLI reports is out of scope.
10. `../../../skills/skill-bill/content.md` and `docs/runtime-command-guidance.md` document the full-run `qa:auto|off` token and the audit, qa, review ordering.

## Non-goals

- Verify integration (subtask 3).
- Changing review's entry gate, the quality gate, or audit repair budgets and non-shrinking limits.
- Model-specific QA strategy variants.
- Mapping pre-QA workflows into QA; they keep their recorded composition.

## Dependency Notes

Depends on subtask 1 for the receipt contract, shared validator, capability resolver, device lease, QA strategy, and the `PhaseSlot.QA` and step ids.

Assumption for implement to confirm: the run loop can express "audit after qa_fix" through the same audit step with a findings-derived scope. If the audit strategy cannot take that scope without a new step, implement adds the narrowest hook on the audit strategy rather than a second audit step.

## Test Obligations

- phaseTraversal keeps the declared qa_fix→audit cross-slot edge and still drops an undeclared cross-slot edge (risk 1: silent edge loss).
- QA findings drive three qa_fix attempts, then BLOCK; attempt count survives a resume in the middle of the loop (AC-009).
- A skip receipt after a findings receipt leaves the findings open and does not advance past QA (AC-009).
- Passed and skipped receipts never launch qa_fix (AC-009).
- A tree-changing review repair invalidates the QA receipt and re-enters audit and qa; a message-only amend does not (AC-010).
- A recorded "0.2" revision-2 goal-child plan resumes with no qa step, and a new launch composes QA (AC-012).
- A resume requesting `--qa off` against a recorded `auto` plan is refused; an unknown `--qa` value is a usage error (AC-007 of this subtask).
- qa-repair state schema accept and reject parity through the real validator.

## Implementation Details

All paths below are relative to `../../../runtime-kotlin` unless they start with `orchestration/`, `skills/`, or `docs/`. Package prefixes are abbreviated as in the preplan digest. Subtask 1 is assumed merged: it supplies `PhaseSlot.QA` (steps `qa`, `qa_fix`), the `FeatureTaskRuntimePhaseIds.QA`/`QA_FIX` ids, the qa-receipt schema with `QaReceiptPayloadKeys` and its shared validator, the `QaCapabilityResolver`, the device lease port, and the base `QaStrategy`. If any of these landed under a different name, implement uses subtask 1's name and does not duplicate it.

### Task 1. Feature-run slot, ids, and workflow graph (AC 1)

- `runtime-domain/.../workflow/taskruntime/model/skeleton/SkeletonDefinition.kt`: add `PhaseSlot.QA` to `FEATURE_RUN_SLOTS` between AUDIT and CODE_REVIEW. `PhaseSlot` already declares QA in ordinal order (subtask 1), which keeps the `SkeletonDefinition` ascending-ordinal `init` check green.
- `runtime-domain/.../phase/task/FeatureTaskRuntimePhaseWorkflowDefinition.kt`: add `PHASE_QA`, `PHASE_QA_FIX`, `QA_FIX_LOOP_ID`, `MAX_QA_FIX_ATTEMPTS = 3`, and `PhaseProjectionContract.QA_RECEIPT = "feature_task_runtime.qa_receipt"`. Add `qa_fix` to whichever of `runtimeSettledPhaseIds`/`agentSettledPhaseIds`/`noChangeClaimPhaseIds` `monitor_fix` and `audit_implement_fix` use as repair substeps; qa_fix is agent-settled and may not claim no-change.
- `runtime-domain/.../phase/task/FeatureTaskRuntimePhaseWorkflowGraph.kt`: `stepLabels` gains `qa` "Phase 4d: Mobile QA" and `qa_fix` "Phase 4e: QA Fix"; `requiredArtifactsByStep` gains `qa` → [plan, audit] and `qa_fix` → [qa, audit, plan]; `resumeActions` gains a qa entry ("re-check target and revision, then run QA") and the qa_fix entry "reconcile existing edits and continue to re-audit and QA without replaying a completed repair". Review's required artifacts stay [audit] (D8).

### Task 2. Verdict, transitions, and the typed cross-slot allowance (AC 2)

- `skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict`: add `QA_FINDINGS` (wire `qa_findings`). Reuse existing ADVANCE/BLOCK for the other outcomes.
- `runtime-domain/.../phase/task/FeatureTaskRuntimePhaseWorkflowTransitions.kt`: add an entry gate `qa` requires `audit` verdict SATISFIED. Add backward edge `qa` QA_FINDINGS → `qa_fix` on `QA_FIX_LOOP_ID`, `perEdgeCap = MAX_QA_FIX_ATTEMPTS`, exhaustion BLOCK, scope PER_SUBTASK, following the `monitorFixEdges` shape. Add `qa_fix` to `loopOnlyPhaseIds`. Add edge `qa_fix` ADVANCE → `audit` marked cross-slot. The forward path audit → qa needs no edge because the traversal orders the QA slot after AUDIT; a re-entered audit that returns SATISFIED advances into qa through the normal forward step.
- Backward-edge declaration type (the edge model used by `FeatureTaskRuntimeTransitionDeclaration.backwardEdges`): add a typed field, `crossSlot: CrossSlotAllowance? = null` or a `Boolean crossSlot = false`. Prefer the typed value object if the edge model already uses value types for loop scope; otherwise a boolean. Default keeps every existing edge's behavior.
- `runtime-domain/.../model/skeleton/ResolvedPhaseExecutionPlan.kt` `phaseTraversal()`: keep a backward edge whose endpoints are in different slots only when it carries the allowance and both endpoints are among the plan's selected steps; keep dropping undeclared cross-slot edges. All four callers (`PhaseRunEntry.kt:251`, `FeatureTaskRuntimeRunLoopDurableState.kt:61`, `FeatureTaskRuntimeRunPreparation.kt:47`, `FeatureTaskRuntimeRunnerExecutePrepared.kt:149`) keep calling the same function; no caller-side filtering.
- Assumption for implement to confirm: the run-loop transition engine (`FeatureTaskRuntimeRunLoopTransitions.kt`, not read in preplan) resolves a backward edge to any step in the traversal regardless of slot once it is present. If it indexes by slot, the narrowest fix goes there, not a second traversal API.

### Task 3. Skeleton revisions for pre-QA resume (AC 8)

- `SkeletonDefinition.kt`: keep the semanticRevision 2 GOAL_CHILD and STANDALONE definitions with a frozen pre-QA slot list (`LEGACY_FEATURE_RUN_SLOTS_REVISION_2`, today's list without QA) and add revision-3 definitions built from the new `FEATURE_RUN_SLOTS`. GOAL_CHILD rev 3 still filters PULL_REQUEST and MONITOR.
- Lookup: `entries.singleOrNull { id && semanticRevision }` keeps working because each (id, revision) pair is unique. Change `byId` to return the highest semanticRevision for the id so new launches resolve revision 3. Recorded-plan paths keep resolving by (id, revision). Implement audits every `byId` caller; any caller that handles a recorded plan must switch to the (id, revision) lookup.
- `PhaseSlot.QA` is in the rev-2 enum universe but not its slot list, so `unselectedStepIds` (derived from `FEATURE_RUN_SLOTS`) must be computed from the plan's definition slots, not the global constant, or a rev-2 plan would list qa as unselected noise. Implement confirms which reading the compatibility check needs and keeps rev-2 byte-identical.
- `AuditPlanningExecutionPlanMapping` looks up the definition by recorded revision; with both revisions present its `supported` composition stays on the recorded revision. No QA mapping is added (non-goal).

### Task 4. Selection model and execution-plan contract 0.3 (AC 7, AC 8)

- New `runtime-domain/.../model/skeleton/FeatureTaskRuntimeQaSelection.kt` (or the package holding `FeatureTaskRuntimeQualityGateSelection`): enum `AUTO("auto")`, `OFF("off")` with `fromWire` throwing a new `UnknownQaSelectionError` (pattern of `UnknownQualityGateSelectionError`).
- `ResolvedPhaseExecutionPlan`: add `qaSelection: FeatureTaskRuntimeQaSelection?` after `qualityGateSelection`; update `withTraversal`, `withEffectivePolicies`, and every positional constructor call, including the two in `AuditPlanningExecutionPlanMapping` and test fixtures. Null means "pre-QA composition" (D5).
- `../../../orchestration/contracts/feature-task-runtime-execution-plan.yaml`: bump `contract_version` const to "0.3", add required `qa_selection` with enum [auto, off, null]. Keep the 0.2 file as the older version alongside 0.1 if that is how 0.1 is kept; implement follows the existing multi-version layout.
- `runtime-contracts/.../contracts/workflow/identity/task/FeatureTaskRuntimeExecutionPlanContract.kt`: version const "0.3", key const `QA_SELECTION = "qa_selection"`.
- `runtime-engine/.../featuretask/lifecycle/execution/FeatureTaskRuntimeExecutionPlanCodec.kt` and `FeatureTaskRuntimeExecutionPlanDecode.kt`: encode 0.3 with qa_selection; decode 0.3 requiring the key; decode 0.2 records with `qaSelection = null`, never AUTO. A malformed or unknown version still fails with the existing typed error.
- `FeatureTaskRuntimeExecutionPlanCompatibility.kt`: a rev-2 plan must carry null qaSelection; a rev-3 plan must carry non-null. Any other pairing is `UnsupportedFeatureTaskRuntimeExecutionPlanError`.
- Policy id decision: no new QA policy id. The QA budget rides the existing "retry-budgets" policy and invalidation rides "review-invalidation", so the exact-set check in `requireSupportedPolicies` and `FeatureTaskRuntimeEffectivePolicies` stay unchanged. If implement finds QA needs policy settings that cannot live there, it registers one id in both places together.

### Task 5. Selection plumbing (AC 7)

- `runtime-contracts/.../FeatureTaskRuntimeGoalContinuationLaunchTokens.kt`: add `QA_SELECTION_FLAG = "--qa"` and `QA_SELECTION_ENV = "SKILL_BILL_QA_SELECTION"`.
- `runtime-cli/.../cli/featuretask/FeatureTaskRuntimeCliCommands.kt`: add a `qaSelections` option next to `qualityGateSelections` (line 98), parsed the same way: more than one distinct value or an unknown value is a `UsageError`; a repeated identical value follows whatever the quality-gate option does today. Flag and env both present with different values is a conflicting-value UsageError.
- `FeatureTaskRuntimeRunRequestAssembly.kt` and `FeatureTaskRuntimeExecutionPlanCreationRequest`: carry `qaSelection`, defaulting to AUTO when omitted on a new launch.
- Goal continuation artifact (`FeatureTaskRuntimeGoalContinuationArtifact.kt`, `...ArtifactPayloadKeys.kt`, `...ArtifactPatcher.kt`, `...Policy.kt`): persist the selection; consumers `FeatureTaskRuntimeExecutionEntry.kt`, `FeatureTaskRuntimeRunEntry.kt`, `GoalRunnerLaunchReconciler.kt`, `FeatureTaskRuntimeStatusService.kt` read it the way they read the quality-gate selection. An artifact written before this change has no key and resolves as absent, which keeps a resumed child on its recorded plan.
- `runtime-engine/.../featuretask/slot/PhaseStrategyLookup.kt`: add qaSelection to the facts rebuilt in `matchesRecordedSelection` and `executionPlanMapping`; add a `singleQaSelection(facts)` mirroring `singleQualityGateSelection` (line 325).
- `FeatureTaskRuntimeExecutionPlanResolver.resolveCreation`: new plans pass the selection as a `PhaseStrategySelectionFacts` entry; for an existing workflow, `requireRequestedSettings` refuses an explicit requested selection that differs from the recorded one, with an actionable message. An omitted selection on resume uses the recorded value. A request with an explicit selection against a rev-2 (null) plan is refused too, since the recorded composition has no QA.
- `runtime-core/src/repoTest/.../architecture/WireVocabularyGovernedSeamInventory.kt` and `WireVocabularyArchitectureSupport.kt`: register the qa selection seams wherever `qualityGateSelection` appears.

### Task 6. QA in the feature run: strategy, verdict rule, resume rules (AC 3, AC 5, AC 7)

- Package `runtime-engine/.../featuretask/slot/qa/`: extend subtask 1's `QaStrategy` (or add a feature-run binding of it) with steps [qa, qa_fix], `entryStep = qa`, `optionalSteps = [qa_fix]`, the QA_RECEIPT projection, `verdictRule = QaVerdictRule`, `resumeRules = QaResumeRules`, loop rules for QA_FIX_LOOP_ID, and a repair-substep hook modeled on `MonitorStrategy.monitorRepairSteps` and `stepHooks.settleCompletedOutput`. Register it only in `runtime-core/.../di/featuretask/RuntimeFeatureTaskSlotProvides.kt`; add test registrations in `PhaseStrategyRegistryFixtures.kt`/`TestPhaseStrategies.kt`.
- `QaVerdictRule.kt`: parse the receipt through subtask 1's shared validator (malformed → typed failure, never skip). `passed`, `skipped`, and `incomplete` with only infrastructure reasons and no findings → ADVANCE. `findings_open`, or `incomplete` carrying findings → QA_FINDINGS. Before deciding, merge with the qa-repair state: findings open in state stay open unless this receipt is bound to the current tree, exercised the flows those findings named, and omits them. A skip or infrastructure-only incomplete receipt while state holds open findings yields QA_FINDINGS (it does not advance past QA). Contract errors, corrupt state, runtime defects, and `CancellationException` propagate.
- `qa` with qaSelection OFF: the step settles a `skipped` receipt with `skip_reason = disabled_policy` without launching an agent, consulting the capability resolver, or touching the device lease. Rev-2 plans never reach this because they have no qa step.
- `qa_fix` step: an agent repair step with the open findings and affected criteria in its prompt; its output is recorded in the qa-repair state and never changes open findings.
- `QaResumeRules.kt`: on resume read the qa-repair state; a completed qa_fix attempt is not replayed, the loop continues to audit then qa; consumed attempts are not refunded; the qa step re-checks target and revision validity rather than reusing a receipt bound to an older tree.
- New file per concern to stay under `ProductionFileLineCeilingArchitectureTest`.

### Task 7. qa-repair state contract (AC 5, AC 12 of the parent)

- New `../../../orchestration/contracts/qa-repair-state-schema.yaml`, Draft 2020-12, `contract_version` "0.1", modeled on `acceptance-criterion-repair-state-schema.yaml`: subtask id, `qa_fix_step_id`, `max_attempts` (3), `consumed_attempts`, `attempts[]` {attempt_number, receipt_tree_identity, consumed_finding_ids, repair_output_ref, outcome}, `open_findings[]` {id, affected_criteria, receipt_ref}, `last_receipt_tree_identity`. Evidence by reference only.
- `runtime-contracts`: `QA_REPAIR_STATE_CONTRACT_VERSION`, a `QaRepairStatePayloadKeys` object, and `FeatureTaskRuntimeFailureCode.INVALID_QA_REPAIR_STATE_SCHEMA` with a `SkillBillRuntimeException` subclass under `skillbill.error.featuretask`. The producer and every reader validate through the real schema validator.

### Task 8. Re-audit scope after qa_fix (AC 4)

- Narrowest hook on the acceptance audit strategy (`slot/audit/AcceptanceAuditStrategy` and its prompt sections / `AcceptanceAuditLoopRules`): when audit is entered from the qa_fix → audit edge, the audit input carries a scope equal to the union of `affected_criteria` over open findings in the qa-repair state. Accepted criteria outside that set stay accepted. An empty scope makes audit return SATISFIED without re-checking and QA decides closure. The audit prompt states the scope and the reason. No second audit step.
- Record D1, D2, D3, and D8 in `../../../agent/decisions.md`.

### Task 9. Receipt invalidation by tree identity (AC 6)

- The qa receipt carries the tree identity from `../../../orchestration/contracts/feature-task-runtime-checkpoint-identity-schema.yaml`. Under the existing "review-invalidation" policy, when review repair, implement_fix, or any later edit before write_history yields a tree identity different from the receipt's, the runtime marks the receipt invalid and routes back to audit through a declared cross-slot backward edge (review/implement_fix → audit, `crossSlot` set), after which audit → qa runs again. A message-only amend with the same tree keeps the receipt.
- Assumption for implement to confirm: the invalidation check sits where review-invalidation already compares reviewed tree identity; implement extends that site instead of adding a new hook. When qa was skipped by `disabled_policy`, there is no device-bound evidence to invalidate and the edge does not fire. Re-entering audit through this edge uses the Task 8 scoping with an empty findings set, so accepted criteria are not re-checked.

### Task 10. Durable QA outcome and goal pull request (AC 9)

- Persistence: when the qa step settles in a full run, write the outcome (attempted while running, then the receipt verdict mapped to passed, findings, skipped, or incomplete), the skip or incomplete reason, and the consumed qa_fix attempts to the runtime database, keyed by workflow. A schema change follows the migration and contract-version rules; the IDE status contract (`ide-status-schema.yaml`) and its clients stay unchanged.
- Goal pull request: the PR the goal opens lists each subtask's QA outcome and reason, read from the stored record. A pre-QA subtask states that QA was not composed.
- Diagnostics: each fallback, target substitution, retry, and cleanup failure emits a record per `../../../docs/observability-policy.md`. Device ids appear only in the redacted or hashed receipt form; no evidence bodies, device logs, or credentials.
- Out of scope: showing the full-run outcome in progress, IDE status, desktop, or CLI status output.

### Task 11. Docs (AC 10)

- `../../../skills/skill-bill/content.md`: Token Forwarding documents full-run `qa:auto|off` → `--qa <value>` and the usage error when `qa:` reaches a form that does not accept it (verify's `qa:on|off` belongs to subtask 3). Edit only content.md; the generated SKILL.md is install output and is not hand-edited.
- `../../../docs/runtime-command-guidance.md`: line 152 child sequence becomes audit, optional qa, review, validate, write_history; mention qa_fix's three-attempt cap.

### Test obligations (written in implement, run only by validate)

1. `phaseTraversal` keeps the declared qa_fix → audit edge and drops an undeclared cross-slot edge (catches silent edge loss, risk 1).
2. Run-loop test: three findings receipts drive three qa_fix attempts then BLOCK; a resume after attempt 2 continues at attempt 3 without replaying attempt 2 (catches refunded budget or replayed repair).
3. `QaVerdictRule`: skip after findings stays QA_FINDINGS; passed and skipped never route to qa_fix; a malformed receipt throws the typed failure rather than skipping (one test per branch).
4. Invalidation: tree-changing review repair re-enters audit and qa; message-only amend keeps the receipt.
5. Pre-QA resume: recorded "0.2" rev-2 goal-child plan resumes with no qa step; a new launch composes rev 3 with qa (catches `byId` choosing the wrong revision).
6. Selection: resume with `--qa off` against a recorded auto plan is refused; unknown `--qa` value is a UsageError.
7. qa-repair state schema accept and reject parity through the real validator; execution-plan 0.3 schema parity updates in the existing parity test.
8. Audit scoping after qa_fix leaves an unnamed accepted criterion untouched (catches re-checking accepted criteria, risk 3).

No separate tests for step labels, consts, or doc text; existing graph and architecture tests cover those.

### Constraints

- No `//` or block comments in runtime-kotlin; KDoc only on interfaces. Wire keys live once in `*Keys` objects. One composition root. File-size ceilings: new logic goes in new files.
- Non-goals hold: no verify changes, no review entry-gate change, no audit budget change, no model-specific QA variant, no mapping of pre-QA workflows into QA.
- Implement and audit run no build, tests, or check; validate owns `./gradlew check` and the agent-config validators. No install or install-sync commands are part of this plan.

## Validation Strategy

Validate runs the repository's required maintainer checks, including the architecture tests for wire vocabulary, governed seam inventory, comment rules, and production file line ceilings. Implement and audit run nothing.

## Next Path

```bash
skill-bill goal SKILL-419
```
