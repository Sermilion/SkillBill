# SKILL-400 Subtask 4 - phase-slot-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](./spec.md)
Issue key: SKILL-400

## Scope

Convert `PhaseSlotContractErrors.kt` (17 classes, all extending `ShellContentContractException`) and `InvalidPhaseStrategyCompositionError` (extends `IllegalArgumentException`) in runtime-contracts `skillbill.error.featuretask`.

- **Defects.** These become `require`/`check`/`error()` with the same text, after a per-site check against the defect rule:
  - `UnknownPhaseStep`, `DuplicatePhaseStrategy`, `PhaseStrategyStepOutsideSlot`, `UnknownPhaseStrategy`;
  - `InvalidSkeletonDefinition`, `InMemorySkeletonDefinitionRequired`, `InMemoryPhaseRunUnsupported`;
  - `PhaseRunFanOutUnsupported`, `GoalPlanningPhaseGatesUnsupported`;
  - `PhaseStrategySelectionSlotMismatch`, `UnregisteredPhaseStrategySelection`.

  Before, they were shell-content failures, and MCP did not capture them. As IAE or ISE they stay uncaptured.
- **Kept as codes.** `UnknownPhaseReviewTarget`, `PhaseIntakeRequired`, `PullRequestBranchRefused`, `PhaseValidationScope` (git I/O), and `UnknownSkeletonDefinition` if a user-supplied definition id reaches it. They go in one `PhaseSlotFailureCode` in `skillbill.error.featuretask`, which joins `isShellContentContractFailure()`.
- **`UnknownQualityGateSelectionError`** becomes a returned value. `FeatureTaskRuntimeQualityGateSelection.fromWire` returns `null`; its only caller is `FeatureTaskRuntimeRunRequestAssembly.kt:93`. That caller builds the same `UsageError` text from `entries.map { it.wireValue }`. Its `initCause` and `runCatching` go only if SKILL-398 subtask 6 has not already removed them.
- **`PhaseCommand.kt:95`** handles `UNKNOWN_REVIEW_TARGET` through `usageError(error)`. Its `UnknownPhaseReviewTargetError` catch merges with the guarded `SkillBillRuntimeException` catch at `:97` as one catch with a `when (e.code)`.
- **`InvalidPhaseStrategyCompositionError`.** Throw sites: `PhaseStrategySelection`, `PhaseStrategyLookup`, `PhaseStrategyRegistry`, `ResolvedPhaseTraversalValidation`, `SkeletonDefinition`, `PhaseHistoricalInterpreter`.
  - A throw site that only runtime composition can trigger becomes `throw IllegalArgumentException("Invalid phase strategy composition: $reason")`, through one private helper per file, or `require`.
  - A throw site that persisted step ids or policies can trigger (expected: `PhaseHistoricalInterpreter.kt:63`), or that can reach the catch at `FeatureTaskRuntimeExecutionPlanCompatibility.kt:78`, keeps a code: `PhaseSlotFailureCode.INVALID_STRATEGY_COMPOSITION`. That catch checks the code.
  - The former class was IAE, so the code joins `uncapturedAtMcp()`. CLI output is identical, because the IAE and runtime-exception arms print the same line.
- Delete `PhaseSlotContractErrors.kt` and `InvalidPhaseStrategyCompositionError.kt` once empty. Keep `FeatureTaskRuntimePhaseOutputFailureCode`, `FeatureTaskRuntimeFailureKinds` and `InvalidFeatureTaskRuntimeHandoffProjectionContext`.

## Acceptance Criteria

1. No main source declares the 18 classes.
2. Each former failure throws `SkillBillRuntimeException` with a `PhaseSlotFailureCode` entry, or fails through `require`/`check`/`error()` where only composition or a code bug can trigger it, or is the returned `null` (`UnknownQualityGateSelectionError` only).
3. `skill-bill phase` with an unknown review target, a missing intake or a refused PR branch prints the same usage or completion text and exit code as before. An unknown quality-gate selection prints the same `UsageError`.
4. Durable execution plans with an incompatible strategy composition are still classified as incompatible.

## Non-Goals

The execution-plan admission family (subtask 5); the SKILL-399 FeatureTaskRuntime areas; the phase-strategy registry design.

## Test obligations

None beyond the converted assertions. Existing `PhaseCommand` and run-request-assembly tests cover the usage paths.
## Shared Rules

Apply `spec.md` "Conversion rules", "Shared pieces", "Transition finish" and "Execution Rule". If a shared piece is missing, add it as written there. After this subtask's edits, check the transition-finish condition and finish the transition if it holds.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- MCP telemetry capture happens for exactly the failures it happened for before, and no unguarded `SkillBillRuntimeException` catch absorbs a failure it did not catch before.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-400

## Spec Path

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_4_phase-slot-codes.md

## Implementation Details

This plan uses only the upstream preplan digest from checkout `6e512ed9ca1d0bf43619880b9f7560a58704d5f2`. It adds no decomposition or dependency. Paths below are relative to `runtime-kotlin`; production package paths sit under the named module's `src/main/kotlin`, and existing unit tests sit under that module's `src/test/kotlin`.

### Ordered tasks

1. Define the phase-slot failure vocabulary and remove the owned throwable declarations. Serves AC-001 and AC-002, plus message and classification parity.

   In contracts `skillbill/error/featuretask/PhaseSlotFailureCode.kt`, declare `PhaseSlotFailureCode : RuntimeFailureCode`. Retain separate entries for `UNKNOWN_REVIEW_TARGET`, `INTAKE_REQUIRED`, `PULL_REQUEST_BRANCH_REFUSED`, `VALIDATION_SCOPE`, `UNKNOWN_SKELETON_DEFINITION`, and `INVALID_STRATEGY_COMPOSITION`. Retain additional distinct entries where a former class is discriminated or asserted and its reachable input failure cannot become a defect. Replace multi-site constructors with adjacent message factories preserving parameters, cause, and exact text. Single-site failures may use the coded constructor directly. Preserve the composition prefix `Invalid phase strategy composition: ` exactly once.

   Replace and then delete contracts `skillbill/error/featuretask/PhaseSlotContractErrors.kt` and `InvalidPhaseStrategyCompositionError.kt`. Reuse the existing shared exception, `rethrowUnless`, and code-label helper. Do not recreate them or change the three explicitly retained FeatureTaskRuntime declarations. Convert existing assertions in the tests named in tasks 2 through 4; no new behavioral test is required for vocabulary declarations.

2. Separate composition defects from input and durable-plan failures at the owning sites. Serves AC-002 and AC-004.

   Inspect and convert engine `skillbill/engine/featuretask/slot/PhaseStrategyRegistry.kt`, `PhaseStrategySelection.kt`, `slot/attempt/PhaseStrategySteps.kt`, `slot/codereview/CodeReviewSlot.kt`, `slot/implementation/ImplementThenSimplifyStrategy.kt`, `phaserun/InMemoryPhaseRunAdapters.kt`, `phaserun/PhaseRunEntry.kt`, `slot/state/PhaseRunState.kt`, and `slot/attempt/PhaseAttemptRunHost.kt`. Convert constructor blank identities, invalid registration, and exclusively code-owned step, slot, in-memory, fan-out, or gate authority violations to `require`, `check`, or `error()` with unchanged messages. Apply the same distinction in domain `skillbill/workflow/taskruntime/model/skeleton/SkeletonDefinition.kt` for authored ordering and ownership. Keep `SkeletonDefinition.byId(id)` coded because phase requests can supply the ID.

   Keep `PhaseStrategySelection.strategyIdFor(slot, facts)` failures coded wherever they reach `PhaseStrategyLookup.matchesRecordedSelection(plan, definition)`. Keep recorded identity, revision, policy, and selected-ownership checks in `PhaseStrategyLookup.kt` coded, along with historical step IDs in `slot/state/PhaseHistoricalInterpreter.resumeRules(stepId)` and the shared failure path in domain `ResolvedPhaseTraversalValidation.kt`. Retarget the reachable legacy IAE handlers in `PhaseStrategyLookup.kt` and engine `FeatureTaskRuntimeExecutionPlanCompatibility.requireSupportedComposition(encoded)` to exact code checks. Preserve contextual wrapping, original causes, and the existing incompatible-plan result. Rethrow unrelated codes rather than treating all shared exceptions as composition failures.

   Convert assertions in engine `PhaseStrategyRegistryTest`, `PhaseStrategyCompositionTest`, `PhaseStrategyTraversalTest`, and `PhaseHistoricalInterpreterTest`, and domain `SkeletonDefinitionTest` and `model/core/PhaseSlotTest`. Preserve durable incompatibility and message assertions. The realistic bug these existing tests must still catch is a stored strategy or historical step mismatch escaping as an uncaught defect instead of producing the existing incompatibility outcome. Pure constructor and registration failures need only their existing defect assertions.

3. Preserve phase input outcomes and CLI handling. Serves AC-002 and AC-003.

   Convert review-target construction in engine `slot/codereview/CodeReviewStep.kt` and `slot/standalonereview/StandaloneReviewStrategies.kt`, keeping the standalone review behavior recorded in the digest. Convert missing intake, refused PR branch, and validation-scope I/O construction at their existing phase-run owners. In CLI `skillbill/cli/phase/PhaseCommand.kt`, merge unknown-review-target handling into the guarded shared catch, routing only `UNKNOWN_REVIEW_TARGET` through the existing `usageError(error)` behavior. Preserve completion text, usage text, exit codes, and causes for the other handled failures.

   Change domain `FeatureTaskRuntimeQualityGateSelection.fromWire(value)` to return a nullable selection. In CLI `skillbill/cli/featuretask/FeatureTaskRuntimeRunRequestAssembly.kt`, private `parseQualityGateSelection(source, raw)` builds the same `UsageError` for null using `entries.map { it.wireValue }`. Remove the obsolete cause initialization and its `runCatching` if they remain. Do not introduce a new exception or result wrapper for this expected input outcome.

   Convert assertions in engine `phaserun/PhasePullRequestRunTest`, `PhaseRunIntakeResolverTest`, and `PhaseReviewRunTest`, and CLI `skillbill/cli/phase/PhaseRunErrorMappingTest`. Preserve the existing PhaseCommand and run-request-assembly usage coverage and reuse `PhaseRunTestSupport.kt`. These tests catch changed usage routing, completion text, or exit codes after removing the old typed failures. Add no duplicate usage test.

4. Preserve shared handling and MCP capture boundaries. Serves AC-002 through AC-004 and the common acceptance criteria.

   Register the shell-content phase-slot codes in contracts `skillbill/error/shellcontent/ShellContentContractFailures.kt`. Preserve the former IAE composition route through MCP `skillbill/mcp/core/McpToolDispatcher.kt`, private `uncapturedAtMcp()`, using an exact `INVALID_STRATEGY_COMPOSITION` check if shell-content classification does not already cover it. Do not broaden any catch or predicate to all runtime codes. Retain existing enum registrations from earlier subtasks, cancellation order, interruption propagation, suppression, and database guards.

   At reachable shared-exception handlers, ensure a newly coded composition failure takes the former IAE route and unrelated codes rethrow unchanged. Preserve code-label rendering and uncoded rendering. Convert existing reader tests where their assertions refer to an owned class. No new capture test is obligated by the digest for this slice; existing classified routes and converted boundary assertions remain the evidence.

5. Remove only owned baseline rows and check transition eligibility. Serves AC-001 and AC-002 and the common baseline criterion.

   Remove the 18 deleted whole rows by hand from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`; retain all unrelated rows and earlier subtask edits. Preserve `FailureCodeTotalityArchitectureTest`, its synthetic rejection fixture, and its nonempty real-tree scan. Leave `ArchitectureScanSupport.kt` unchanged.

   Implement confirms the transition condition across main, test, and testFixtures source sets after conversion. The digest establishes remaining classes owned by other subtasks, so this plan assumes the transition stays open. If implement finds no remaining subclass or codeless constructor caller, apply the shared transition-finish rule without weakening guarded catches: remove only the legacy base and constructor machinery, make the shared exception final, remove its baseline row and legacy classification term, and reconcile `runtime-kotlin/ARCHITECTURE.md` and stale documentation references. Otherwise leave those declarations intact and name the remaining caller or subclass in the implementation handoff. This conditional work does not convert another subtask's classes.

6. Hand off validation obligations without executing them in plan or implement. Serves AC-001 through AC-004 and all common criteria.

   Validate runs the affected engine, domain, and CLI tests listed above, the existing run-request-assembly usage tests, detekt, formatting, and runtime-core repoTest architecture checks through the runtime-owned full validation strategy. Include `FailureCodeTotalityArchitectureTest` and preserve governed parity coverage and slotbaseline resource paths and byte comparisons. Build proof remains with the build phase. Spotless runs in a plain clone. Review and audit check each criterion and the digest's A1, A2, A4, A7, A9, A10, and G7 requirements; later validation or review repairs may touch required production wiring, test setup, formatting, or lint while preserving behavior and architecture rules.

   New `test_obligations` are empty. Existing coverage needs type-to-code or defect assertion conversions, with exact code assertions and all behavioral assertions retained. If implement discovers an uncovered boundary, it must name the concrete wrong behavior before adding one focused outcome test. Do not add tests that mirror factories, enum declarations, or trivial glue.

### Constraints and assumptions

- The digest does not provide the complete per-class constructor text or exact package path of every named reader. Implement confirms those details at the named owners and preserves the current signatures and messages. Missing detail is not authority to invent a new failure route. When defect-only reachability is uncertain, keep a code.
- No schema version or persisted wire-format change is needed. Preserve durable strategy policies, migrations, stored evidence, and incompatible-plan classification.
- Keep scope on this subtask's conversions and required readers. Add no module, dependency, exception property, typealias, code-family metadata, suppression, relaxed mock, helper module, or new `runCatching`. Keep ports declaration-only and domain free of ports and `java.nio` imports.
- Use owning key constants at governed payload seams. Keep package ownership and dependency direction intact. A file reduced to its enum and factories uses the enum's filename. Respect the existing throw, return, length, and complexity limits. Add no Kotlin line or block comments; interface KDoc alone is allowed.
- Planning edits only this sub-spec. Implementation produces repository changes; audit inspects them; validation owns execution and evidence. History, commit, PR, and any parent-runtime maintenance remain with their owning phases. No installation work is part of this child plan.
