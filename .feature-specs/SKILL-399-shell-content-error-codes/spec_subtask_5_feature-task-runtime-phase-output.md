# SKILL-399 Subtask 5 - feature-task-runtime-phase-output

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

In `FeatureTaskRuntimeShellContentErrors.kt`, convert these classes, and drop the dead data types if they have no main or test readers:

- `InvalidFeatureTaskRuntimePhaseOutputSchemaError`
- `InvalidFeatureTaskRuntimeHandoffProjectionError`
- `FeatureTaskRuntimePhaseOrderViolationError`
- data types: `FeatureTaskRuntimePhaseOutputStructuralRepair`, `FeatureTaskRuntimePhaseOutputStructuralRepairSource`

If those data types do have readers, move them next to the reader; they are not throwables.

- **Phase output.** The code is the carried `FeatureTaskRuntimePhaseOutputFailureCode`, with default `SCHEMA_INVALID`; add no new entry. No main code reads `structuralRepair*` or the dropped properties. Pass enum entries directly, not wire strings.
- **Handoff projection rejection.** The code is `context.failureKind` (`FeatureTaskRuntimeHandoffProjectionFailureKind`). `InvalidFeatureTaskRuntimeHandoffProjectionContext` is already a value.
  - The projection build and validation entry points return the rejection context. They are used by `PhaseLaunchPreparation` (both catches, `:121` and `:221-222`) and by `FeatureTaskRuntimeRunLoopOutputVerification.kt:158` → `FeatureTaskRuntimePhaseBriefingRecorder.recordProjectionRejection` (`:101-105`).
  - Those readers build measurement rows from `context.projectionName`, `projectionContractId` and `failureKind`. Other callers throw the coded failure, built from the context by the message function.
- **Phase order violation.** `FeatureTaskRuntimeTransitionFunction.nextTransition` (runtime-domain) returns a sealed result with a violation variant carrying `phaseId` and the byte-identical message. `FeatureTaskRuntimeRunLoopDrive` (`:164-169`) branches on it. Its own throw at `:104` stays a coded failure if it ends the run. Create `FeatureTaskRuntimeFailureCode` with a phase order violation entry if subtask 6 has not.
- **Catches.** Merge the catches in `FeatureTaskRuntimeRejectedOutputRecorder.kt:189-221` into one `when (e.code)`. Lambdas typed as returning these classes (for example `featureTaskRuntimeWireArtifactNonObjectError`, `coherenceError`) become `SkillBillRuntimeException`.
- **Pinned test.** If `FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest` asserts `error::class.simpleName` for a class converted here, that assertion becomes a `code` assertion.

## Acceptance Criteria

1. None of the three classes or two data types remains in `FeatureTaskRuntimeShellContentErrors.kt`.
2. `nextTransition` returns its violation as a value, and no main code reads `phaseId` from a caught exception.
3. No main code reads `projectionName`, `projectionContractId` or `failureKind` from a caught exception.

## Non-Goals

The other FeatureTaskRuntime classes (subtask 6).

## Test obligations

- One test: `nextTransition`'s phase-order violation blocks at the violation's `phaseId` with the same message. Realistic bug: the run blocks at the current phase instead.

## Shared Rules

Apply `spec.md` "Conversion rules", "Shared transition pieces" and "Execution Rule". If a shared transition piece is missing, add it as written there. If any main `catch`, `is` or `as?` on `ShellContentContractException` lacks the `isShellContentContractFailure()` guard, add the guard. Add each area enum this subtask creates to `isShellContentContractFailure()`, unless it is `ScaffoldFailureCode`.

After this subtask's edits, check whether any class in main still extends `SkillBillRuntimeException` or `ShellContentContractException`. If none does, finish the transition as `spec.md` "Target failure model" describes.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch.

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_5_feature-task-runtime-phase-output.md
