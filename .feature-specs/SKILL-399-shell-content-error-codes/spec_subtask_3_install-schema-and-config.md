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

## Implementation Details

This plan comes from the current preplan digest for SKILL-399. That digest says the subtask 3 conversion is already on the branch. `InstallShellContentErrors.kt` now declares only `InstallFailureCode`, its message functions, and the two goal-planning preparation classes. Subtask 4 owns those two classes, as recorded in `runtime-kotlin/runtime-contracts/agent/history.md#a9bf5b54b2c3`. Planning treats this subtask as verify-only: implement confirms the end states below and repairs only residue it finds. It does not redo the conversion. This subtask has no dependencies and does not change the eight-slice decomposition.

### Ordered tasks

1. **Confirm no owned declaration remains.** Serves AC-001 and the baseline criterion.
   - Inspect `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/InstallShellContentErrors.kt`. None of the 16 classes in Scope may be declared, and no typealias may carry a deleted class name.
   - `InvalidGoalPlanningPreparationSchemaError` and `IncompatibleGoalPlanningPreparationRecoveryError` may remain. Leave them and their baseline rows for subtask 4.
   - Confirm that `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` has no `runtime-contracts:<Class>` row for any of the 16.
   - If a row survives, delete only that whole row. Edit no other row.
   - Residue rule: if one of the 16 classes still exists, convert it under `spec.md` "Conversion rules". Use the matching `InstallFailureCode` entry from Scope and a message function or coded constructor with byte-identical text, then delete its row.

2. **Confirm every former failure is coded.** Serves AC-002.
   - Confirm `InstallFailureCode` implements `RuntimeFailureCode` and has the entries listed in Scope. Unreadable repo-local config and malformed machine config share the family entry.
   - Confirm `skillbill.error.shellcontent.ShellContentContractFailures.kt` `isShellContentContractFailure()` includes `InstallFailureCode` (the digest says it does).
   - Every producer named in Scope or the previous plan must throw or return `SkillBillRuntimeException` with an Install code, through the message functions. Check install selection persistence and validation, the baseline manifest wire, native-agent composition, repo-local and machine config, `ConfigResolutionService`, the install, telemetry, goal-observability, goal-progress and IDE-status validators, and the domain goal-subtask review-state decoders.
   - No `require`/`check`/`error()` replaces an input-driven failure (`runtime-kotlin/runtime-contracts/agent/decisions.md#00c7d9a5549b`).
   - The `GoalSubtaskReviewState.reviewStateError` path already throws `invalidGoalSubtaskReviewStateSchemaError` with the Install code. The repair-receipt rewrap inside `decodeRepairReceipts` stays for subtask 6.

3. **Confirm no typed-property reads.** Serves AC-003.
   - Former `is`/`as?`/catch sites must read only `code`, `message` and `cause`, through `rethrowUnless(code == InstallFailureCode.X)`. Check `FileSystemInstallSelectionValidation.kt`, `FileSystemBaselineManifestWire.kt`, `GoalSubtaskReviewArtifactDecoder.decodeContinuationOnlyWire` and the remediation reconciler's review-state catch.
   - Two Scope anchors do not belong here. The `InstallApply.kt:195` identity-mismatch rethrow belongs to ReviewContext (subtask 2). The paired `IdeStatusService.kt:78/86` catches handle `InvalidWorkListRowError` and `InvalidWorkflowStateSchemaError`, which subtask 7 owns. Leave both alone.
   - The install-apply `causeClass` renders keep using `failureCodeLabel() ?: <existing expression>`.

4. **Check the transition condition.** Serves the Shared Rules end-of-subtask check.
   - The digest lists `ShellContentContractException` subclasses still in main: `PhaseSlotContractErrors.kt`, `DurableExternalDecodeErrors.kt`, `MalformedJsonTextError.kt`, `ExternalPlatformPackErrors.kt`, `ExternalAddonErrors.kt`, `FailureWireCodeContract.kt`, the execution-plan errors and `InvalidMcpToolArgumentError.kt`. They belong to SKILL-400 and SKILL-398, and the shellcontent classes of subtasks 4–8 also remain.
   - The transition therefore stays open. Keep `ShellContentContractException`, `LegacyFailureCode`, the codeless constructor, the open base and the predicate's `is ShellContentContractException` term.
   - Assumption for implement to confirm: no intervening work has removed every subclass. Finish the transition only if that turns out to be false.

### Tests

- Test obligations are empty. The digest names no tier-2 conversion for this subtask, and the converted existing assertions already cover misclassification, lost causes and message drift. These include `FileSystemInstallSelectionPersistenceTest`, `InstallPlanSchemaViolationsTest`, `InstallReconcileTest`, `InstallReconcileApplyTest`, `FileSystemRepoLocalConfigTest`, `InstallSelectionRuntimeBoundaryTest`, the native-agent and infra-contracts validator tests, and the goal-review-state tests.
- If residue is converted under task 1, its tests switch to `assertFailsWith<SkillBillRuntimeException>` plus a code assertion, and every message, payload and label assertion stays byte-for-byte.

### Constraints and phase ownership

- Implement produces repository end states and needs no build evidence to start. Audit checks each criterion. Build proof belongs to the build phase. All test runs, detekt, Spotless (in a plain clone) and the runtime-core repoTest suite, including `FailureCodeTotalityArchitectureTest`, belong to validate.
- Edit no file owned by subtasks 4–8 or by another issue. Edit nothing if every check passes.
- Add no module, dependency, typealias, property on `SkillBillRuntimeException`, `@Suppress`, new `runCatching`, `//` comment or non-interface KDoc.
- `skillbill.error.core` must not import `shellcontent`.
- Do not grow `ArchitectureScanSupport.kt` or weaken guards and baselines.
- No persisted schema, payload, flag or generated artifact changes. No install or uninstall command runs inside this goal child.
