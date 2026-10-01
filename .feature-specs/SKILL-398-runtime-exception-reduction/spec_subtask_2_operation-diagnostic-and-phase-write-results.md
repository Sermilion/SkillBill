# SKILL-398 Subtask 2 - operation-diagnostic-and-phase-write-results

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
Issue key: SKILL-398

## Scope

(F-003) Three families report expected outcomes by throwing and are turned back into results a few frames up. Make them return the result directly.

**Operations** (`runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/operation/`, and `OperationUsageError` wherever it is declared, at the census tree `runtime-contracts/.../error/operation/OperationUsageError.kt`).

- `Operation.pre`, the confirmation gate and every operation's `run`/`execute` report a refusal (today `OperationRefusalError` and its 17 subclasses: unknown, consumed, superseded or foreign token; moved or unreadable anchors; PR not found or not checked out; protected branch; dirty worktree; release branch behind; verify workflow unknown, foreign or closed; spec rehydrate needed; verify target not checked out) or a usage problem (today `OperationUsageError` and its 9 subclasses) as a value. Use one sealed type, for example `sealed interface OperationRefusal { Blocked(message); Usage(message) }`, returned from `pre` (null means proceed) and carried by `OperationOutcome` / `OperationRunResult` where `run` or confirmation can refuse. Reuse `OperationOutcome.Blocked` for the blocked case.
- `OperationExecutor` has no `catch` of an operation failure. `runtime-cli/.../operation/OperationCommand.kt` maps the usage value to the same output and exit code it produces today for `OperationUsageError`.
- Update the KDoc at `Operation.kt:16-17` to describe the returned refusal.
- `DuplicateOperationIdError` is a registry wiring defect: replace it with `require`/`check` in `OperationRegistry`, same message text.
- Delete every operation error class. Messages stay byte-identical.

**Rejected-output diagnostics.**

- `runtime-ports/.../diagnostics/RejectedOutputDiagnosticRepository.kt`: `read` returns a sealed read result (found, absent, expired, oversized) instead of throwing `Absent`/`Expired`/`Oversized`; `insert` returns a sealed insert result (inserted, conflict) instead of throwing `Conflict`. Adapt `runtime-infra/sqlite/.../SqliteRejectedOutputDiagnosticRepository.kt`, `runtime-application/.../diagnostics/RejectedOutputDiagnosticService.kt`, `RejectedOutputDiagnosticInspection.kt`, `runtime-cli/.../featuretask/RejectedOutputCommands.kt` and `runtime-engine/.../lifecycle/core/FeatureTaskRuntimeRejectedOutputRecorder.kt` to branch on those values.
- `InvalidRequest` and `RejectedOutputDiagnosticAmbiguousSelectorError` are invalid caller input: the service returns them as values to its callers.
- `Persistence`, `Permission`, `Corrupt`, `Retrieval` and `InvalidConfiguration` are I/O or durable-state failures (tier 3): throw `SkillBillRuntimeException` with an entry of a `RejectedOutputDiagnosticFailureCode` enum declared beside the port's owner vocabulary. The recorder's degrade path catches `SkillBillRuntimeException`, maps `code is RejectedOutputDiagnosticFailureCode` to its `FeatureTaskRuntimeDiagnosticFailureClass` exactly as `degradableFailureClass()` does today, and rethrows any other code.
- Delete `RejectedOutputDiagnosticError` and `RejectedOutputDiagnosticAmbiguousSelectorError`.

**Required phase writes.**

- `FeatureTaskRuntimePhaseStateRecorder` and `FeatureTaskRuntimePhaseBriefingRecorder` return a value for a rejected required write (for example `RequiredPhaseWrite.Rejected(kind, workflowId, phaseId, attempt)`) instead of throwing `RequiredPhaseWriteRejected`. The goal-planning and run-loop callers (`GoalPlanningSharedPreplanSettlement.kt`, `GoalPlanningSharedPreplanProduction.kt`, `GoalPlanningPhaseAttemptGate.kt`, `GoalPlanningStepAttempts.kt`, `FeatureTaskRuntimeRunLoopStepBindings.kt` at the census tree) branch on it with the behaviour they have today. Delete `RequiredPhaseWriteRejected`.

If the custom-throwable baseline exists, remove the rows of every deleted class.

## Acceptance Criteria

1. No main source declares `OperationRefusalError`, `OperationUsageError`, any of their subclasses, or `DuplicateOperationIdError`. `OperationExecutor` contains no `catch`. Duplicate operation registration fails through `require`/`check` with the former message.
2. Every operation refusal and usage case produces the same CLI stdout, stderr and exit code, and the same operation result payload, as before; existing operation tests pass with only exception-type assertions replaced by assertions on the returned refusal.
3. `RejectedOutputDiagnosticRepository.read` and `insert` return sealed results that represent absent, expired, oversized and conflict without throwing; no main source declares `RejectedOutputDiagnosticError` or `RejectedOutputDiagnosticAmbiguousSelectorError`.
4. Persistence, permission, corrupt, retrieval and invalid-configuration failures throw `SkillBillRuntimeException` with a `RejectedOutputDiagnosticFailureCode`; the recorder degrades exactly the failure classes it degrades today and rethrows every other failure.
5. No main source declares `RequiredPhaseWriteRejected`; the recorders return the rejection as a value, and no goal-planning or run-loop code catches or `is`-checks an exception to detect a rejected required write.
6. Messages of every former class are byte-identical where they are still shown. No expected-output or wire-fixture assertion is edited other than type-to-value or type-to-code replacements.
7. If the custom-throwable baseline exists, it lists none of the deleted classes and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- Execution-plan admission errors (`FeatureTaskRuntimeExecutionPlanAdmissionError` family): they end the run and become codes in subtask 5.
- Changing which diagnostic failures degrade and which propagate.
- Other `SkillBillRuntimeException` subclasses.

## Dependency Notes

Depends on: none.
Applies to the operation classes wherever they are declared (SKILL-391 moved them into runtime-engine). If subtask 5 converted any of these classes to codes first, convert the code checks into the values described here. If subtask 1 has not landed, add the target-type pieces this subtask uses as the parent spec defines them. Coordinates with SKILL-390 (engine) and SKILL-393 (ports); the second lander keeps both edits.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: a test per new result branch where an outcome was thrown before (operation blocked and usage, diagnostic absent/expired/conflict, rejected required write), each asserting the downstream behaviour that the old catch produced.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_2_operation-diagnostic-and-phase-write-results.md
