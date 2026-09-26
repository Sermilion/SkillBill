# SKILL-380 Subtask 7 - Run loop runs over PhaseRunState

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Every run is a skeleton definition driven by the one run loop (subtask 4). Subtask 8
runs short definitions with no workflow row, so the loop must not touch durable storage
directly. This subtask moves every run-loop read and write behind `PhaseRunState` and
keeps the durable implementation byte-identical. It adds no in-memory implementation.

**Census first.** List every read and write the run loop and its collaborators under
`skillbill.engine.featuretask` make outside strategies. Expected on 2026-09-25, recount
at start:

- workflow snapshot and step status (workflow state store)
- phase records and ledger entries
- run invariants (`freezeRunInvariants` and their decode on resume)
- runtime session rows (`feature_task_runtime_sessions`)
- checkpoints (review, remediation, audit-to-review git checkpoint refs)
- resume reconstruction (`FeatureTaskRuntimeRunStateReconstruction` and state
  validation)
- goal continuation reads
- activity stamps, liveness, and leases
- lifecycle telemetry (`skillbill_feature_task_runtime_finished` inputs)

**Port.** Each item becomes an operation on `PhaseRunState`, or on a cohesive sub-port
it exposes (for example records, checkpoints, telemetry) if one interface would break
the package or function ceilings. The loop receives one `PhaseRunState` per run. The
durable implementation delegates to today's stores, writers, and git operations
unchanged.

**Entry.** The loop's entry takes the skeleton definition, the `PhaseRunState`, and the
per-call facts. `FeatureTaskRuntimeRunRequest` stays the durable entry request: it
builds the durable state and resolves the definition (subtask 4), then calls the loop.
No other code path drives the loop.

**Context-free strategies (moved from subtask 5 on 2026-09-26).** Subtasks 2–4 and 6
built on `FeatureTaskRuntimeRunLoopContext`. On 2026-09-26 that was 32 slot files:
- `PhaseStrategy.runStep`, `PhaseStepHooks`, and `PhaseLoopRules`
- the `slot/attempt` loop, about 2.8k lines
- the codereview (inline and delegated), qualitygate, and commitpush code

Once the port exists:
- Every strategy takes collaborators by constructor and reads and writes run state
  only through `PhaseRunState` and the per-call facts.
- No class under `slot` references `FeatureTaskRuntimeRunLoopContext`.
- The attempt loop either depends only on the slot contract and `PhaseRunState`, or
  it moves out of `slot` into the shared runner package. Either way, both
  dependency-direction rules hold.
- Recount at start.

**Run-loop step identity (moved from subtask 5).** Subtask 5's `census_subtask_5.md` lists
the step-identity references it left in these run-loop internals:
- run-state validation and reconstruction
- output verification and persistence
- record rejection
- status-service phase resolution
- runner launch outcomes and policies

Each reference moves into the owning strategy, behind a `PhaseRunState` operation, or
into a slot or strategy query. The count ends at zero.

**Guard.**
- Add a rule to the engine boundary suite: no class in the run-loop packages depends on
  the durable stores, writers, or git checkpoint operations directly. Only the durable
  `PhaseRunState` implementation does. It asserts it read at least one file and fails
  on a synthetic violation.
- Widen subtask 5's step-identity rule from its step-owned package list to every
  package under `featuretask` outside `slot`. It stays without an exemption or baseline.
- Add the clause "no class under `slot` references `FeatureTaskRuntimeRunLoopContext`"
  to subtask 5's dependency-direction rule, with a synthetic strategy that references
  it.
- Put the before-and-after census for both in `census_subtask_7.md` (see Verification
  ownership). The after counts are zero.
- Update ARCHITECTURE.md to state that strategies are context-free and the
  step-identity rule covers all shared code.

## Verification ownership

The same split as subtask 5 applies. The census lives in
`.feature-specs/SKILL-380-phase-slot-strategies/census_subtask_7.md`: implement writes
it and audit checks it. Criterion 4's fixture comparison and resume parity, and
criterion 7, are verified in validate, which runs the capture command only to confirm
the fixtures are unchanged, then runs `./gradlew check`.

## Acceptance Criteria

1. Every run-loop read and write in the census goes through `PhaseRunState`, and the durable implementation is the only production class under `skillbill.engine.featuretask` that depends on the durable stores, writers, and checkpoint git operations.
2. The loop's entry takes a skeleton definition, a `PhaseRunState`, and per-call facts, and `FeatureTaskRuntimeRunRequest` is only the durable entry that builds them.
3. The new guard fails on a synthetic run-loop file that imports a durable store and passes on the tree.
4. Every subtask 1 fixture matches its latest baseline unchanged, and resume from durable records reconstructs the same state as before (existing reconstruction suites plus one parity test per census item that has none).
5. No class under `skillbill.engine.featuretask.slot` references `FeatureTaskRuntimeRunLoopContext`, and every strategy, including both `code_review` strategies, reads and writes run state only through `PhaseRunState`. The dependency-direction rule fails on a synthetic strategy that references `FeatureTaskRuntimeRunLoopContext` and passes on the tree.
6. The step-identity rule scans every package under `skillbill.engine.featuretask` outside `slot`, with no exemption or baseline, and passes on the tree. `census_subtask_7.md` shows zero step-identity references outside `slot`.
7. `cd runtime-kotlin && ./gradlew check` passes at this subtask's commit (verified in validate).

## Non-goals

- The in-memory `PhaseRunState`, short definitions, and the `phase` CLI (subtask 8).
- Changing any stored byte, checkpoint ref name, lease rule, or telemetry payload.
- Goal-runner code outside the run request it already builds.

## Dependency notes

- Depends on subtask 5 (strategies and the three guard rules) and subtask 4 (definitions
  and traversal). Subtask 6 lands first by id order, and this subtask converts its
  `DelegatedReviewStrategy` too.

## Validation strategy

Catch: a write that bypasses the port; a resume that reconstructs different state; a
checkpoint ref written under a different name. Cover with the guard, the fixture
comparison, and the reconstruction and resume suites over real SQLite. Run
`cd runtime-kotlin && ./gradlew check`, plus the engine, core, and infra-sqlite suites.
Run `bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_8_phase-review-and-validation.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_7_run-loop-over-run-state.md
