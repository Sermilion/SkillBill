# SKILL-399 Subtask 7 - workflow-state-and-records

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

Convert these classes in `WorkflowShellContentErrors.kt`:

- `InvalidWorkflowStateSchemaError`
- `ProseFeatureTaskWorkflowWriteRefusedError`
- `InvalidWorkListRowError`
- `WorkflowIssueKeyConflictError`
- `LegacyProseWorkflowError`
- `InvalidRejectedOutputDiagnosticSchemaError`
- `InvalidProducerOutputEvidenceSchemaError`
- `GoalVerificationBoundaryCapExceededError`

The two decomposition-manifest classes belong to subtask 8; if they still exist, leave them.

- **Codes.** `WorkflowFailureCode` (create it if subtask 8 has not): workflow state schema, prose write refused, work list row, issue key conflict, legacy prose workflow, rejected output diagnostic schema, producer output evidence schema, verification boundary cap.
- **Workflow-state subclassing.** `InvalidWorkflowStateSchemaError` is `open` and subclassed by `InvalidFeatureTaskRuntimeCheckpointIdentityVersionError` (subtask 6). Add `fun Throwable.isInvalidWorkflowStateFailure(): Boolean` in `skillbill.error.shellcontent` if it is missing, as subtask 6 defines it. If the checkpoint-version class still exists when this subtask runs, convert it as well, using the checkpoint-identity-version `FeatureTaskRuntimeFailureCode` entry and creating that enum and entry if they are missing, because the subclass cannot outlive its base. Every former `catch (e: InvalidWorkflowStateSchemaError)` uses `isInvalidWorkflowStateFailure()`.
- **Catch-to-value sites.** At `WorkflowService.kt:143`, `:165`, `:173`, `VerifyWorkflowStore.kt:59` and `WorkflowStateRepositoryParentDiscovery.kt:76`, prefer making the decode boundary return the value (`null`, `emptyMap()` or an `Error` result) and drop the catch. A code-checked catch is acceptable; do not widen this into decoder refactors.

## Acceptance Criteria

1. `WorkflowShellContentErrors.kt` declares none of the 8 classes.
2. Each former failure throws `SkillBillRuntimeException` with a `WorkflowFailureCode` entry, or is a `require`/`check`/`error()` defect.
3. No main code reads a typed property from a caught failure of these classes.

## Non-Goals

The decomposition-manifest classes (subtask 8). The FeatureTaskRuntime classes other than the checkpoint-identity-version subclass (subtasks 5 and 6).

## Test obligations

None beyond the converted assertions. If a catch-to-null becomes a returned value, add one test asserting the caller's branch.

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

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_7_workflow-state-and-records.md
