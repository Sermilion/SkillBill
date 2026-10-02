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
