# SKILL-380 Subtask 4 - Skeleton definitions, slot traversal, and the quality_gate strategies

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Resolves investigation F-003, and makes every run a skeleton definition.

**Skeleton definitions (runtime-domain, beside `PhaseSlot`).**
- `SkeletonDefinition` is a value: an id, an ordered list of slots that is a subset of
  the nine canonical slots in canonical order. A slot always runs all its steps, so a
  phase is self-sufficient in every definition. Definitions are code, declared once in
  runtime-domain. No
  reordering, no slot outside the nine, no operator-supplied definition.
- This subtask declares two: `standalone` (all nine slots) and `goal-child` (every slot
  but `pull_request`). `goal-child` replaces the `takeWhile { it != PHASE_PR }` in
  `FeatureTaskRuntimeRunnerPolicies`. Subtasks 8, 9, and 10 add the short and
  goal-planning definitions.
- The transition declaration is derived from the definition:
  - forward order: the definition's steps in canonical order
  - entry gates: kept only when both the gated step and the required step are in the
    definition (review's gate on audit `satisfied` does not apply when audit is absent)
  - backward edges: kept only when both ends are in the definition (today the only one,
    `review_fix`, lies inside `code_review`)
  - loop-only steps: as declared today
- A durable run resolves its definition the way it tells goal children apart today: a
  run with a goal continuation is `goal-child`, every other run is `standalone`. No
  stored byte records the definition id; resume re-derives it the same way.
- For `standalone` and `goal-child`, the derived declaration equals today's
  `FeatureTaskRuntimePhaseWorkflowTransitions` output exactly (one equality test per
  definition), so ledger and transition bytes stay identical.
- `PhaseStrategySelection` becomes keyed by definition: `(definition, slot) → strategy
  id`, plus the review-mode mapping.

**Slot traversal.** Entering a slot resolves to the selected strategy's entry step.
Leaving a slot resolves to the next slot in the definition, or ends the run after the
last one.

Subtask 2 registered one temporary `routed-quality-gate` wrapper. This subtask
replaces it with two strategies and deletes transition rewriting.

**Strategies.**
- `pack-build` owns build: `FeatureTaskRuntimeBuildGateCoordinator`, triage, repair
  turns, cache-bypassing verify, `findings_open` persistence, and the absent-gate
  fallback.
- `agent-validate` owns validate: the validation gate coordinator, the shrink loop,
  triage, and the fallback variants.
- Both run their steps through `PhaseRunner`, and so do their triage and repair
  sessions: the runner reaches the shared `GoalRunnerSubtaskLauncher` port, and the
  strategies do not touch it. `findings_open`, repair counts, and the captured triage
  plan are read and written through `PhaseRunState`, so `pack-build` runs unchanged in
  the `validation` definition in subtask 8.
- The gate cycles move out of the run loop. `runDeclaredBuildGateCycle`,
  `runDeclaredValidationGateCycle`, and their step-specific helpers leave
  `runloop/settlement` for the strategy packages. A strategy whose `runStep` is one
  call back into run-loop code does not satisfy this subtask.
- Delete `routed-quality-gate`.

**Traversal is generic.** Entering and leaving a slot works the same way for every
slot.

- For each slot in the definition, the forward walk visits the selected strategy's
  steps.
- Traversal, selection, and lookup contain no reference to `quality_gate`, build, or
  validate.
- Nothing rewrites a computed transition afterwards (no `next.copy(phaseId = …)`).
- No public set of quality-gate step ids replaces the routing. Readers that must know
  whether a step belongs to the selected gate ask the lookup.

**Selection.** The skeleton entry of `PhaseStrategySelection` for `quality_gate`
resolves from today's selection value: `BUILD` selects `pack-build`, `VALIDATE`
selects `agent-validate`. `FeatureTaskRuntimeQualityGateSelection` stays as the
goal-runner and continuation wire value; this is today's internal rule, not an
operator strategy choice.

**Loud selection values.**
- `FeatureTaskRuntimeQualityGateSelection.fromWire` stops mapping unknown values to
  VALIDATE.
- `--quality-gate-selection` becomes a closed choice. An unknown flag or
  `SKILL_BILL_QUALITY_GATE_SELECTION` value exits as a usage error naming `build` and
  `validate`. Emit it on stderr when the CLI has a stderr channel, otherwise on the
  diagnostic channel the CLI uses now.
- A legacy continuation row with no selection keeps today's healed-to-VALIDATE path
  with its field-adoption record.
- Delete the silent defaults, or turn each into a recorded degradation:
  `orLegacyValidate()` and `?: VALIDATE` readers in `FeatureTaskRuntimeStatusService`
  and the run-loop transitions. Goal-runner repair readers stay.

**Uniform output for validate.** validate stops emitting a structured envelope and
settles with the parent "Phase input and output" shape:

- `completed` means every required project check passes; `blocked` carries the
  remaining failures in its prose value.
- The shrink decision reads the verdict: the agent compares its remaining failures with
  the previous attempt's value (handed to it as input) and sets `progress` or
  `no_progress`. An absent or unknown verdict counts as `no_progress` and emits a
  record.
- `produced_outputs.validation_passed` and the remaining-failure set parsing are
  deleted. Census every reader (on 2026-09-25: `FeatureTaskRuntimeRunLoopPhaseAttempts`,
  `FeatureTaskRuntimeRunLoopAttemptSettlement`, `FeatureTaskRuntimeRunStateValidation`,
  `FeatureTaskRuntimeValidationGateCoordinator`, `FeatureTaskRuntimePhaseProjectionShapes`,
  and `GoalRunnerStatusProjectionAssembler`) and derive it from the step status. The
  goal status projection keeps its `validation_passed` field and bytes.
- Settlement: add validate to the steps the MCP settlement tools accept, and add the
  optional `verdict` to `feature_task_phase_block` (the settlement request already has
  the field). `failure_disposition` stays the retry-on-resume enum and does not carry
  the progress signal.
- build is runtime-owned: its gate results are measured by the runtime already. Its
  triage and repair sessions return the uniform output, and the captured triage plan
  is their prose value.
- Re-baseline the validate prompt, phase record, and consuming handoff fixtures in this
  commit (parent fixture ledger). Bump each changed handoff projection contract.
  Validate records written before this change still decode.
- If this subtask bumps the phase-output contract version, follow the parent
  "Contract bumps" rule. The first attempt missed
  `goal-planning-preparation-schema.yaml` `provenance.phase_output_contract_version`
  (`const: "0.6"`), which broke every goal planning run.

**Routing removal.**
- Delete `FeatureTaskRuntimeQualityGateRouting`.
- Projection filtering in
  `FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationForQualityGate`, the handoff
  declaration checks, and the status skip rule key on the selected strategy's steps
  instead of the selection enum.
- Delete `FeatureTaskRuntimeQualityGateRoutingTest` (runtime-domain tests) with the
  routing, moving any behaviour it proves into the strategy tests, and census other tests
  that pin the routing.

## Acceptance Criteria

1. `SkeletonDefinition` exists in runtime-domain with `standalone` and `goal-child`. The transition declaration derived from each equals today's, a definition that reorders slots fails with a typed error, and `FeatureTaskRuntimeRunnerPolicies` no longer special-cases `pull_request`.
2. The build and validate behaviour listed in Scope lives in the `pack-build` and `agent-validate` packages, and `routed-quality-gate` and `FeatureTaskRuntimeQualityGateRouting` no longer exist.
3. The runtime-core registry provider lists two `quality_gate` strategies.
4. A goal child whose continuation selects BUILD runs build and not validate, the final goal child runs validate and not build, and a standalone run runs validate.
5. Transitions, ledger entries, projections, and every other subtask 1 fixture match unchanged in each case.
6. `--quality-gate-selection biuld` and `SKILL_BILL_QUALITY_GATE_SELECTION=biuld` each exit as a usage error naming `build` and `validate`, and no production code maps an unknown selection to VALIDATE. A legacy continuation row without a selection still heals to VALIDATE with an adoption record.
7. Both strategies read and write gate state only through `PhaseRunState`.
8. validate settles with the uniform output, its shrink decision reads only the verdict, and no production code reads `validation_passed` from agent output. The validate fixtures differ from the subtask 1 baseline only by the ledger's allowed change, and a run whose validate record predates the change resumes.
9. Slot traversal, `PhaseStrategySelection`, and `PhaseStrategyLookup` contain no reference to a specific slot or step. No code rewrites a transition after it is computed. The build and validate cycles no longer exist under `runloop`.
10. Goal planning still runs end to end (the goal-planning suites and the subtask 1 goal-planning fixtures pass), and `cd runtime-kotlin && ./gradlew check` passes at this subtask's commit.

## Non-goals

- Operator-facing strategy selection.
- Changing any gate's repair turns, caps, or prompts (F-007 stays open).
- Goal-runner code that stamps the selection.

## Dependency notes

- Depends on subtask 2. Independent of subtask 3.

## Validation strategy

The regressions to catch:

- a quality-gate child running both gates or neither
- the absent-gate fallback lost in the move
- a resumed goal child switching gate
- a typo silently running the wrong gate
- a legacy continuation failing to resume
- the shrink loop continuing forever on a missing verdict
- a pre-change validate record failing to decode on resume

What covers them: the existing gate and goal-runner suites over real SQLite, the
fixture comparison for all three runs, one resume-parity test per gate where the
suite has none, CLI usage-error tests, and a legacy-continuation test over a
pre-change database.

Run `cd runtime-kotlin && ./gradlew check`, plus the engine, core, CLI, and
infra-sqlite suites. Run `bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_5_remaining-slots-and-guard.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_4_skeleton-definitions-and-quality-gate.md
