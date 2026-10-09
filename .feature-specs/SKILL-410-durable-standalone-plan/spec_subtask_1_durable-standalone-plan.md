# SKILL-410 Subtask 1: Durable standalone plan that a full run resumes

Parent spec: `.feature-specs/SKILL-410-durable-standalone-plan/spec.md`. Read its "Current state"
and "Decisions" sections first. This subtask implements all of them.

## Scope

Make `skill-bill phase plan <KEY> [description]` a durable, resumable phase run that stops after
the spec bundle is verified. Make `skill-bill <KEY>` continue it: finish an incomplete plan, then
run the goal over the bundle.

Owned paths (elided segments are `...`):

- `runtime-domain/.../taskruntime/model/skeleton/SkeletonDefinition.kt`: `PLAN` run-state kind
  and the new standalone-invocation property.
- `runtime-cli/.../cli/phase/PhaseCommand.kt`: phase listing, help text, routing `plan` to the
  durable entry, result output.
- `runtime-engine/.../featuretask/phaserun/`: `PhaseRunEntry` (keeps in-memory definitions
  only), `PhaseRunIntakeResolver` (intake → key and invariants, reused by the durable plan),
  `PhaseRunModels.kt` (`specBundleRequired` rule).
- `runtime-engine/.../featuretask/model/core/FeatureTaskRuntimeRunFacts.kt` (`specBundleRequired`).
- `runtime-engine/.../featuretask/runner/FeatureTaskRuntimeRunEntry.kt` and
  `FeatureTaskRuntimeRunnerPolicies.kt` (`skeletonDefinitionFor`): opening and admitting a
  workflow whose definition is `plan`.
- `runtime-engine/.../featuretask/lifecycle/execution/FeatureTaskRuntimeExecutionAdmission.kt`
  (`requireMatchingPlan`) and `.../lifecycle/core/FeatureTaskRuntimeCrashReconciler.kt`:
  definitions allowed per route scope. Also `.../lifecycle/continuation/FeatureTaskContinuationLookupService.kt`
  if it repeats the `forRun` check.
- `runtime-engine/.../featuretask/slot/plan/AgentPlanStrategy.kt` and
  `.../runloop/attempt/FeatureTaskRuntimeRunLoopHookViews.kt` (`existingBundleReason`):
  manifest-based refusal and the operator-authored spec guard.
- `runtime-engine/.../featuretask/runloop/planning/PlanDecompositionStop.kt`: settlement check
  for the operator-authored spec.
- `runtime-engine/.../goalrunner/GoalRunner.kt` (`admitIntake`, `run`) and
  `.../goalrunner/intake/GoalIntakePreparation.kt` (`prepare`, `existingSpecPath`): incomplete
  plan detection and resume before single-spec preparation.
- `runtime-engine/.../goalrunner/manifest/WorkflowGoalRunnerManifestLoader.kt`
  (`importFromManifestProjection`) and `runtime-application/.../decomposition/DecompositionWorkflowContinuation.kt`
  (`bootstrapParentWorkflowFromManifest`): linking the completed plan workflow.
- `runtime-application/.../decomposition/WorkflowStateRepositoryParentDiscovery.kt`
  (`parentDiscoveryCandidate`, `listDecomposedParentsForPurge`): excluding plan workflows from
  parent discovery and including them in purge ownership.
- `runtime-engine/.../goalrunner/reset/GoalRunnerPurgeCoordinator.kt` and the SQLite purge
  (`SQLiteUnitOfWork.purgeDecomposedGoal` or its current replacement): removing plan workflows.
- Port defaults in `testFixtures` whose interfaces change.
- Docs: `docs/runtime-command-guidance.md` (Phases paragraph), `README.md` (phase bullet near
  line 180 and the `phase:plan` table row), `runtime-kotlin/ARCHITECTURE.md` (the run-state
  kind and phase-run paragraphs near lines 1290-1365), and `skills/skill-bill/content.md`
  (frontmatter description; run `./install.sh` after editing it).

## Implementation Tasks

1. **Definition and listing.** Make `SkeletonDefinition.PLAN` `DURABLE`. Add a
   standalone-invocation property on `SkeletonDefinition`. Set it for `REVIEW`, `VALIDATION`,
   `PLAN`, `PR` and `MONITOR`, and have `PhaseInvocationParser.phaseNames()` read it instead of
   `runStateKind`. The error text for durable-only definitions such as `standalone` and
   `goal-child` stays. `PhaseRunEntry` keeps refusing non-in-memory definitions.
2. **One `specBundleRequired` rule.** Move the "definition ends at the `plan` slot and the run is
   not a goal continuation" rule to one place both fact types use. Durable facts for a `PLAN` run
   then return `true`. `STANDALONE` and `GOAL_CHILD` runs still return `false`.
3. **Route-scope definitions.** Replace the single `forRun(routeScope)` equality in admission and
   crash reconciliation with the set of definitions a scope admits: `standalone` admits
   `standalone` and `plan`, and `goal_child` admits `goal-child`. Opening a new workflow keeps
   `forRun` unless the request names `PLAN`.
   *Assumption to confirm:* `executionPlanCompatibility.requireSupportedComposition` and
   `requireSupportedRecovery` accept a recorded plan whose definition is `plan`. If they
   hard-code feature-run compositions, extend them the same way, keyed on the recorded
   definition.
4. **Seed and open.** In the durable plan entry, resolve the key and invariants with
   `PhaseRunIntakeResolver`. Then:
   - Spec-path intake, or a bare key whose bundle has `spec.md` but no manifest: the governed
     spec path is that spec. Record `seeded=false` and its SHA-256.
   - Free-text intake with no spec on disk: write `.feature-specs/<KEY>-<slug>/spec.md` from the
     intake with the writer and slug rule goal intake preparation uses. Write the parent spec
     only, with no manifest and no subtask spec. Record `seeded=true` and its SHA-256.
   - A manifest already exists for the key: refuse before opening, with today's existing-bundle
     reason, and name `skill-bill <KEY>` as the way to continue.
   - An incomplete plan workflow exists for the key and repository identity: admit it instead of
     opening a new one (task 6).
   Open the workflow through `FeatureTaskRuntimeRunEntry` with route scope `standalone` and
   `skeletonDefinition = PLAN`, and persist the run invariants built from the intake. Record the
   seed flag and hash as a workflow artifact so resume and settlement can read them.
   *Assumption to confirm:* the durable run loop starts no branch setup, checkpoint ref or commit
   for a definition whose last slot is `plan`. If branch setup runs at loop entry rather than at
   the implementation slot, gate it on the definition containing a slot that edits code.
5. **Existing-spec guard.** In the plan pre-launch hook, refuse only when a decomposition
   manifest exists for the key, not when a parent spec exists. At plan settlement, for
   `seeded=false`, reject the output with a not-ready reason when `spec.md`'s hash differs from
   the recorded one. For `seeded=true`, the agent may rewrite `spec.md`.
6. **Resume an incomplete plan.** A plan workflow for the key and repository identity in
   `RUNNING`, `BLOCKED` or `PAUSED` status (including a crash-reconciled one) is incomplete.
   `phase plan <KEY>` with a bare key, or with intake equal to the recorded `specReference`,
   resumes it through the existing durable continuation, using its recorded invariants and
   per-step assignment. Different intake text is refused with a message naming the workflow id.
   Worker ownership serializes concurrent invocations: the second one exits non-zero with the
   existing ownership reason.
7. **Phase output.** A completed plan prints the parent spec, manifest and subtask spec paths, the
   plan workflow id and `Next: skill-bill <KEY>`, and exits 0. A blocked plan prints the block
   reason, the workflow id and `Resume: skill-bill phase plan <KEY>` (or `skill-bill <KEY>`), and
   exits 1.
8. **Discovery excludes plan workflows.** In `parentDiscoveryCandidate`, return `null` for a row
   whose recorded execution plan names the `plan` definition, before the corrupt-candidate
   branch. Decode the execution plan artifact defensively. A row whose plan cannot be decoded
   keeps today's classification.
9. **Full run resumes planning.** In `GoalRunner.admitIntake`, admit a bare key that has an
   incomplete plan workflow even when no requirements are supplied. In `GoalRunner.run`, before
   `GoalIntakePreparation.prepare` (and therefore before any single-spec manifest is written),
   look up an incomplete plan workflow for the key and repository identity. If there is one, run
   the durable plan continuation from task 6 with the goal request's agent and timeout. On
   completion, continue into today's import and goal run in the same invocation. On block,
   return a stopped report with the plan's block reason, its workflow id and
   `lastResumableStep` set to the plan step, so the CLI exits 1.
10. **Link on import.** When `importFromManifestProjection` or `bootstrapParentWorkflowFromManifest`
    opens a new parent, look up the most recent completed plan workflow for the key and
    repository identity whose decompose terminal names the manifest being imported. If found,
    record its id as a parent artifact and reference it from the `preplan` and `plan` step
    updates. If not found, write today's stubs. An existing parent is not changed.
11. **Purge.** Extend purge ownership with every plan workflow for the key and repository
    identity, complete or not, linked or not. Remove them with the same row coverage and
    repo-local directory deletion purge applies to goal workflows. Do not touch `spec.md`.
    Purge output counts plan workflows with the other workflows.
12. **Docs.** Update the files listed under Scope. `phase plan` is now a durable workflow with
    phase records and run invariants. It stops after the verified bundle, resumes when
    incomplete, and is continued by `skill-bill <KEY>`. The other phases stay in-memory. Keep the
    statement that standalone phases are operator tools and that the dispatcher never invokes
    `phase plan` on its own.

## Implementation Details

### Constraints

- One generic runner: no plan-only run loop, run state, record store or settlement path. The
  durable `PhaseRunState` and its adapters serve the plan run unchanged, apart from the
  definition-driven rules above.
- Do not change the feature-task execution identity contract, add a route scope, or bump any
  contract version.
- Do not run the goal planning sweep at plan time, and do not create the goal parent at plan
  time.
- Results for expected outcomes; `require`/`check` for defects; no new custom exception classes.
- Implement runs no builds, tests, `./gradlew check` or installers. The build and validate phases
  own those.
- Validate must watch these architecture guards: the `RuntimeEngineInboundApiTest` allowlist, the
  `PackageSiblingCountArchitectureTest` ceilings, the raw-map guard, the kotlin-inject accessor
  census, the custom-exception baseline, and `RuntimeEngineBoundaryArchitectureTest`'s planning
  step rules.

## Acceptance Criteria

1. `SkeletonDefinition.PLAN.runStateKind` is `DURABLE`. `phase` lists exactly `review`,
   `validation`, `plan`, `pr` and `monitor` through the standalone-invocation property, and
   `PhaseRunEntry` still runs the in-memory ones.
2. `skill-bill phase plan <KEY> <description>` on a clean key seeds `spec.md`, opens one
   task-runtime workflow with route scope `standalone` and definition `plan`, and persists
   `preplan` and `plan` phase records, ledger entries and run invariants. It verifies the bundle,
   records the decompose terminal with status `COMPLETED`, prints the bundle paths, the workflow
   id and `skill-bill <KEY>`, and exits 0. It creates no branch, checkpoint ref or commit.
3. A spec-path intake without a manifest plans around that spec. Plan output that changes an
   operator-authored `spec.md` is rejected at settlement. A runtime-seeded `spec.md` may be
   rewritten.
4. `phase plan <KEY>` refuses before opening a workflow when a manifest for the key exists, and
   names `skill-bill <KEY>`.
5. A plan workflow that blocked, timed out or was crash-reconciled after an accepted `preplan`
   resumes at `plan` through `phase plan <KEY>` or `skill-bill <KEY>`, without relaunching
   `preplan`. Intake text that differs from the recorded intake is refused and names the
   workflow id.
6. `skill-bill <KEY>` with an incomplete plan workflow never writes a single-spec manifest. It
   finishes the plan and continues into the goal run in the same invocation, or exits 1 with
   the plan's block reason and workflow id.
7. `skill-bill <KEY>` after a completed plan imports the manifest as it is on disk at launch,
   including subtask edits made after planning. The new parent records the plan workflow id and
   references it from its `preplan` and `plan` step records. A bundle with no plan workflow
   imports exactly as today.
8. Goal parent discovery never returns a plan workflow, complete or incomplete, as a valid or
   corrupt parent.
9. Admission and crash reconciliation admit a `standalone` row recorded with definition `plan`
   and still raise `IncompatibleFeatureTaskRuntimeExecutionPlanError` for other mismatches,
   such as a `goal_child` row recorded with `plan`.
10. Goal purge removes every plan workflow for the key and repository identity, with its owned
    rows and repo-local directories, and leaves `spec.md` byte-for-byte unchanged.
11. The docs, the `phase` help text and the `skills/skill-bill/content.md` description match the
    new behaviour, and none still says `phase plan` writes no workflow row or cannot be resumed.

## Test Obligations

- AC 2: catches a plan that still runs in memory. Assert the workflow row, phase records,
  ledger, invariants and `COMPLETED` decompose terminal exist after a fake-agent plan run, and
  that the branch and refs are unchanged.
- AC 3: catches a plan agent that rewrites an operator-authored spec.
- AC 5: catches a resume that re-runs `preplan` or loses the recorded invariants. Block the
  fake `plan` once, resume with a bare key, and assert the `preplan` attempt count stays 1.
- AC 6: catches the shipped `GoalIntakePreparation` path writing a single-spec manifest over a
  seeded spec while a plan workflow is incomplete.
- AC 7: catches a parent import that ignores operator edits made between plan and launch. Add a
  subtask to the manifest after planning and assert the goal parent has it.
- AC 8: catches the corrupt-candidate fallback adopting a plan row as the goal parent.
- AC 9: catches an admission rule widened to accept any definition.
- AC 10: catches a purge that leaves a manifest-less, incomplete plan workflow behind.
- Not tested separately: doc and help text (AC 11), and the phase listing beyond one parser
  assertion (AC 1).
- Mocks use `relaxUnitFun = true`, never `relaxed = true`. Tests that build environments pass an
  explicit non-empty map, because `emptyMap()` falls back to the host environment.

## Non-Goals

- No goal planning sweep at plan time, and no goal parent created at plan time.
- No execution identity contract change, new route scope or contract version bump.
- No one-subtask bundle for a direct plan.
- No new telemetry event types.
- No `goal status` changes for incomplete plan workflows.
- No in-place re-planning of a key that already has a manifest.

## Dependency Notes

None. This is the only subtask, and it runs on the current tree. If goal purge already discovers
owned state by issue key and repository identity (SKILL-409), extend that discovery. Otherwise
extend whatever discovery purge uses. Anchors named here may have moved; apply each task where
the named behaviour now lives.

## Validation Strategy

- The build phase runs the pack build command.
- The validate phase runs the pack validation gate and `./gradlew check`.
- Run Gradle from a local clone, not a linked `git worktree`, because spotless fails there.
- If spotless reports a stale JVM-local cache, rerun with `--no-configuration-cache`.
- Implement and audit do not run builds or tests.

## Next Path

```bash
skill-bill goal SKILL-410
```
