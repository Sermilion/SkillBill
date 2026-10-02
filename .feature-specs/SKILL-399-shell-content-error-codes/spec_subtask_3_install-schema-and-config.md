# SKILL-399 Subtask 3 - install-schema-and-config

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

Convert the 16 classes in `InstallShellContentErrors.kt` other than `IncompatibleGoalPlanningPreparationRecoveryError` and `InvalidGoalPlanningPreparationSchemaError`. Those two belong to subtask 4; if they still exist, leave them.

The 16 classes: `InvalidInstallPlanSchemaError`, `InvalidNativeAgentCompositionSchemaError`, `InvalidTelemetryEventSchemaError`, `InvalidGoalObservabilityEventSchemaError`, `InvalidGoalProgressEventSchemaError`, `InvalidIdeStatusSchemaError`, `InvalidGoalSubtaskReviewStateSchemaError`, `MissingInstallSelectionRecordError`, `UnreadableInstallSelectionRecordError`, `MalformedInstallSelectionRecordError`, `UnreadableBaselineManifestError`, `ReconciliationConflictError`, `UnreadableRepoLocalConfigError`, `MalformedRepoLocalConfigError`, `MalformedMachineConfigError`, `ContractVersionMismatchError`.

- `InstallFailureCode` entries: install plan, native agent composition, telemetry event, goal observability event, goal progress event, IDE status, goal subtask review state, install selection missing, unreadable and malformed, baseline manifest unreadable, reconciliation conflict, repo-local config malformed, contract version mismatch. Family entry: unreadable repo-local config, malformed machine config.
- If subtask 4 already created `InstallFailureCode`, add these entries to it.
- Known `is`/`as?` sites: `FileSystemInstallSelectionValidation.kt:55`, `FileSystemBaselineManifestWire.kt:98`, `InstallApply.kt:195`. The two catches at `IdeStatusService.kt:79/87` merge into one catch with a `when (e.code)`.
- The install apply `causeClass` renders (9 sites) already use `failureCodeLabel()`; their tests expect the code label for converted classes.

## Acceptance Criteria

1. `InstallShellContentErrors.kt` declares none of the 16 classes.
2. Each former failure throws `SkillBillRuntimeException` with an `InstallFailureCode` entry, or is a `require`/`check`/`error()` defect.
3. No main code reads a typed property from a caught failure of these classes.

## Non-Goals

Goal-planning preparation conflict and schema (subtask 4). The repair-receipt re-wrap in `GoalSubtaskReviewState.decodeRepairReceipts` (subtask 6). Here it only switches its thrown class to the goal subtask review state code.

## Test obligations

None beyond the converted assertions.

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

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_3_install-schema-and-config.md
