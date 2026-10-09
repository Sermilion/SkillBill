# SKILL-410: Durable standalone plan that a full run resumes

## Problem

`skill-bill phase plan <KEY> [description]` runs `preplan` and `plan` in memory. It writes the
spec bundle (parent `spec.md`, subtask specs, `decomposition-manifest.yaml`) and nothing else:
no workflow row, no phase records, no ledger, no run invariants. A crash, timeout or block
loses every completed step, and the run cannot be resumed. The only way forward is to start
`phase plan` again from nothing.

The operator wants to plan now and execute later:

1. `skill-bill phase plan <KEY> <description>` runs `preplan` and `plan` as a proper durable
   phase run with all its records, then stops.
2. Later, `skill-bill <KEY>` continues that work. If planning finished, the full run executes
   the bundle. If planning stopped part-way, the full run finishes planning first, then
   executes.

## Current state (census)

- `SkeletonDefinition.PLAN` (`runtime-domain/.../taskruntime/model/skeleton/SkeletonDefinition.kt:64`)
  is `SkeletonRunStateKind.IN_MEMORY` with `PhaseIntakeRequirement.ISSUE_KEY`.
- `PhaseCommand` (`runtime-cli/.../cli/phase/PhaseCommand.kt`) lists only `IN_MEMORY`
  definitions in `phaseNames()` (line 129). Its help says phases run "with no workflow state".
- `PhaseRunEntry.run` (`runtime-engine/.../featuretask/phaserun/PhaseRunEntry.kt`) throws
  `InMemorySkeletonDefinitionRequiredError` for any other run-state kind. It builds
  `InMemoryPhaseRunState` over `InMemoryPhaseRunRecords`. `InMemoryPhaseRunFacts`
  (`PhaseRunModels.kt:77`) sets `specBundleRequired` when the definition ends at the `plan`
  slot. The durable `FeatureTaskRuntimeRunFacts.specBundleRequired` is hard-coded `false`
  (`FeatureTaskRuntimeRunFacts.kt:78`).
- The planning stopper already supports durable runs. `FeatureTaskRuntimePlanningStopper`
  (`runloop/planning/PlanDecompositionStop.kt`) persists a decompose terminal through
  `PhaseRunRecords.recordDecomposeTerminal`. The durable adapter
  (`runloop/durable/DurablePhaseRunAdapters.kt:214`) delegates to
  `FeatureTaskRuntimeDecomposeTerminalRecorder`, which marks the row `COMPLETED` with the
  decompose-terminal artifact.
- `AgentPlanStrategy`'s pre-launch hook (`slot/plan/AgentPlanStrategy.kt:99`, via
  `FeatureTaskRuntimeRunLoopHookViews.kt:54-57`) blocks a bundle-required plan whenever **any**
  parent spec for the key already exists.
- The durable feature-task route is spec-path driven. `FeatureTaskRuntimeRunEntry.open`
  (`runner/FeatureTaskRuntimeRunEntry.kt`) needs a governed spec path. The execution identity
  contract (`orchestration/contracts/feature-task-execution-identity-schema.yaml`, `0.1`)
  requires `governed_spec_path` matching `^\.feature-specs/...\.md$` and
  `route_scope ∈ {standalone, goal_child}`.
- Admission and crash recovery accept only `SkeletonDefinition.forRun(routeScope)`:
  `FeatureTaskRuntimeExecutionAdmission.requireMatchingPlan` (~line 194) and
  `FeatureTaskRuntimeCrashReconciler` (~line 148). A row whose recorded plan names any other
  definition raises `IncompatibleFeatureTaskRuntimeExecutionPlanError`.
- Goal parent discovery (`runtime-application/.../decomposition/WorkflowStateRepositoryParentDiscovery.kt`,
  `parentDiscoveryCandidate`, line 59) classifies every non-terminal row for the issue key that
  has no decomposition manifest as a **Corrupt** parent candidate. `findDecomposedParentOrCorruptFallback`
  returns it as a fallback, and `importFromManifestProjection`
  (`WorkflowGoalRunnerManifestLoader.kt:92`) then imports onto it.
- A full run with a bundle on disk and no parent row imports it.
  `GoalRunner.run` → `GoalIntakePreparation.prepare` → `manifestStore.loadByIssueKey` →
  `importFromManifestProjection` opens a parent row, writes `preplan` and `plan` as completed
  stubs, and leaves it `PAUSED` at `plan`. `DecompositionWorkflowContinuation.bootstrapParentWorkflowFromManifest`
  (line 114) does the same. The stub records reference no real planning run.
- `GoalIntakePreparation.prepare` treats an existing `spec.md` without a manifest as
  single-spec intake and writes a one-subtask manifest from it. `GoalRunner.admitIntake`
  admits a bare key only when a spec or a stored parent exists; otherwise it asks for
  requirements.

## Decisions

- **One generic runner.** The durable plan runs through the existing durable run loop
  (`FeatureTaskRuntimeRunEntry` / `FeatureTaskRuntimeRunner` with `skeletonDefinition = PLAN`)
  and the existing durable `PhaseRunState`. Do not add a plan-only run path, run state or
  record store. `PhaseRunEntry` stays the in-memory entry for the remaining in-memory
  definitions.
- **Definition.** `SkeletonDefinition.PLAN` becomes `SkeletonRunStateKind.DURABLE`. Whether the
  `phase` command can invoke a definition becomes its own property of `SkeletonDefinition`
  (for example `standaloneInvocable`), separate from `runStateKind`. `phase` lists `review`,
  `validation`, `plan`, `pr` and `monitor`, the same set as today. `specBundleRequired` derives
  from the definition (it ends at the `plan` slot and is not a goal continuation) for both
  durable and in-memory facts, from one shared rule.
- **Route scope.** A plan workflow uses route scope `standalone`. Admission and crash
  reconciliation accept the definitions allowed for a scope: `standalone` admits `standalone`
  and `plan`, and `goal_child` admits `goal-child`. The execution identity contract does not
  change.
- **Seeded parent spec.** For free-text intake, the runtime writes
  `.feature-specs/<KEY>-<slug>/spec.md` from the intake before it opens the workflow. Slug
  derivation and the writer are the ones goal intake preparation already uses; this step writes
  the parent spec only, with no manifest and no subtask specs. The seeded spec is the
  workflow's governed spec path and claims the key on disk, so local key allocation (which
  reads `.feature-specs/`) cannot hand out the key again. The workflow records that the runtime
  seeded the spec and the seed's SHA-256.
- **Existing parent spec.** The plan's pre-launch refusal changes from "a parent spec exists" to
  "a decomposition manifest exists for the key". A spec-path intake (an existing `spec.md`
  without a manifest) is therefore planned into a bundle around that spec. The plan agent may
  rewrite a runtime-seeded `spec.md` into the full parent spec, as it authors `spec.md` today.
  It must leave an operator-authored `spec.md` byte-for-byte unchanged, and the runtime rejects
  the plan output if it changed.
- **Stop point.** Planning stops when the bundle is verified. On success the existing decompose
  terminal marks the plan workflow `COMPLETED`. The command prints the bundle paths, the plan
  workflow id and `skill-bill <KEY>` as the next command. Nothing creates the goal parent at
  plan time and no goal planning sweep runs, so the operator can still edit the bundle,
  including adding, removing or splitting subtasks, until the full run launches.
- **Resume of an incomplete plan.** A `RUNNING`, `BLOCKED` or crash-recovered plan workflow is
  resumable. Both `skill-bill phase plan <KEY>` and `skill-bill <KEY>` admit it and continue
  from its last incomplete step, using the run invariants and the per-step model and agent
  assignment it recorded. A bare key is enough. Intake text that differs from the recorded
  intake is refused with a message naming the workflow id. Two concurrent invocations for one
  plan workflow are serialized by the existing worker ownership: the second is refused.
- **Full run after planning.** `skill-bill <KEY>` checks for an incomplete plan workflow before
  it treats a manifest-less `spec.md` as single-spec intake. If one exists, it resumes the plan
  run. When that plan completes, the same invocation continues through today's bundle import
  and goal run. When it blocks, the invocation stops with the plan's block reason, the plan
  workflow id and exit 1. With a completed plan and a bundle on disk, today's import path runs
  unchanged.
- **Goal parent links its plan.** When the goal imports a bundle and a completed plan workflow
  exists for the same key and repository identity, the parent records that plan workflow id as
  a parent artifact. Its `preplan` and `plan` step records reference that workflow instead of
  being anonymous stubs. Without a plan workflow (hand-written bundles, bundles planned before
  this change), import stays as it is today.
- **Discovery.** Goal parent discovery skips rows whose recorded execution plan names the `plan`
  definition. A plan workflow is never a goal parent, valid or corrupt.
- **Ownership and purge.** A goal owns every plan workflow for its issue key and repository
  identity, linked or not. Goal purge removes them along with their sessions, leases, execution
  identities and repo-local tracking and run-evidence directories. Purge leaves `spec.md` alone,
  including a runtime-seeded one.
- **No branch work.** A plan run creates or switches no branch, writes no checkpoint ref and
  makes no commit. It runs on the checked-out branch, as today.
- **Direct plan.** A plan that does not decompose still blocks, as today.

## Acceptance Criteria

1. `skill-bill phase plan <KEY> <description>` opens a durable task-runtime workflow for the
   key, records `preplan` and `plan` phase records, ledger entries and run invariants, writes
   and verifies the spec bundle, marks the workflow completed with its decompose terminal, and
   prints the bundle paths, the workflow id and `skill-bill <KEY>`. It creates no branch,
   checkpoint ref or commit.
2. Free-text intake seeds `.feature-specs/<KEY>-<slug>/spec.md` before the workflow opens. That
   path is the workflow's governed spec path. A spec-path intake without a manifest is planned
   around the existing spec and leaves it byte-for-byte unchanged. Plan output that changes an
   operator-authored spec is rejected.
3. A plan run that blocks, times out or crashes after `preplan` resumes at `plan` with
   `skill-bill phase plan <KEY>` or `skill-bill <KEY>`, reusing the accepted `preplan` record.
   Different intake text for the same key is refused and names the workflow.
4. `skill-bill <KEY>` with an incomplete plan workflow finishes planning and then continues
   into the goal run in the same invocation. If planning blocks again, it exits 1 with the
   plan's block reason. It never writes a single-spec manifest for that key.
5. `skill-bill <KEY>` after a completed plan imports the bundle as it is on disk at launch,
   including operator edits made after planning. The goal parent records the plan workflow id,
   and its `preplan` and `plan` step records reference that workflow.
6. Goal parent discovery never returns a plan workflow, complete or incomplete, as a parent or
   a corrupt-parent fallback.
7. Admission and crash reconciliation admit a `standalone`-scope row whose recorded plan names
   the `plan` definition and still reject any other definition mismatch.
8. Goal purge removes every plan workflow for the key and repository identity, and their owned
   rows and directories. It leaves `spec.md` unchanged.
9. `phase` still lists exactly `review`, `validation`, `plan`, `pr` and `monitor`. The other
   phases stay in-memory with unchanged behaviour.
10. `docs/runtime-command-guidance.md`, `README.md`, `runtime-kotlin/ARCHITECTURE.md`, the
    `phase` command help and the `skills/skill-bill/content.md` description describe `phase
    plan` as a durable, resumable run that a full run continues.

## Non-Goals

- Running the goal planning sweep (shared preplan and per-subtask plans) at plan time.
- Changing the feature-task execution identity contract or adding a route scope.
- Turning a direct (non-decomposing) plan into a one-subtask bundle.
- New telemetry event types. The plan run emits what the durable run loop already emits.
- `goal status` reporting for incomplete plan workflows.
- Re-planning an already-planned key in place. A manifest for the key still blocks `phase plan`.

## Execution Rule

The single subtask runs on the current tree. If goal purge already discovers owned state by
issue key and repository identity (SKILL-409), extend that discovery with plan workflows.
Otherwise add plan workflows to whatever purge discovery exists. Anchors named in the census
may have moved; apply each decision wherever the named behaviour now lives.

## Subtasks

1. `spec_subtask_1_durable-standalone-plan.md`: the whole change in one commit. A durable plan
   run that the full run cannot resume, or a resume path with no durable plan to resume, would
   leave planned work stranded, so the pieces only make sense together.

## Next Path

```bash
skill-bill goal SKILL-410
```
