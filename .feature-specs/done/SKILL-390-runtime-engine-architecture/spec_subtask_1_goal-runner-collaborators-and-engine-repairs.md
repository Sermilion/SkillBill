# SKILL-390 Subtask 1 - goal-runner-collaborators-and-engine-repairs

Parent spec: [.feature-specs/SKILL-390-runtime-engine-architecture/spec.md](spec.md)
Issue key: SKILL-390

## Scope

Covers F-001, F-004, F-005, F-006, the recovery forwarders of F-007, and F-008 from the parent overview.

Touches:
- runtime-engine main `goalrunner`: execution.core, launch, planning.context, planning.attempt, planning.outcome, planning.state, planning.sweep, status, persist, reset, repair, and the recovery importers;
- the featuretask files that import goalrunner;
- the issue-key sites in work and featuretask.lifecycle;
- runtime-domain `FeatureTaskExecutionIdentityPolicy`;
- runtime-core repoTest `RuntimeEngineBoundaryArchitectureTest` and `baselines/runtime-engine-package-cycle-baseline.txt`;
- the engine test factories that build the deleted bags (GoalRunnerTestFactory and its callers).

Changes:
- Delete the five goal-runner bags and the two status-projection bags. Each consumer takes the collaborators it reads, as private constructor parameters:
  - GoalRunner: 10.
  - GoalRunnerSubtaskLaunchPrepare: 7.
  - GoalRunnerFinalization: 8.
  - GoalRunnerPerRunLoopAssembler: unpacking gives 13 parameters. Pass GoalRunnerIterationPendingState as a call argument; it has 2 reads in each of GoalRunnerIterationOutcome and GoalRunnerSelectedSubtaskLoop. Those classes then become injectable, and the assembler shrinks or goes away.
- Turn DefaultGoalPlanningSweep's receiver and parameter functions into members, or into @Inject step classes in the same packages. Each step takes only what it reads: the shared-preplan settlement and production, the planning attempt gate and attempt recording, subtask plan production, and run progress.
- Move GoalRunnerFinalization's and GoalRunnerStatusProjectionAssembler's same-file receiver functions into their classes as private members.
- Add one non-validating canonical issue-key function to FeatureTaskExecutionIdentityPolicy. `normalizeIssueKey` calls it, and so does every engine trim-uppercase issue-key site.
- Measure the planning attempt duration with the injected Clock instead of System.nanoTime.
- Delete DurableChildRecoveryClass.kt; its importers use skillbill.engine.recovery directly.
- Remove the featuretask-to-goalrunner edge:
  - drop the 6 unused `goalrunner.status.completed` imports;
  - move protectedBranchName and its protected-branch set into featuretask.lifecycle.branch;
  - move GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY into featuretask.persist;
  - empty the engine package-cycle baseline.
- Delete the inert default-public visibility rule and its two fixtures from RuntimeEngineBoundaryArchitectureTest.

## Acceptance Criteria

1. runtime-engine main declares no type whose name ends in `Boundaries`. GoalRunnerBoundaries.kt and GoalPlanningSweepBoundaries.kt no longer exist, and neither GoalRunnerStatusProjectionDataSources nor GoalRunnerStatusProjectionValidationDependencies is declared anywhere.
2. GoalRunner, GoalRunnerSubtaskLaunchPrepare, GoalRunnerFinalization, DefaultGoalPlanningSweep, GoalRunnerStatusProjectionAssembler, GoalRunnerPerRunLoopAssembler (if it remains) and every @Inject class added under goalrunner.planning each have at most 12 constructor parameters. Each parameter is `private val` or a plain parameter. None of these classes declares a public or internal property that returns one of its constructor collaborators.
3. No function or property in runtime-engine main declares DefaultGoalPlanningSweep, GoalRunnerFinalization or GoalRunnerStatusProjectionAssembler as its receiver or as a parameter type, and no call passes `this` of those classes to a top-level function.
4. runtime-engine main contains no `.trim().uppercase()` and no `?.trim()?.uppercase()` applied to an issue key. Each former site calls one FeatureTaskExecutionIdentityPolicy function that returns the trimmed, uppercased key without validating it, and `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey` returns its result through that same function.
5. runtime-engine main contains no `System.nanoTime`. The planning attempt duration is computed from the injected `Clock`, and `GoalPlanningSweepConstants.NANOS_PER_MILLI` is gone if nothing references it.
6. An engine test runs one planning attempt with a clock that advances by a fixed number of milliseconds while the launch runs, and asserts that the recorded attempt `durationMs` equals that number. This catches a start time read after the launch or from a different clock, both of which record 0.
7. `goalrunner/persist/DurableChildRecoveryClass.kt` no longer exists. GoalOperatorDecisionService, GoalRunnerResetReplanCoordinator, GoalPlanningRecoveryKind, GoalRunnerReAttemptCause, GoalRunnerRepairCoordinator and GoalPlanningRecoveryClassificationTest import their recovery symbols from `skillbill.engine.recovery`.
8. No file under runtime-engine main `skillbill/engine/featuretask` imports `skillbill.engine.goalrunner.*` or `skillbill.engine.work.*`. `protectedBranchName` and its protected-branch set are declared in `skillbill.engine.featuretask.lifecycle.branch`, and `GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY` is declared in `skillbill.engine.featuretask.persist`.
9. `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/runtime-engine-package-cycle-baseline.txt` contains no rows.
10. RuntimeEngineBoundaryArchitectureTest.kt no longer declares the default-public top-level declaration method, its two `visibility census` fixture methods, or the private `topLevelPublicDeclarations` helper. Its run-loop acyclicity methods and the other engine boundary guard classes remain.
11. The subtask adds no architecture-test class, baseline row, detekt suppression, typealias or module, and it adds no file to a package that already holds 12 or more Kotlin files.

## Non-Goals

- Feature-task gates, FeatureTaskRuntimeRunner visibility and the engine inject-property guard, which belong to subtask 2.
- Moving test packages, which belongs to subtask 3.
- Typing the engine's public raw-map declarations, unifying preflight add-on resolution, or renaming contracts `normalizeIssueKey`.
- Moving goal-runner declarations between modules. SKILL-393 places the ports declarations; this subtask edits whatever packages they end up in.
- Changing persisted bytes, CLI or MCP output, recovery command text, or any FeatureTaskRuntime* name.

## Dependency Notes

Depends on: none
This subtask waits for no other issue. These bundles touch the same code; if one has landed, rebase onto it first, and otherwise implement against the current tree (whichever lands second keeps both edits):
- SKILL-387 rewrites GoalPlanningPhaseAttemptGate and GoalPlanningSubtaskPlanProduction, where DefaultGoalPlanningSweep receiver functions live;
- SKILL-388;
- SKILL-389 edits ArchitectureScanSupport and the scanner inventories;
- SKILL-392 subtask 2 rewrites the issue-key derivation at FeatureTaskRuntimeRunner:69 and FeatureTaskRuntimeExecutionEntry:35. Whichever lands second routes the derivation through the one FeatureTaskExecutionIdentityPolicy canonical function;
- SKILL-393 subtask 1 deletes the engine typealiases over ports types and moves goal-runner persistence models into engine.

AC 8 and AC 9 need the featuretask-to-work edge gone. Its 18 imports all use the `IdeStatusCurrentPhaseExecution` and `IdeStatusCurrentPhaseExecutionKind` aliases in `engine/work/model/IdeStatusModels.kt`. If SKILL-393 subtask 1 has not landed, this subtask makes that bundle's edit for these two aliases only: point the featuretask imports at the runtime-ports types directly and delete the two aliases. Add no alias. Both types stay in runtime-ports under SKILL-393 (the IdeStatusSnapshot tree), so the edits agree in either order.

Before implementing, recount the receiver functions, the issue-key sites and the featuretask imports of goalrunner and work, because those bundles move lines. FeatureTaskExecutionIdentityPolicy belongs to runtime-domain (SKILL-397); the added function is this bundle's only domain edit, and whichever bundle lands second keeps it.

## Validation Strategy

Build compiles runtime-domain, runtime-engine, runtime-core, runtime-cli and runtime-mcp, which proves kotlin-inject resolution of the unpacked goal-runner and sweep constructors. Validate runs the full check:
- the goal-runner and goal-planning engine suites, including the new duration test;
- the slotbaseline goal-planning capture suites (byte-identical persisted artifacts);
- the FeatureTaskExecutionIdentityPolicy domain tests;
- runtime-core repoTest: package-cycle drift against the empty engine baseline, RuntimeEngineBoundaryArchitectureTest, RuntimeEngineInboundApiTest and ambient-clock drift;
- the runtime-cli goal suites and the runtime-mcp parity tests;
- detekt and spotless.

## Implementation Details

This plan uses the upstream preplan digest as its repository evidence. Preserve every existing section of this spec. The digest supersedes the older implementation estimates for execution work: direct injection gives GoalRunner 11 parameters, launch preparation seven, finalization eight, and status projection 12 after deleting its unread validation dependency. There are 19 inline issue-key sites, no remaining featuretask-to-work imports, and an additional ambient-time default in GoalRunnerTickProgressReader. No dependency work is required. Do not repeat preplan discovery during planning.

For the tasks below, `E` means `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine`, `T` means `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/`, and `A` means `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`. Execute the tasks in order within this subtask. These are implementation steps, not a new decomposition.

### 1. Unpack goal execution collaborators and preserve per-run state

Serves AC-001, AC-002, AC-003 and AC-011.

Replace the three bags in `Egoalrunner/execution/core/GoalRunnerBoundaries.kt` with direct private dependencies in `Egoalrunner/GoalRunner.kt`, `Egoalrunner/launch/GoalRunnerSubtaskLaunchPrepare.kt`, and `Egoalrunner/execution/core/GoalRunnerFinalization.kt`, then delete the bag file. GoalRunner needs the manifest store, outcome store, planning sweep, telemetry emitter, clock, diagnostics and execution coordinator alongside its four existing non-bag collaborators. Do not inject the nullable phase query or findings ledger service here. Launch preparation needs only the bag's manifest store, outcome store and git operations alongside its four existing collaborators. Replace forwarding getters with private constructor fields; do not retain collaborator accessors.

Convert finalization's same-file receiver functions to private members. Cover reconciliation, dirty-worktree commit, stage-and-push, clean-worktree verification, unpushed-branch handling, feature-branch checks, scratch deletion and ledger resolution. Preserve result values, failure text, ordering, best-effort operations and visibility needed by generated cross-module DI.

Make `GoalRunnerIterationOutcome` and `GoalRunnerSelectedSubtaskLoop` injectable behaviour owners in their existing execution-core files. Pass pending state through execution calls rather than capturing it in constructors. Keep the one mutable pending-state instance created and bound by `GoalRunner.runPrepared`. Selected-subtask execution reads pending reattempt cause and causing-loop entry; iteration outcome updates them and validation-quality retry counts. Shrink `GoalRunnerPerRunLoopAssembler.kt` to coordination if it still performs work, or delete it if direct coordination makes it redundant. Do not create a replacement collaborator factory. Keep durable sequence allocation, the per-run loop-count cache, cancellation propagation and read-only phase queries intact.

Update `Tgoalrunner/execution/core/GoalRunnerTestFactory.kt`, `../../../runtime-kotlin/runtime-engine/src/testFixtures/kotlin/skillbill/engine/goalrunner/execution/core/GoalRunnerSharedTestFactory.kt`, and affected construction callers as each constructor changes. Later validation runs existing goal execution and child-recovery suites. These rewiring changes require no new test that asserts constructor structure or collaborator calls.

### 2. Make status projection own its operations

Serves AC-001, AC-002, AC-003 and AC-011.

In `Egoalrunner/status/GoalRunnerStatusProjectionAssembler.kt`, delete GoalRunnerStatusProjectionDataSources and GoalRunnerStatusProjectionValidationDependencies. Delete the unread validation dependency instead of unpacking it. Inject the five actual data sources privately: manifest store, outcome store, read-only phase query, attempt ledger store and database session factory. Retaining the other seven parameters gives 12.

Move the 15 same-file receiver functions into the assembler, including runtime-input construction, worktree edit summaries, audit retry measurement, completed-subtask validation, planning-status alignment, manifest reconciliation, liveness, active-agent resolution and requested diff projections. Preserve externally called `project` and `resolveExecutionLiveness`. Neither functions nor properties may take the assembler as a receiver or parameter, and no top-level helper may receive its `this`. Update existing status test construction; later validation runs status-degradation coverage. Preserve durable-read failure reporting and read-only authority.

### 3. Replace planning bags and sweep locators with behaviour owners

Serves AC-001, AC-002, AC-003 and AC-011.

Convert existing function families in their current files and packages, then delete `Egoalrunner/planning/sweep/GoalPlanningSweepBoundaries.kt`. Keep `GoalPlanningSweep.prepare(state, request)` unchanged. DefaultGoalPlanningSweep coordinates private behaviour owners, strategy selection, execution-plan assembly and the run-loop entry, with at most 12 parameters. Do not unpack all 15 dependencies into the sweep or introduce another service collection.

Use the existing `Egoalrunner/planning/context/GoalPlanningSharedPreplanProduction.kt`, `GoalPlanningSharedPreplanSettlement.kt` and `GoalPlanningSharedPreplanSettlementEnvelope.kt` for production and settlement ownership. Keep context discovery and preplan production together with their manifest-file store, invariant source, context discovery, repository-root port and checkpoint. Keep preplan recovery and settlement with checkpoint and production operations they use.

Use the existing `Egoalrunner/planning/attempt/GoalPlanningPhaseAttemptGate.kt`, `GoalPlanningPhaseAttemptGateBurstCap.kt`, `GoalPlanningPhaseAttemptGateLaunch.kt`, `GoalPlanningPhaseAttemptGateSettlement.kt` and `GoalPlanningAttemptRecording.kt` for attempt control. Group behaviour by actual reads: manifest controls, attempt and rejection recording, timing, burst schedule and clock. Keep `composePlanningPrompt(args)` a pure helper. Give launch behaviour its manifest-store authorization dependency, preserve briefing writes before launch, and keep the authorization lifetime unchanged.

Use `Egoalrunner/planning/outcome/GoalPlanningSubtaskPlanProduction.kt` and `GoalPlanningSubSpecSnapshot.kt` for plan production and snapshot admission with checkpoint and governed-spec reads. In `Egoalrunner/planning/state/GoalPlanningRunProgress.kt`, replace the stored DefaultGoalPlanningSweep with the specific production, settlement and checkpoint operations plus the repository-root, manifest-file, descriptor and pause collaborators it actually uses. Removing receiver syntax alone does not satisfy AC-003.

Implementation must confirm the exact class names and dependency subsets while converting these existing files. The digest establishes the behaviour grouping but does not prescribe new owner names. Keep every added injected planning owner within 12 private or plain parameters; expose operations rather than dependencies. Add no file to a full package. Preserve prose phase content, runtime-owned decisions, authoritative sub-spec handoff, governed-path and hash checks, and legacy extraction restricted to persistence and recovery. Do not derive executable plans from response text, restore response-envelope validation, or overwrite the parent spec.

Adapt planning factories and callers, including the existing concrete-sweep binding in `../../../runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/goal/RuntimeGoalPlanningSweepProvides.kt` if constructor wiring requires it. Later validation runs planning sweep, planning recovery and goal-planning persistence/capture coverage. Preserve existing regression tests; do not add owner-by-owner wiring tests.

### 4. Use one domain issue-key derivation

Serves AC-004 and AC-011.

Add `canonicalIssueKey(issueKey: String): String` to `../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/model/FeatureTaskExecutionIdentityPolicy.kt`, returning the existing non-validating `issueKey.trim().uppercase()` expression. Keep normalizeIssueKey's original-input validation and error text, then return canonicalIssueKey's result. Do not use or rename the different contracts-level normalizer.

Replace all 19 digest-listed engine derivations: launch preparation; purge twice and reset/replan; planning status coherence; shared-preplan production three times and the sweep; `Ework/IdeStatusRepositoryCorrelation.kt` three times and `IdeStatusLivenessAnchors.kt`; `Efeaturetask/runner/FeatureTaskRuntimeRunner.kt`; continuation lookup twice; execution admission; and crash reconciliation twice. Use nullable `let` where needed and retain existing blank filtering, `takeIf`, early returns and uppercase semantics. Preserve FeatureTaskRuntimeRunEntry and execution-entry identity ownership.

Later validation runs the existing `../../../runtime-kotlin/runtime-application/src/test/kotlin/skillbill/application/FeatureTaskExecutionIdentityPolicyTest.kt` and affected purge/reset, continuation and runner coverage. Do not move the policy tests. The named bug obligation is accidental validation at a previously non-validating canonicalisation call or changed nullable behaviour; prefer existing boundary coverage over duplicate literal-based tests.

### 5. Replace ambient elapsed time and prove the planning duration

Serves AC-005, AC-006 and AC-011.

In `Egoalrunner/planning/attempt/GoalPlanningPhaseAttemptGateBurstCap.kt`, read the same injected Clock immediately before launch and after launch and compute elapsed milliseconds directly. Preserve required-start persistence before execution and pause handling. Delete `GoalPlanningSweepConstants.NANOS_PER_MILLI` once it has no callers.

Remove `clockNanos: () -> Long = System::nanoTime` from `Egoalrunner/execution/support/GoalRunnerTickProgressReader.kt`. Pass the Clock already injected into `Egoalrunner/launch/GoalRunnerLaunchReconciler.kt`. Express the memo interval as 200 milliseconds. Preserve cached absence and the refresh cadence; refresh when time moves backwards rather than retaining the cache indefinitely. Do not widen the shared ambient-time scan or alter unrelated infrastructure baselines.

Add the one required regression in `Tgoalrunner/planning/sweep/GoalPlanningSweepTest.kt`. Thread an injectable clock through GoalPlanningSweepPortsParams and testGoalPlanningSweepPorts instead of the hardcoded Clock.systemUTC(). Follow the existing empty-provider-turn recorder boundary: advance the clock by 137 milliseconds inside the first launcher callback, return emptyProviderTurnOutcome(), capture GoalPlanningRejectionRecord and assert that its empty-turn evidence durationMs is exactly 137. Allow valid subsequent output so the attempt sequence completes. The realistic bug is a start read after launch or use of another clock, both recording zero. A successful captured response is not this assertion boundary; duration reaches GoalPlanningEmptyTurnEvidence through `Egoalrunner/planning/outcome/GoalPlanningSweepOutcomeDerivationChildStatus.kt`.

Later validation runs this regression and existing planning and tick-progress coverage. Implementation should confirm available tick-progress coverage and preserve its boundary assertions, including cached absence and rollback where present. Do not add a second test merely to duplicate elapsed-time mechanics. Fixed-clock memoisation is an implementation risk to inspect explicitly during review.

### 6. Remove recovery forwarding and reverse the featuretask dependency

Serves AC-007, AC-008, AC-009 and AC-011.

Delete `Egoalrunner/persist/DurableChildRecoveryClass.kt`. Import authoritative recovery symbols from `skillbill.engine.recovery` in GoalOperatorDecisionService, GoalRunnerResetReplanCoordinator, GoalPlanningRecoveryKind, GoalRunnerReAttemptCause, GoalRunnerRepairCoordinator and `Tgoalrunner/planning/recovery/GoalPlanningRecoveryClassificationTest.kt`. Keep classification and recovery command strings unchanged.

Delete the six unused goalrunner.status.completed imports from the three featuretask runloop-state files, runloop output verification, goal review-pass recorder and runloop drive listed in the digest. Move protectedBranchName and PROTECTED_GOAL_BRANCHES from GoalRunnerTickProgressReader into an existing file under `Efeaturetask/lifecycle/branch/`. Preserve trimming, blank filtering, lowercase membership and returned spelling. Rewrite its four featuretask importers and goalrunner consumers toward this owner. Move GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY into an existing file under `Efeaturetask/persist/`, keeping the literal `"goal_child_repair_evidence"`, then update every consumer including FeatureTaskRuntimeWorkflowPersistence. The digest does not name destination files; implementation chooses existing files that own branch policy and persisted artifact vocabulary without adding siblings to full packages.

Empty `Abaselines/runtime-engine-package-cycle-baseline.txt` after removing the remaining featuretask-to-goalrunner edge. The featuretask-to-work edge and ports aliases are already gone, so do not execute the stale conditional alias work in Dependency Notes. Later validation runs recovery classification, child recovery, branch/recovery coverage, the package-cycle guard and persisted-artifact captures. Moves and import deletion need no new structural tests.

### 7. Delete only the inert visibility checks

Serves AC-010 and AC-011.

In `ARuntimeEngineBoundaryArchitectureTest.kt`, remove `new public top-level engine declarations stay within inbound api and model packages`, the two visibility-census fixture methods and private topLevelPublicDeclarations helper from RuntimeEnginePublicTopLevelDeclarationArchitectureTest. Keep that carrier class and its live run-loop acyclicity and leaf checks. Keep every other engine boundary guard in the file and RuntimeEngineInboundApiTest. Preserve comment/literal stripping in declaration-cycle scans and slot dependency guards. Do not add an architecture-test class, suppression, exemption or baseline row. Later validation runs these existing guards; deleting inert checks requires no replacement test.

### 8. Complete construction updates and hand off validation evidence

Serves AC-001 through AC-011, without taking ownership of later subtasks or phases.

Finish affected engine tests and testFixtures construction updates while retaining observable assertions. Keep test packages and golden resources unchanged. Review the resulting collaborator graph against A1, A2, A3, A5, A6, A8, A9, A10, P3 and G1 through G7. Later review applies the architecture-guidelines section 5 checklist, verifies private collaborator ownership and constructor limits, and checks for receiver/parameter locators and service-forwarding getters that mechanical guards do not detect.

AC-001's broad no-Boundaries end state includes the featuretask bags assigned to subtask 2. This subtask deletes every goalrunner bag named here and records that remaining featuretask cleanup belongs to subtask 2. Do not expand this implementation into featuretask gate removal or the engine inject-property guard. Test-package moves remain subtask 3. Keep all criteria unchanged and make this sequencing explicit in the later audit evidence.

The build phase alone owns the pack build command and kotlin-inject compilation proof. Validate alone owns test execution, full project checks, repoTest, detekt, Spotless, scripts/validate_agent_configs and required CLI/MCP parity checks. Relevant existing suites include goal execution, status degradation, purge/reset, planning sweep and recovery, child recovery, goal-planning slotbaseline capture and domain-policy coverage. Validation must discover any other required project checks at that time. Do not regenerate goldens to accept changed artifacts. Persisted bytes, CLI/MCP output, recovery text, phase identities, module ownership and public inbound pins remain unchanged.

Planning executes no tests or build commands and leaves tests_executed empty if a receipt requests it. No schema migration, feature flag, module, install refresh or release work is planned. Later phases own implementation, simplification, audit, review, findings verification, validation, history, commit/push and PR work. No unresolved user decision remains.

## Next Path

skill-bill goal SKILL-390

## Spec Path

.feature-specs/SKILL-390-runtime-engine-architecture/spec_subtask_1_goal-runner-collaborators-and-engine-repairs.md
