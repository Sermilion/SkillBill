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

Paths below are relative to `runtime-kotlin/` unless they start with `docs/`, `README.md` or
`skills/`. Anchors come from the SKILL-410 preplan digest; apply each step where the named
behaviour now lives.

### Settled decisions

- **D1. Seed and spec-origin facts.** Add one `DurableWorkflowArtifactFamily` entry, `plan_seed`,
  with a codec beside the existing families such as `FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT`.
  It holds `intake_sha256` (SHA-256 of the trimmed intake text, or null for a bare key),
  `spec_origin` (`seeded` or `operator`) and `spec_sha256` (the spec's hash at open). The
  workflow writes it once, at open. Run invariants are not overloaded with these facts.
- **D2. Where the plan caller lives.** Put a new `@Inject` class `StandalonePlanRun` in a new
  package `skillbill.engine.goalrunner.plan`. It must not go under `featuretask/`: seeding needs
  `GoalIntake` and `GoalIntakePreparation.acceptanceCriteria`, `goalrunner` already depends on
  `featuretask`, and the reverse edge would create a package cycle. It must not go under
  `featuretask/phaserun` either, because `FeatureTaskPhaseRunDefinitionScan` forbids naming
  definitions there. `PhaseCommand` and `GoalRunCommand` both call this one class.
- **D3. Plan-then-goal chain.** Do it in `GoalRunCommand.run`, before `goalRunner.run`, because
  a blocked `GoalRunnerRunReport` exits 3 and AC-006 needs exit 1 with the plan's block reason.
  `GoalRunner.run` still guards on its own: before `intakePreparation.prepare`, if an incomplete
  plan workflow exists for the key, it returns a stopped report naming that workflow and
  `skill-bill <KEY>` and writes no manifest. This covers callers that skip `GoalRunCommand`.
- **D4. Linking on import.** Both stub-step writers call one shared helper. On each of the two
  stub step entries (`preplan` and `plan`) the helper adds an optional `plan_workflow_id`
  field. It also adds a parent artifact `plan_workflow` holding `workflow_id`. The field is
  deliberately not named `workflow_id`, so it cannot be read as the step's own id.
- **D5. Admitted definitions.** Add `SkeletonDefinition.admittedForRun(goalContinuation:
  Boolean): Set<SkeletonDefinition>`, mirroring `forRun`: `{STANDALONE, PLAN}` for false and
  `{GOAL_CHILD}` for true. It lives in `runtime-domain`, keyed on a boolean, so domain gains no
  route-scope import.
- **D6. Operator-spec guard input.** The runloop decodes no `plan_seed` artifact. Instead,
  `FeatureTaskRuntimeRunInput` and `FeatureTaskRuntimeRunRequest` carry a nullable
  `protectedSpecSha256`. It is set only when `spec_origin = operator`. `StandalonePlanRun`
  fills it from its open-time hash, and on resume it fills it from the stored `plan_seed`
  artifact.
- **D7. Execution-plan definition accessor.** Add a read-only accessor next to the artifact
  family, so `runtime-application` can read `definition.id` from the
  `FEATURE_TASK_RUNTIME_EXECUTION_PLAN` artifact without importing the engine
  `FeatureTaskRuntimeExecutionPlanCodec`. Example: `ExecutionPlanArtifactView.definitionId(
  artifacts): String?`. It returns null when the artifact is missing or does not decode. It
  must not expose a public `toPayload` map, because the raw-map guard forbids that.
- **D8. Continuation lookup filter.** `FeatureTaskContinuationLookupService.lookup` gains an
  `admittedDefinition: SkeletonDefinition` filter on the recorded definition id.
  - `feature-task` lookups pass `STANDALONE`, so they never resume a plan row.
  - The plan lookup passes `PLAN`.
  - The explicit `workflowId` resume path in `FeatureTaskRuntimeRunEntry.run` uses
    `require`/coded refusal to check that the caller's definition equals the recorded
    definition.

### Ordered tasks

1. **Definition properties** (AC-001, AC-009). Touches `runtime-domain/.../taskruntime/model/skeleton/SkeletonDefinition.kt`.
   - Set `PLAN` to `SkeletonRunStateKind.DURABLE` and keep `intake = ISSUE_KEY`.
   - Add a constructor property `standaloneInvocable: Boolean = false`. Set it to true on
     `REVIEW`, `VALIDATION`, `PLAN`, `PR` and `MONITOR`.
   - Add `fun requiresSpecBundle(goalContinuation: Boolean) = slots.last() == PhaseSlot.PLAN &&
     !goalContinuation`.
   - Add `admittedForRun` (D5).
   - No `skillbill.text`, `java.nio` or ports imports.
2. **Phase listing** (AC-001). Touches `runtime-cli/.../cli/phase/PhaseCommand.kt`.
   - `PhaseInvocationParser.phaseNames()` filters on `standaloneInvocable`.
   - `definitionId()` keeps the "runs over durable workflow state" text for definitions that
     are not invocable.
   - `PhaseRunEntry.run` keeps `InMemorySkeletonDefinitionRequiredError` unchanged.
3. **One `specBundleRequired` rule** (AC-002).
   - `InMemoryPhaseRunFacts.specBundleRequired` (`featuretask/phaserun/PhaseRunModels.kt`) calls
     `definition.requiresSpecBundle(false)`.
   - Add a nullable `skeletonDefinition` to `FeatureTaskRuntimeRunRequest`.
   - The default `FeatureTaskRuntimeRunFacts.specBundleRequired` (`featuretask/model/core/FeatureTaskRuntimeRunFacts.kt`)
     becomes `skeletonDefinition?.requiresSpecBundle(isGoalContinuationRun) ?: false`.
   - `skeletonDefinitionFor(request)` (`runner/FeatureTaskRuntimeRunnerPolicies.kt`) prefers the
     request's definition before falling back to `forRun`.
4. **Admission sites** (AC-009). Replace the `forRun(scope).id` equality with "recorded
   definition id ∈ `admittedForRun(scope == GOAL_CHILD).map { it.id }`" in three places:
   - `lifecycle/execution/FeatureTaskRuntimeExecutionAdmission.requireMatchingPlan`
   - `lifecycle/core/FeatureTaskRuntimeCrashReconciler.readCandidateAdmission`
   - `lifecycle/continuation/FeatureTaskContinuationLookupService.claim`

   Any other mismatch still throws `IncompatibleFeatureTaskRuntimeExecutionPlanError`.

   *Confirm:* `executionPlanCompatibility.requireSupportedComposition` and
   `requireSupportedRecovery` accept a recorded `plan` descriptor. If they hard-code
   feature-run compositions, extend them keyed on the recorded definition id.
5. **Run input and entry** (AC-002, AC-005). Touches `runner/FeatureTaskRuntimeRunEntry.kt` and
   `FeatureTaskRuntimeRunInput`.
   - Add a nullable `definition` and a nullable `protectedSpecSha256` (D6) to the input.
   - `open()` and `warnIfResumeAssignmentDiffers` use `input.definition ?: forRun(...)`.
   - `routeScope` stays `STANDALONE` for `PLAN`.
   - Thread both fields into `FeatureTaskRuntimeRunRequest`.
   - Apply the explicit-resume definition check (D8).
   - The CLI `FeatureTaskRuntimeRunExecution` passes null, so feature-task behaviour is
     unchanged.

   *Confirm two things:*
   - The run loop does no branch setup, checkpoint ref or commit before the implement slot.
     If branch setup runs at loop entry, gate it on the definition containing a code-editing
     slot.
   - `FeatureTaskRuntimeExecutionPlanResolver.resolveCreation` tolerates a repo with an
     `Absent` gate. If `requireBuildGate` would fail a plan-only run, skip it for definitions
     whose slots contain no implement or validate slot.
6. **Continuation lookup filter** (AC-005, collision risk).
   - Add the `admittedDefinition` filter from D8 to `FeatureTaskContinuationLookupService.lookup`.
   - Make `findStandaloneFeatureTaskCandidates` callers that mean "feature-task standalone" pass
     or apply the `STANDALONE` filter.
   - Update the `testFixtures` port defaults whose signatures change.
7. **`plan_seed` artifact and accessor** (AC-003, AC-005, AC-008). Add the artifact family and
   codec (D1) and the execution-plan definition accessor (D7).
8. **Parent-spec seeding** (AC-002). Touches `featuretask/prepare/FeatureSpecPreparationWriter.kt`.
   - Add `writeParentSpecOnly(repoRoot, issueKey, featureName, requirements,
     acceptanceCriteria): Path`. It writes `.feature-specs/<KEY>-<normalized slug>/spec.md`
     through the existing private `renderParentSpec` and `normalizeFeatureName`, and writes no
     manifest and no subtask specs. Do not call `write`.
   - In `goalrunner/intake/`, add an internal `PlanSpecSeed` helper. It derives the feature name
     from `GoalIntake.parse(text).featureName` and the criteria from
     `GoalIntakePreparation.acceptanceCriteria`, so the seeded spec carries a parseable
     acceptance list.

   *Confirm:* the run-invariants reader parses that list into `Read`. If not, adjust the
   rendered list so it does.
9. **Prompt and pre-launch refusal** (AC-003, AC-004).
   - `PlanningLaunchView.existingBundleReason` (`runloop/attempt/FeatureTaskRuntimeRunLoopHookViews.kt`)
     refuses only when a decomposition manifest exists for the key. Use
     `findMatchingDecompositionManifests` or `resolveDecompositionManifest` in
     `skillbill.application.decomposition`.
   - The reason names `skill-bill <KEY>`.
   - Add `specRewritable: Boolean` to `FeatureTaskRuntimePhasePromptComposeInputs`. Set it at
     `slot/attempt/PhaseLaunchPreparation.kt` to `protectedSpecSha256 == null`.
   - Rewrite `AgentPlanStrategy.BUNDLE_DIRECTIVE` and `SPEC_BUNDLE_REQUIREMENT` (`slot/plan/AgentPlanStrategy.kt`):
     - the governed spec's directory and `spec.md` already exist
     - write the subtask specs and `decomposition-manifest.yaml` into that directory
     - a new slug directory fails verification
     - when `specRewritable` is false, leave `spec.md` byte-for-byte unchanged; when it is
       true, `spec.md` may be rewritten into the full parent spec

   PLAN is now the only bundle-requiring definition, so the old "must not exist yet" wording
   goes away entirely.
10. **Settlement guard** (AC-003). In `runloop/planning/PlanDecompositionStop.authoredBundleRejection`,
    before `PlanBundleAuthorization.violation` and `verifyAuthoredBundle`, add one check. When
    `request.protectedSpecSha256` is non-null and the governed `spec.md` hashes differently,
    return a not-ready reason. The reason names the spec and says operator-authored specs must
    stay unchanged. This happens before `FeatureTaskRuntimePlanningStopper` persists the
    decompose terminal, so no `COMPLETED` row results.
11. **`StandalonePlanRun`** (AC-002 to AC-005). Location per D2. Input: intake text, repo root,
    invoked agent, override, timeout, event sink and model assignment. It returns a sealed
    result: `Completed(bundle, workflowId)`, `Blocked(reason, workflowId)` or
    `Refused(reason)`. Steps, in order:
    1. Resolve the key and invariants with `PhaseRunIntakeResolver.resolve(PLAN, …)`.
    2. Refuse with `Refused` if a decomposition manifest exists for the key. Name
       `skill-bill <KEY>` and open nothing.
    3. Look up an incomplete plan workflow (D8 lookup with `PLAN`, statuses `RUNNING`,
       `BLOCKED`, `PAUSED`).
       - If one is found, check the intake before resuming:
         - A bare key always resumes.
         - A spec-path intake must equal the governed spec path.
         - Free text must hash to the stored `intake_sha256`.
       - On a mismatch, refuse and name the workflow id.
       - Otherwise resume with `explicitWorkflowId`, reading `protectedSpecSha256` from
         `plan_seed`. Recorded invariants and per-step assignment are reused, so the accepted
         `preplan` is not relaunched.
       - `AlreadyRunning` and other ownership outcomes surface the existing ownership reason
         as `Refused`.
    4. When no plan workflow is found, settle the spec:
       - a spec-path intake, or a bare key with an existing `spec.md`, uses that spec as
         `operator`
       - free text with no spec seeds one (task 8) as `seeded`
       - a bare key with no spec is refused with the existing "supply the requirements"
         message
    5. Open through `FeatureTaskRuntimeRunEntry.run` with `definition = PLAN` and the
       governed path. Write `plan_seed` on the new workflow.
    6. Map the decompose terminal to the `PhaseRunSpecBundle` paths.
12. **`PhaseCommand` routing and output** (AC-002, AC-004, AC-005). In `runtime-cli/.../cli/phase/PhaseCommand.kt`,
    route definitions with `runStateKind == DURABLE` to `StandalonePlanRun`, and route the
    others to `PhaseRunEntry` unchanged.
    - `Completed` prints:
      - the `Parent spec:`, `Manifest:` and `Subtask spec:` lines
      - `Workflow ID: <id>`
      - `Next: skill-bill <KEY>`
      - exit 0
    - `Blocked` prints:
      - the reason
      - `Workflow ID:`
      - `Resume: skill-bill phase plan <KEY>` (or `skill-bill <KEY>`)
      - exit 1
    - `Refused` prints the reason and exits 1.
    - Update the command help and argument help (task 16).
13. **Goal resume** (AC-006).
    - `GoalRunCommand.run` (`runtime-cli/.../cli/goal/core/GoalCliCommands.kt`), after
      `admitIntake` and before `goalRunner.run`, checks for an incomplete plan workflow for the
      key.
      - If one exists, it calls `StandalonePlanRun` with the resolved agent, override, model
        assignment, timeout and presenter sink.
      - `Completed` continues into `goalRunner.run` in the same invocation.
      - `Blocked` and `Refused` print the reason and the workflow id and exit 1.
    - `GoalRunner.admitIntake` admits a bare key with an incomplete plan workflow.
    - `GoalRunner.run` gets the D3 guard ahead of `intakePreparation.prepare`.
14. **Discovery and import linkage** (AC-007, AC-008).
    - In `runtime-application/.../decomposition/WorkflowStateRepositoryParentDiscovery.parentDiscoveryCandidate`,
      return null when the D7 accessor yields `plan`. This check comes before the `Corrupt`
      branch. A row that does not decode keeps today's classification.
    - Add `listPlanWorkflowsForPurge(issueKey, repositoryIdentity)` and
      `findCompletedPlanWorkflow(issueKey, repositoryIdentity, manifestPath)`, both filtered on
      the D7 accessor.
    - `findCompletedPlanWorkflow` returns the most recent completed plan row whose decompose
      terminal names the imported manifest path. If the terminal artifact carries no manifest
      path, it falls back to key and identity only; implement must confirm which applies.
    - `WorkflowGoalRunnerManifestLoader.importFromManifestProjection` and
      `DecompositionWorkflowContinuation.bootstrapParentWorkflowFromManifest` use the shared
      D4 helper only when opening a new parent.
      - With no plan row, the stubs stay byte-identical to today.
      - An existing parent is not changed.

    *Confirm:* `WorkflowStepUpdates` and the strict workflow decoder accept the optional
    `plan_workflow_id`. Extend them if not.
15. **Purge** (AC-010). Touches `goalrunner/reset/GoalRunnerPurgeCoordinator.kt`,
    `goalrunner/manifest/WorkflowGoalRunnerPurgePersistence.kt` and `model/GoalRunnerPurgeModels.kt`.
    - `discoverPurgeOwnership` adds `planWorkflowIds` from `listPlanWorkflowsForPurge`.
    - `discover` adds them to `GoalPurgeTarget.workflowIds`, never to `parentWorkflowIds`.
      Directory deletion, the DB purge and the census then cover them.
    - `refusal()` also refuses when a plan id has a live worker. Call
      `resolvePurgeBlockingLiveness(planId, emptyList())`, or a workflow-ids variant if that
      call does not check the id's own lease.
    - `hasStandaloneSibling` excludes plan rows.
    - The deferral on a failed directory delete is unchanged, and `spec.md` is never touched.
    - Output counts plan workflows with the other workflows.

    *Confirm:* `purgeDecomposedGoal` deletes sessions, leases, identities, phase records and
    ledger rows for non-parent `workflowIds`. Extend the SQLite purge if any table is keyed
    only through parents.
16. **Docs and help** (AC-011). Describe `phase plan` as a durable, resumable workflow with
    phase records and run invariants. It stops at the verified bundle and `skill-bill <KEY>`
    continues it; the other phases stay in-memory. Keep the line that the dispatcher never
    invokes `phase plan` itself. Files:
    - `docs/runtime-command-guidance.md` (Phases paragraph, ~line 71)
    - `README.md` (~lines 180-203: phase bullet, `phase:plan` row and example)
    - `runtime-kotlin/ARCHITECTURE.md` (~lines 1290-1371: run-state kind, in-memory phase run,
      `specBundleRequired` and the plan result bundle)
    - the `PhaseCommand` help
    - `skills/skill-bill/content.md` (frontmatter description, the `phase:plan` row ~line 60,
      ~line 168)

### Tests

Each test below is tied to an AC or to a realistic bug, and covers one rule.

- `PhaseInvocationParserTest`: the listing is exactly the five names, and `standalone` keeps
  its error (AC-001).
- `PhasePlanRunTest` (moved to the durable path, beside `StandalonePlanRun`). A fake-agent run
  on a clean key creates:
  - the seeded spec
  - one `standalone`/`plan` row
  - `preplan` and `plan` records, ledger entries and invariants
  - a `COMPLETED` decompose terminal

  The branch and refs are unchanged (AC-002).
- The same suite also covers:
  - a manifest-present key is refused with no row (AC-004)
  - a plan blocked once and resumed with a bare key keeps the `preplan` attempt count at 1
    (AC-005)
  - different intake text is refused and names the id (AC-005)
- `PlanDecompositionStop` settlement:
  - a changed operator spec is rejected and no terminal is recorded
  - a changed seeded spec is accepted (AC-003)
- Admission and crash tests (`FeatureTaskContinuationAdmissionTest` and the crash-reconciler
  test): `standalone` admits `plan`, and `goal_child` with `plan` raises (AC-009).
- Continuation lookup: a `feature-task` lookup never returns a plan row. This catches a full
  run resuming a plan row under the standalone traversal.
- Parent discovery: neither a complete nor an incomplete plan row is returned, including as
  the corrupt fallback (AC-008).
- Goal: an incomplete plan plus a seeded spec resumes and continues in one call, and no
  single-spec manifest is written. A plan that blocks again exits 1 with the reason and id
  (AC-006).
- Goal import: a subtask added after planning appears on the parent, along with the
  `plan_workflow` artifact and the step references (AC-007).
- Purge: a plan-only, incomplete key loses its rows and directories while `spec.md` bytes
  stay the same, and a live plan worker refuses (AC-010).
- Update pinned values without adding new tests. The `runStateKind` trace for plan becomes
  `durable` in `FeatureTaskExecutionPlanCreationTest`, `PhaseStrategyCompositionTest` and the
  slot baselines (`SlotBaselinePhaseRunCapture`). Move the plan cases of
  `PhaseRunIntakeResolverTest` to the durable path.
- No dedicated tests for docs, help text or prompt wording.

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
- The Scope line "run `./install.sh` after editing" `content.md` is not executed in this goal
  child, because goal children never run installers. The operator or the parent runtime
  refreshes the install after merge.
- Refusals for an existing manifest, an intake mismatch, a changed operator spec or ownership
  are returned results or not-ready reasons. None of them adds an exception class.
- kotlin-inject: `StandalonePlanRun` and `PlanSpecSeed` are `@Inject` and are reached through
  the existing components. Check the accessor census before adding a `RuntimeComponent`
  accessor.

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
