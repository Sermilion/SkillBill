# SKILL-425 Subtask 1 - Intake slot

Parent spec: [spec.md](./spec.md). Read its Model, Decisions and Execution rule first.
Issue key: SKILL-425

## Scope

Move intake into a slot. Add `PhaseSlot.INTAKE` as the first slot in canonical order with one step,
`intake`, and make the `plan` skeleton `[INTAKE, PREPLAN, PLAN]`. Fill the slot with a runtime
strategy that runs no agent, following the pattern of `RuntimeCommitStrategy`. It reuses the existing
intake code: spec resolution in `GoalIntakePreparation`, `FeatureSpecPreparationWriter.writeParentSpecOnly`,
and the prose rule from commit `9e5f26d30`.

The strategy writes or keeps the parent `spec.md` and nothing else:

- An operator spec with an acceptance list that `GovernedSpecSectionParser` can read is kept byte for
  byte. Its SHA-256 stays the protected spec hash, so the plan step may not change it.
- An operator spec without such a list becomes prose intake. The runtime writes a spec at the same
  path and quotes the original text in it.
- Free text or resolved tracker requirements seed a spec at `.feature-specs/<KEY>-<slug>/spec.md`.

The workflow must open before the spec exists. Derive the governed spec path from the intake before
writing any file, open the workflow with that path, and read run invariants after the intake step
completes. Today `FeatureTaskRuntimeRunEntry` reads them at open (review mode) and before the runner
starts.

Remove the pre-skeleton seed call in `StandalonePlanRun.open` (`intakePreparation.seedPlanSpec`).
Keep the plan seed artifact (`FEATURE_TASK_RUNTIME_PLAN_SEED`): the intake step records the spec
origin and hashes that `open` records today. Keep refusing a supplied spec that is not the governed
spec for the key.

Owned paths (elided segments are `...`):

- `runtime-domain/.../taskruntime/model/skeleton/PhaseSlot.kt`, `SkeletonDefinition.kt`
- `runtime-domain/.../taskruntime/model/core/FeatureTaskRuntimePhaseIds.kt`
- `runtime-engine/.../featuretask/slot/` (new intake strategy package), `slot/skeleton/SkeletonStrategyBindings.kt`
- `runtime-engine/.../featuretask/runner/FeatureTaskRuntimeRunEntry.kt`
- `runtime-engine/.../goalrunner/plan/StandalonePlanRun.kt`
- `runtime-engine/.../goalrunner/intake/GoalIntakePreparation.kt` (only the code the strategy reuses)
- `runtime-core/.../di/featuretask/RuntimeFeatureTaskSlotProvides.kt`

## Acceptance Criteria

1. `PhaseSlot.INTAKE` exists, is first in canonical order, and owns one step, `intake`.
2. `SkeletonDefinition.PLAN` is `[INTAKE, PREPLAN, PLAN]`, and `SkeletonStrategyBindings` binds the intake strategy to `INTAKE` for it.
3. The intake strategy runs no agent and writes or keeps only the parent `spec.md`. It never writes a manifest or a subtask spec.
4. An operator spec with a readable acceptance list is unchanged after intake, and a plan step that changes its bytes is refused as today.
5. An operator spec without a readable acceptance list is replaced by a runtime spec at the same path that contains the original text as a quote and an acceptance list the run-invariants reader accepts.
6. Free-text intake produces a seeded spec at `.feature-specs/<KEY>-<slug>/spec.md`, using today's slug rule.
7. A plan workflow opens before its spec exists, and run invariants are read only after the intake step completes.
8. `StandalonePlanRun` no longer calls `seedPlanSpec` before the workflow opens, and the plan seed artifact still records spec origin and hashes.
9. Resuming a plan workflow that stopped after intake does not rerun intake or change the spec.
10. Tests cover criteria 3 to 9, including a table-form acceptance section like SKILL-424's.

## Non-Goals

- No change to the full run's intake (`GoalIntakePreparation.prepare`). Subtask 2 owns it.
- No change to the `plan` slot's strategy or steps. Subtask 3 owns it.
- No tracker lookup in the runtime.

## Dependency Notes

None. This subtask runs on the current tree. If the `plan` slot already owns two steps, add `INTAKE`
in front of it unchanged. If the full run already runs the plan skeleton, intake applies there too
through the same skeleton.

## Implementation Details

All paths are under `runtime-kotlin/`. `R-engine` is `runtime-engine/src/main/kotlin/skillbill/engine`
and `R-domain` is `runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime`. Lines marked
**Assumption** are decisions taken without a fact from the preplan digest. Implement confirms each one
in the code and keeps the stated end state if the code differs.

### Settled design decisions

- **Names.** The strategy is `RuntimeIntakeStrategy` with `ID = "runtime-intake"`, in the new package
  `R-engine/featuretask/slot/intake/`. That mirrors `RuntimeCommitStrategy` / `"runtime-commit"`. It has
  no Opus variant, because intake runs no agent.
- **One resolution, two callers.** The read-only half of today's `seedPlanSpec` becomes a pure
  resolution: given the issue key, intake and repo root, decide the spec path and the branch, and
  write nothing. The three branches are `KeepOperator(specPath, sha256)`,
  `ProseWrap(specPath, originalText)` and `Seed(specPath, featureName)`. `StandalonePlanRun.open` calls
  it before the workflow opens, and the intake step calls it again when it writes. The write half is
  only reachable from the intake step.
- **"Readable acceptance list" means the run-invariants reader accepts it.** The keep-or-wrap test
  must use the same parsing that the run-invariants reader applies to `## Acceptance Criteria`.
  Today that is `GovernedSpecSectionParser.parseListSection` with `ACCEPTANCE_CRITERIA_PREFIX`. If the
  run-invariants reader turns out to use a different parser, switch to that one. A kept spec then
  always passes the invariant read, and a spec that would fail the read is always wrapped.
- **Protected hash.** The open-time resolution supplies `protectedSpecSha256` exactly as today: the
  file's SHA-256 for `KeepOperator`, and `null` for `ProseWrap` and `Seed`. A prose-wrapped spec is
  written by the runtime, so it is rewritable like a seeded one. `PlanDecompositionStop.protectedSpecRejection`
  stays untouched and keeps refusing a plan step that changes a kept operator spec (AC-004).
- **Governed-path refusal stays before open.** The open-time resolution keeps today's refusal of an
  `OPERATOR` spec outside `.feature-specs/<KEY>-*/`, so a refused intake never opens a workflow. It
  also keeps the `invalidIntake("parent_spec", …)` errors from `existingSpecPath`.
- **Plan seed artifact.** The intake step writes `FEATURE_TASK_RUNTIME_PLAN_SEED` with the unchanged
  `FeatureTaskRuntimePlanSeed(intakeSha256, specOrigin, specHash)` shape. `specHash` is the post-intake
  file hash. Origin is `OPERATOR` for a kept spec and `SEEDED` for a seeded or prose-wrapped spec,
  because the runtime authored the prose-wrapped one. The shape does not change, so no migration and
  no `DesktopReadSchema` bump are needed. `open` stops writing the seed through `openArtifacts`.
- **Intake is idempotent.** The runtime records the `SEEDED` seed before it writes the spec, so a
  crash between the write and the final record cannot make the retry read the runtime-written spec as
  an operator spec. A recorded seed finishes intake when its origin is `OPERATOR`, or when the spec at
  the governed path has a readable acceptance list. Then the step completes and writes nothing, and a
  `SEEDED` seed whose hash is stale is refreshed. A `SEEDED` seed whose spec still lacks the list means
  the write never happened, so intake writes it. A completed `intake` step is never rerun on resume,
  so resume never reverts planning output (AC-009).
- **Plan workflows opened before this change.** `SkeletonDefinition.PLAN` moves to
  `semanticRevision = 2`. A plan workflow recorded with the old `[PREPLAN, PLAN]` composition is
  refused with `unsupported_descriptor` and must be restarted with `skill-bill phase plan <KEY>`.
  The recorded-plan mapping cannot bridge a definition revision, so no migration is provided.
- **Where the step gets its intake.** **Assumption:** the run request opened by `StandalonePlanRun`
  already carries the issue key and the supplied intake text that the intake step needs. If it does
  not, add one nullable request field for the raw plan intake. Legacy rows decode it as absent, and a
  plan workflow with no field and no seed is refused on resume with a message naming
  `skill-bill phase plan <KEY>`.
- **Execution binding kind.** `PhaseStrategy.executionBindingKind` gets an explicit `INTAKE` case
  that maps to the same no-agent runtime kind that `COMMIT_PUSH` uses. **Assumption:** the kind that
  `COMMIT_PUSH` maps to is that runtime kind. If `COMMIT_PUSH` maps to a commit-specific kind, add a
  `RUNTIME` kind used by intake only.
- **Capability boundary.** The strategy does no I/O itself. Its `runStep` casts the accepted
  execution to a new narrow `PhaseIntakeStepBinding` (next to `PhaseCommitStepBinding` in
  `R-engine/featuretask/slot/state/PhaseStepBinding.kt`) and calls `runIntake(...)`. The run-loop
  bindings implement that by delegating to the intake service. Those wiring files lie outside the
  owned paths. They are required production wiring, and per the parent's Execution rule they are in
  scope.

### Ordered tasks

1. **Slot and step id (AC-001).**
   - Add `INTAKE = "intake"` to `R-domain/model/core/FeatureTaskRuntimePhaseIds.kt`.
   - Add `PhaseSlot.INTAKE` as the first enum entry in `R-domain/model/skeleton/PhaseSlot.kt`, owning
     `listOf(FeatureTaskRuntimePhaseIds.INTAKE)`, and follow the existing entries' constructor shape.
   - **Assumption:** no persisted data stores `PhaseSlot.ordinal`. Implement checks for `.ordinal` uses
     on `PhaseSlot`. If one is persisted, move that use to the slot name.
   - If a durable phase workflow definition lists the step ids (`FeatureTaskRuntimePhaseWorkflowDefinition`
     or equivalent), register `intake` there with the same declaration shape that `commit_push` uses.
   - Tests: update `PhaseSlotTest` so that its canonical-order assertion starts with `INTAKE`. Add no
     new test.
2. **Plan skeleton (AC-002).**
   - In `R-domain/model/skeleton/SkeletonDefinition.kt`, set `PLAN` to `[INTAKE, PREPLAN, PLAN]` and
     leave its run settings unchanged.
   - Leave `STANDALONE`, `GOAL_CHILD` and `GOAL_PLANNING` unchanged. `FEATURE_RUN_SLOTS` does not gain
     `INTAKE` in this subtask.
   - `requiresSpecBundle` keys on `slots.last()`, so it is unaffected.
   - Tests: update `SkeletonDefinitionTest` so that its `PLAN` slot and step-id assertion matches. Add
     no new test.
3. **Intake service (AC-003, 005, 006).** Extract the plan-intake code from
   `R-engine/goalrunner/intake/GoalIntakePreparation.kt` into an `@Inject` class `PlanSpecIntake` in
   `R-engine/featuretask/prepare/`, next to `FeatureSpecPreparationWriter`.
   - `resolve(issueKey, intake, repoRoot): PlanSpecResolution` is read-only. It reuses
     `existingSpecPath`, `hasAcceptanceList` (rule from the design decisions), `newWorkFeatureName`
     and today's slug rule.
   - `apply(resolution, intake, repoRoot): PlanSpecIntakeResult(specPath, origin, specSha256)` does the
     writing:
     - `KeepOperator` writes nothing and returns the hash.
     - `ProseWrap` writes a runtime spec at the same path. It quotes the original text with the
       `asProse` blockquote rule from `9e5f26d30`, and its acceptance list comes from the existing
       `acceptanceCriteria` helper.
     - `Seed` calls `writeParentSpecOnly(repoRoot, decision, featureName, VALIDATION)`, as
       `seedPlanSpec` does today.
   - **Assumption:** `writeParentSpecOnly` can target an existing path. If it cannot, `ProseWrap`
     renders through the same parent-spec renderer and writes through the existing file store. The
     prose-wrapped spec's acceptance list must be non-empty and must parse with the run-invariants
     reader.
   - `GoalIntakePreparation` keeps its public helpers (`issueKeyForExistingSpec`, `issueKeyOf` and
     `missingNewWorkInput`) and delegates the shared helpers to `PlanSpecIntake`.
   - `prepare` keeps its behavior and output byte for byte, because that is a non-goal owned by
     subtask 2. Delete `seedPlanSpec` and `PlanSpecSeed` once task 6 removes their last caller.
   - The service never calls `FeatureSpecPreparationWriter.write(...)`, and it never writes a manifest
     or a subtask spec.
4. **Strategy and step binding (AC-002, 003).**
   - Add `R-engine/featuretask/slot/intake/RuntimeIntakeStrategy.kt` modeled on `RuntimeCommitStrategy`:
     - `slot = PhaseSlot.INTAKE` and one step, `intake`, which is also `entryStep`.
     - `PhaseStepPolicy(mutating=false, singleAgentSession=false, readOnlyIdle=false, fileMutating=true, generationScoped=false)`.
     - A short `DIRECTIVE`.
     - `briefingInvariantFields` empty, because intake runs before invariants exist.
     - `runStep` casts to `PhaseIntakeStepBinding` and calls `runIntake`.
     - Resume and loop rules are the defaults. A completed `intake` record is never relaunched.
   - Declare `PhaseIntakeStepBinding` in `slot/state/PhaseStepBinding.kt` the way `PhaseCommitStepBinding`
     is declared. Implement it in `R-engine/featuretask/runloop/state/FeatureTaskRuntimeRunLoopStepBindings.kt`:
     - If no plan seed exists, call `PlanSpecIntake.resolve` then `apply`, and persist the seed
       artifact.
     - If a seed exists, follow the idempotency rule.
     - Complete the step with a short prose value naming the spec path and origin.
   - Add the `INTAKE` case to `PhaseStrategy.executionBindingKind`.
5. **Bindings and DI (AC-002).**
   - In `R-engine/featuretask/slot/skeleton/SkeletonStrategyBindings.kt`, bind
     `PhaseSlot.INTAKE to PhaseStrategyBinding.Fixed(RuntimeIntakeStrategy.ID)` for `PLAN` without
     `withOpus`. No other skeleton holds the slot.
   - In `runtime-core/.../di/featuretask/RuntimeFeatureTaskSlotProvides.kt`, register
     `RuntimeIntakeStrategy` in `phaseStrategyRegistry` with `runner()`, and provide `PlanSpecIntake`
     wherever the run-loop bindings are constructed.
   - Update `RuntimeFeatureTaskSlotProvidesTest`, `PhaseStrategyCompositionTest`, `TestPhaseStrategies.kt`
     / `TestExecutionPlan.kt` fixtures and any registry or allowlist in
     `StrategyCapabilityBoundaryArchitectureTest` / `RuntimeEngineInboundApiTest` that enumerates
     strategies, binding interfaces or slots. Edit them only to include the new members, and never to
     weaken a rule.
6. **Open before the spec exists (AC-007, 008).**
   - In `R-engine/goalrunner/plan/StandalonePlanRun.kt` `open`, replace `intakePreparation.seedPlanSpec`
     with `PlanSpecIntake.resolve`, which writes nothing.
   - Open the workflow with `resolution.specPath`, `definition = SkeletonDefinition.PLAN`, and
     `protectedSpecSha256` from the resolution.
   - Drop the seed from `openArtifacts`.
   - `resume` keeps refusing an intake whose sha256 differs from the recorded seed. When no seed exists
     yet (the run stopped before intake completed), compare against the intake carried by the run
     request.
   - `resume` takes `protectedSpecSha256` from the recorded seed when `specOrigin == OPERATOR`.
7. **Read run invariants after intake (AC-007).**
   - In `R-engine/featuretask/runner/FeatureTaskRuntimeRunEntry.kt`, skip both invariant reads (the
     review-mode one at open and the pre-runner one) while the definition holds `INTAKE` and its
     `intake` step is not completed.
   - Read the invariants once, when the first step after `intake` is prepared.
   - **Assumption:** the pre-launch preparation for a step (`slot/attempt/PhaseLaunchPreparation.kt`
     or the runner prepare path) is where a deferred read fits without a new run path. If invariants
     are cached on the run context at open, make the cache lazy rather than adding a second runner.
     Skeletons without `INTAKE` keep today's read timing.
8. **Tests (AC-010).** Keep them few, each tied to a realistic bug.
   - **Intake service unit tests** go in `GoalIntakePreparationTest` or a new `PlanSpecIntakeTest`. Each
     uses a temp repo root, checks bundle directory contents, and leaves out tests for trivial glue.
     1. Kept operator spec (AC-003, 004): bytes stay identical, origin is `OPERATOR`, the hash equals
        the file hash, and the bundle directory gains no manifest or `spec_subtask_*`. It catches an
        intake that rewrites or normalizes an operator spec, or still writes a bundle.
     2. Spec without an acceptance list (AC-003, 005): the same path now holds a spec that contains
        the original text as a blockquote, the run-invariants reader returns a non-empty acceptance
        list, and no manifest exists. It catches a wrap that drops the original text or produces a
        spec the invariant reader rejects.
     3. Table-form acceptance section modeled on SKILL-424 (AC-010): after intake the run-invariants
        reader returns a non-empty acceptance list, and the original is preserved, either byte-identical
        or quoted. Implement pins the branch the parser actually takes. It catches the SKILL-424
        failure class, where a table spec ends up with no readable acceptance criteria.
     4. Free-text intake (AC-006): the spec lands at `.feature-specs/<KEY>-<slug>/spec.md` with the
        slug today's rule produces. Migrate the existing `seedPlanSpec` slug test, if one exists,
        rather than adding a sibling.
   - **Plan run tests** go in `PhasePlanRunTest`, with a run-loop fake if real agents are needed. The
     fake records the state each step sees.
     5. AC-007, 008: the plan workflow row exists and records the derived spec path while the spec file
        is still absent. The intake step creates the file. The seed artifact records the origin, intake
        hash and spec hash. The invariant read happens only after intake completes, which the fake
        observes as no invariant-read failure for a spec that did not exist at open. It catches
        pre-open seeding and an invariant read at open.
     6. AC-009: run to after intake, change the seeded spec's bytes the way a plan step would, then
        resume. The spec keeps the changed bytes and the seed artifact is unchanged. It catches a rerun
        of intake on resume that reverts planning output.
     7. AC-004, plan step protection: if no existing test already proves that a plan step changing a
        kept operator spec is refused, add one here through the new open path. Otherwise rely on the
        existing test and name it in the implementation summary.
   - Update the existing tests that the slot change breaks (tasks 1, 2 and 5), and do not duplicate
     them.

### Constraints

- Authored Kotlin follows `AGENTS.md`: no line comments, and KDoc only on interfaces. `PhaseIntakeStepBinding`
  gets KDoc if its sibling bindings carry it.
- No change to `GoalIntakePreparation.prepare` behavior, the `plan` slot's strategy or steps,
  `PlanDecompositionStop`, or tracker lookup.
- No new run path or runner. Intake runs through the generic run loop like `commit_push`.
- Persisted rows stay readable. The seed artifact shape is unchanged, and the only possible new
  request field is nullable and decodes as absent on legacy rows. This adds no database column, so no
  migration is needed.
- Implement and audit run no build, test or check. The build and validate phases own that.

## Validation Strategy

- The build phase runs the pack build command.
- The validate phase runs the pack validation gate and `./gradlew check`.
- Implement and audit do not run builds or tests.

## Next Path

Complete the goal and prepare its pull request.
