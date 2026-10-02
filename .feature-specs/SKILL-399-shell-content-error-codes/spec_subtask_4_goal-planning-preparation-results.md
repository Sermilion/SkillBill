# SKILL-399 Subtask 4 - goal-planning-preparation-results

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

Convert `IncompatibleGoalPlanningPreparationRecoveryError` and `InvalidGoalPlanningPreparationSchemaError` (both in `InstallShellContentErrors.kt`). Make the preparation conflict a repository result, and stop the contract-version hard-reset classifier from reading typed properties.

- **Codes.** Add these `InstallFailureCode` entries (create the enum if subtask 3 has not):
  - goal planning preparation schema;
  - goal planning preparation conflict;
  - goal planning preparation contract incompatible.
- **Preparation conflict (tier 2).** `IncompatibleGoalPlanningPreparationRecoveryError(workflowId, subtaskId, reason, cause)` has 14 sqlite and 10 engine throw sites at `432d427c8`. Its readers are `blockedOnRecoveryError`, `recoverySubtaskId`/`preparationStateReadReason`, `goalPlanningPreparationStateReadStopReason` and `goalPlanningChildImportConflictBlockedReason`. Main reader sites: `GoalRunnerSubtaskLaunchPrepare.kt:144-148`, `GoalPlanningSweepOutcomeDerivationTerminalClass.kt:45-52`, `GoalPlanningOperatorRemedies.kt:67-72`, `GoalPlanningRecoveryKind.kt:28-69`.
  - Add a ports value `GoalPlanningPreparationConflict(workflowId, subtaskId, reason, cause: Throwable?)`.
  - Add result types shaped like `WorkflowGitOperationResult`: a `sealed interface` with nested data variants for applied or found versus `Conflicted(conflict)`. Ports hold no functions.
  - The `SharedGoalPreplanRepository`/`GoalSubtaskPlanRepository` methods that throw a conflict today return these results. The sqlite stores return `Conflicted` instead of throwing.
  - In runtime-engine, add an extension `GoalPlanningPreparationConflict.toFailure()`. It builds the coded failure through the install-area message function, keeping the text `Goal planning preparation '<workflowId>' subtask <subtaskId> cannot be recovered: <reason>`.
  - Callers that never inspected the conflict call `toFailure()` and throw, so their behaviour is unchanged.
  - The three reader paths take the conflict as a value and branch on it:
    - `prepareAttemptedLaunch`, in hydration and child persistence;
    - `recoveryProgress` plus `requireStoredPlansReady`, which returns the unready subtask id;
    - the sweep stop reason.
  - The reader functions take `GoalPlanningPreparationConflict`.
  - `GoalPlanningStatusReasonCoherenceTest` keeps passing unchanged: the stop reason contains the recovery `reason` and not the "cannot be recovered" text.
- **Catches.** The paired catches at `GoalChildPlanningHydrator.kt:301/303` and `GoalPlanningPreparationCheckpoint.kt:271/273` merge, or disappear where the result replaces them. Convert `GoalPlanningPhaseAttemptGateBurstCap.kt:25` if it checks one of these classes. `GoalPlanningPreparationStoreSchemaParityTest:89`, `:100` (`assertFailsWith<ShellContentContractException>`) become `SkillBillRuntimeException` plus the code.
- **Contract-version hard reset.** `causeIndicatesContractVersionHardReset` stops reading `fieldPath`, `reason` and `payloadFreeReason`.
  - Every throw site that rejects a stored planning or phase-output record for a contract id or version mismatch uses the "contract incompatible" code. Find them with one `grep -rnE 'phase_output_contract_version|planning_contract_version|phase_output_contract_id|planning_contract_id'`.
  - Where such a producer today throws a class owned by another subtask, convert only that throw site, and make every catch that could receive it also accept the new code.
  - The classifier walks the cause chain for that code. Keep whatever generic message branch SKILL-398 subtask 3 left in place.

## Acceptance Criteria

1. `InstallShellContentErrors.kt` declares neither class.
2. No sqlite or engine code throws a preparation conflict to signal a conflicted stored plan; the repository returns `Conflicted`.
3. The conflict readers take `GoalPlanningPreparationConflict`, and no main code reads `subtaskId`, `reason`, `fieldPath` or `payloadFreeReason` from a caught exception for these paths.
4. `causeIndicatesContractVersionHardReset` matches on the contract-incompatible code.
5. Ports declarations pass `PortsDeclarationArchitectureTest`.

## Non-Goals

The other Install classes (subtask 3).

## Test obligations

- One test: a conflicting stored plan during selected-subtask launch blocks that subtask's child with the conflict reason, not the "cannot be recovered" message. Realistic bug: the returned conflict is dropped, or routed to the wrong subtask id.

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

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_4_goal-planning-preparation-results.md
