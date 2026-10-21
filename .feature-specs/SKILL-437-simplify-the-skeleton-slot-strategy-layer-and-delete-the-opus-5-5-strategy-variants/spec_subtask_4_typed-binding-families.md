# Subtask 4 Declare typed binding families and preserve accepted authority

## Scope

Implement R6 across PhaseStrategy, PhaseStepBinding, PhaseStepHooks, typed attempt dispatch, FeatureTaskRuntimeRunLoopStepBindings and finding-verification write admission. Replace PhaseExecutionBindingKind and slot-driven binding selection with sealed PhaseBindingFamily<B : PhaseAcceptedStepExecution>. Strategy-owned per-step descriptors preserve type correlation for heterogeneous registry entries and multi-role strategies. Runtime retains private binding construction and lifetime ownership.

## Acceptance Criteria

1. PhaseStrategy<B> declares its typed family per execution step and runs with state: B; heterogeneous dispatch keeps strategy, family, binding and hooks correlated without unchecked casts, Any, raw-state callbacks or an all-capabilities binding.
2. FeatureTaskRuntimeRunLoopStepBindings.create dispatches exhaustively on declared families, contains no selection over PhaseSlot, PhaseExecutionBindingKind, qualityGateOperation or plansInFanOut, and the removed enum and slot accepted-binding downcasts no longer exist.
3. Family construction and dispatch retain accepted attempt, owner, step, request, policy, wave, closure and release checks, and quality-gate family payloads retain PackGate BUILD versus AgentValidation and their single-session policies.
4. Strategy authority enforcement retains raw PhaseRunState and PhaseRunner protections and unrelated writer protections, with no new baseline, exemption or source-catalog growth.

## Authority contract

CodeReviewSlot keeps distinct review, finding-verification and repair-receipt capabilities per step. AcceptanceAuditStrategy keeps QA findings authority for entry and ordinary agent execution for repairs. QA, monitor, standalone monitor, standalone publication, commit, PR, planning fan-out and planning units retain their separate narrow families. Review never gains the ordinary agent marker. Gate and commit gain no agent launch authority.

Carry family typing through PhaseStepHooks and PhaseStepLaunchHooks without renaming hooks or changing launch supplements, guards, admission, validated-output checks or settlement timing. Remove monitor, standalone-monitor and QA hook binding downcasts as well as runStep casts. PhaseStrategySteps.runAgentStep must require typed PhaseAgentExecution, rather than hiding its unsafe assumption. No strategy obtains raw state through a context.

FindingVerificationState.requireAcceptedWriter checks an active accepted binding before matching its selected family and authorizing checkpoint, boundary, rejection or repair-receipt writes. Retain canonical/recorded alias identity checks from subtask 2 at accepted execution seams. The obsolete acceptsAttemptStrategy override point is removed in subtask 5; do not introduce a new dependency on it here.

Keep plan fan-out unit/wave authorization, bounded buffers and finally cleanup; every dispatch and temporary decideByStep, decideByLoop and forEachSlotRules binding closes and releases. Preserve PR per-workflow/per-repository lookup cleanup in finally and monitor publication hooks.

## Non-goals

Do not move binding implementation into strategies, widen capability unions, conceal casts in shared helpers, rename hooks or delete StrategyCapabilitySourceCatalog's dynamic PSI graph. Context-role casts are distinct from accepted-binding casts, and need no unrelated redesign.

## Dependency notes

Depends on subtask 3. The digest reports existing user changes in FeatureTaskRuntimeRunLoopStepBindings.kt. Reconcile with them in the supplied workflow checkout. The parent settles typed capture ownership; plan_subtasks determines concrete implementation structure. Capability extraction follows in subtask 5, but any typing needed to preserve hook authority belongs here.

## Test Obligations

- Bug caught, an accepted binding can be used for another role, request, step or closed invocation. Retain or add observable refusal cases for wrong family, wrong step, wrong request, closed binding and unauthorized planning-unit wave, ensuring no checkpoint write or launch occurs. Do not test private class structure or dispatch call order.
- Bug caught, generic dispatch grants repair writers to review or launches an agent from a gate/commit binding. Preserve architecture synthetic rejection evidence for remaining raw/review authority and typed-family negative compilation/guard evidence where the existing guard framework supports it. Drop only obsolete rows that the type system now proves.
- Bug caught, family payload loses the selected gate mode or dispatch cleanup leaks authorization into the next execution. Exercise PackGate BUILD, AgentValidation, fan-out cleanup, PR cleanup, QA receipt enrichment and monitor settlement through existing boundary scenarios, extending only gaps introduced by this change.
- Validate runs existing repoTest architecture guards without relaxing synthetic violations, baselines or exemptions. Catalog parser behavior and unresolved-edge failures remain covered.

## Next path

Subtask 5, extract optional capabilities from the typed strategy core.

## Shared constraints

The parent spec's constraints, non-goals, decisions and phase ownership apply. Paths describe affected owners without preventing necessary review or validation repairs. Do not weaken behavior, assertions, architecture guards or durable identity checks. Keep existing unrelated workspace edits. No directive text, classifier identity or telemetry-column changes are allowed.

## Validation Strategy

Implement authors production and test changes; audit inspects repository facts. Neither executes builds, tests or generators. The later build phase owns compile/buildability proof. The later validate phase runs the required focused obligations below and the full project validation strategy, including repoTest guards. Preserve all existing relevant regression coverage and allow repairs required by those checks. Review, history, commit, publication and workflow advancement remain runtime-owned.

## Implementation Details

The ordered tasks below use only the upstream preplan digest. In these tasks, E is `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask`, ET is `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/featuretask`, and A is `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture`. Acceptance criterion references refer to this sub-spec. The implementation must accommodate the existing edits in `E/runloop/state/FeatureTaskRuntimeRunLoopStepBindings.kt` rather than replace that file wholesale.

1. Define the typed family and step-capture contract. Serves criteria 1 and 3.

   Add sealed `PhaseBindingFamily<B : PhaseAcceptedStepExecution>` beside the slot binding contracts. Update `E/slot/PhaseStrategy.kt` and `E/slot/state/PhaseStepBinding.kt` so each executable step preserves the relationship between its family, execution function and accepted binding. Keep construction of concrete bindings in the runtime. Define separate families for ordinary agents, planning fan-out and units, QA findings, review, finding verification, repair receipts, quality gates, commit, monitor, standalone monitor, standalone publication and PR. The quality-gate descriptor carries the selected `PhaseQualityGateOperation`, including PackGate BUILD or AgentValidation, rather than consulting a nullable strategy member during binding creation.

   Resolve heterogeneous dispatch with a typed step descriptor and a generic capture operation. Registry and lookup may expose an existential descriptor, but only capture opens it as a single B shared by the family, hooks and execution function. Runtime-owned exhaustive family branches construct the corresponding concrete binding and invoke that correlated execution. They must not return an uncorrelated star-projected binding for a later call. A single-family `PhaseStrategy<B>` can supply its typed `runStep` directly. A multi-role owner supplies independently typed descriptors for its steps, rather than choosing a common B that grants their combined authority. Shared identity and policy metadata remain on the selected owner.

   The digest does not establish the exact Kotlin capture signatures. Treat the descriptor plus generic capture structure as the implementation assumption to confirm during implementation and later build proof. If sealed-family narrowing cannot preserve B with the first signature, adjust the typed visitor or descriptor boundary. Do not insert an unchecked cast, Any, raw-state callback or broad binding to make it compile. Update heterogeneous registry, lookup and test-fixture signatures as needed without changing registration membership, recorded identities or selection behavior. No new test is needed solely to mirror these declarations; the authority obligations in tasks 4 and 6 prove their behavioral boundary.

2. Carry the family type through hooks and accepted agent execution. Serves criteria 1, 2 and 3.

   Update `E/slot/PhaseStepHooks.kt`, `PhaseStepLaunchHooks` and `E/slot/attempt/PhaseStrategySteps.kt`. Parameterize binding-consuming hooks or bind their operations into the typed descriptor so invocation retains the descriptor's B. Keep hook names, invocation timing and default behavior. Make `runAgentStep` require `PhaseAgentExecution` through its typed receiver or parameter. Review bindings continue to lack that marker, and gate and commit descriptors expose no agent execution operation.

   Carry the same captured descriptor through `E/slot/attempt/PhaseAttemptLoop.kt`, `PhaseAttemptOnce.kt`, `PhaseLaunchPreparation.kt`, `PhaseAttemptRunHost.kt` and any directly affected attempt helpers. Preserve launch supplements, guards, accepted-call admission, validated-output checks and completed-output settlement. Remove accepted-binding downcasts in monitor, standalone-monitor and QA hooks, including QA finding supplements and receipt enrichment. Keep canonical and historical-alias identity admission from subtask 2; do not add reliance on `acceptsAttemptStrategy`, whose deletion belongs to subtask 5. Extend the existing monitor publication and QA receipt boundary scenarios only if they leave the newly typed hook invocation unobserved.

3. Declare narrow families on every live strategy and helper. Serves criteria 1, 2 and 3.

   Update canonical owners under `E/slot/preplan`, `plan`, `implementation`, `audit`, `codereview`, `standalonereview`, `qualitygate`, `writehistory`, `commitpush`, `pullrequest`, `monitor`, `qa` and `intake`. Include the retained `AgentPlanStrategy` helper and typed fixture strategies in `runtime-kotlin/runtime-engine/src/testFixtures/kotlin/skillbill/engine/featuretask/slot/PhaseStrategyRegistryFixtures.kt`.

   `CodeReviewSlot` declares separate review, verify_findings and implement_fix descriptors. `AcceptanceAuditStrategy` declares QA findings for its entry and agent descriptors for repairs. Preserve its historical planning mapping. Planning distinguishes outer fan-out from authorized unit execution. Keep standalone review, monitor and publication separate from full-run review, monitor and runtime commit. Pack-build and agent-validation families retain their selected operation and single-session policies. PR retains its narrowed context and both lookup cleanups in finally. Remove the accepted-binding casts rather than relocating them into descriptors or helper functions. Do not perform the optional-capability extraction assigned to subtask 5, except for typing required by these descriptors and hooks.

   Reuse existing gate, planning, standalone-review, monitor and QA behavioral coverage. Add no table-mirroring test for each family declaration. Add a case only for a concrete lost role, payload or settlement behavior named in Test Obligations.

4. Replace runtime binding selection and writer admission. Serves criteria 2 and 3.

   Rewrite `E/runloop/state/FeatureTaskRuntimeRunLoopStepBindings.kt` to consume the selected typed descriptor and dispatch exhaustively on its sealed family. Remove slot, enum, nullable quality-gate and fan-out-based selection from `create`. Retain the private implementations, coordinator and accepted authority checks. Keep accepted attempt, selected owner and policy, step, request identity, fanOutUnitId, active wave, closure and release validation. Remove `PhaseExecutionBindingKind` once its consumers use the families.

   Replace `requireAcceptedWriter(kind)` in `E/runloop/state/FeatureTaskRuntimeRunLoopFindingVerificationState.kt` with admission against the active accepted binding and its selected family. The active-binding check must precede authorization of verification checkpoints, boundary selection, rejected findings and repair receipts. Family matching alone must not authorize a write. Historical aliases remain admitted only through the existing accepted plan and registry semantics, never through suffix matching.

   For the realistic bug of stale or mismatched authority causing a launch or durable write, retain or extend boundary refusal coverage for wrong family, wrong step, wrong request, closed binding and unauthorized unit wave. Assert that each refusal produces no forbidden launch or checkpoint mutation. Prefer the existing refusal scenarios over parallel tests of private constructors. Test execution belongs to validate.

5. Integrate typed dispatch with run-loop lifetime ownership. Serves criteria 1 and 3.

   Update `E/runloop/core/FeatureTaskRuntimeRunLoopPlanningBranch.kt` and directly affected dispatch consumers so `dispatchPreparedPhase` captures the descriptor, authorizes coordinator dispatch, constructs the binding and executes its typed function within the existing finally cleanup. Keep prepared-run behavior for the later capability extraction. Preserve closure and release for temporary bindings used by `decideByStep`, `decideByLoop` and `forEachSlotRules`, including affected transition and backward-edge paths. Update `E/slot/plan/PlanFanOutStrategy.kt` without changing bounded waves, authorization, output buffers, unit cleanup or plan versus plan_subtasks resume semantics.

   For the realistic bug of leaked dispatch or wave authority allowing the next invocation to reuse a finished binding, retain boundary scenarios that fail or finish one execution and then attempt another. Observe the refusal or successful fresh admission and persisted outcomes, rather than private call order. Reuse gate payload, fan-out, PR cleanup, QA preparation and monitor settlement scenarios to confirm that typed dispatch keeps their existing effects. Do not add new runtime state to the shared runner or change immutable-plan admission, digests or recorded launch assignments.

6. Tighten authority evidence and prepare later validation. Serves all four criteria.

   Update `A/StrategyCapabilityBoundaryArchitectureTest.kt`, `StrategyCapabilityTransitiveGraph.kt` and `StrategyCapabilityWriterInventory` only where removal of binding casts makes an existing entry obsolete. Preserve raw PhaseRunState and PhaseRunner protections, the existing deliberate review-runner boundary and unrelated writer protections. Keep `StrategyCapabilitySourceCatalog.kt` as the dynamic PSI graph with unresolved-edge failures. Do not add catalog entries, baselines or exemptions to accommodate the refactor.

   Preserve synthetic rejection tests for hidden mutation, helper or alias authority leaks and unresolved governed edges. Use negative type or guard evidence only where the existing framework supports it; do not introduce a compiler-test framework just to restate sealed declarations. Update `ET/slot/PhaseStrategyCompositionTest.kt`, `PhaseStrategyRegistryTest.kt`, `PhaseStrategyTraversalTest.kt`, `FeatureTaskRuntimeExecutionPlanResolverTest.kt` and fixture types where generic API changes require it. Keep the monitor and historical-alias regressions established by earlier subtasks.

   The later build phase proves the typed capture and all production/test signatures compile. The later validate phase discovers the repository's existing focused test commands and runs the refusal, gate, fan-out, review, QA, monitor and cleanup obligations plus required repoTest guards and full validation. Exact test command selectors are not present in the digest and must be resolved by that owning phase. Audit checks that `create` has no prohibited selection, the enum is absent, slot accepted-binding casts are absent and architecture enforcement has not grown exceptions. This planning phase runs no commands for build or test proof and leaves tests_executed empty.

Apply the digest's architecture rules throughout these tasks. Keep domain models pure, runtime construction in engine and DI in core. Add no ambient environment reads, new line or block comments, duplicated wire keys, durable fields or execution-plan version changes. Remove obsolete code instead of moving its unchecked authority elsewhere. Necessary wiring, test setup, formatting and lint repairs remain allowed in their owning phases.
