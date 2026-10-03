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

## Implementation Details

This plan uses only the upstream preplan digest and this sub-spec. Discovery's reference tree is `8527efaee41cfc2beb316be9b6eec4be017f9deb` on `base/SKILL-380-phase-slot-strategies`. The Workflow file has ten classes, eight owned here and two owned by subtask 8. There are no dependencies or unresolved design questions. Implement applies the plan to the current symbols, preserves intervening shared-file edits, and confirms the conditional checkpoint subclass and transition state during its own work.

### Ordered tasks

1. Define the Workflow codes and remove the eight owned throwable declarations. Serves AC-001 and AC-002. Work in `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/WorkflowShellContentErrors.kt` and the adjacent `WorkflowFailureCode.kt`, creating or extending the enum without disturbing decomposition entries. Declare eight distinct `RuntimeFailureCode` entries for workflow state schema, prose write refusal, work-list row, issue-key conflict, legacy prose workflow, rejected diagnostic schema, producer evidence schema, and verification boundary cap. The digest gives meanings rather than exact enum tokens. Assume descriptive uppercase tokens consistent with the existing area enums; implement must reuse an already established token for each meaning. Keep all eight as coded failures because the digest places them at user, agent, record, or verification-input boundaries and provides no evidence of a defect-only condition. Structured constructors used at multiple sites become adjacent message factories with the same input parameters and optional cause. Message-only constructions become direct `SkillBillRuntimeException` constructions. Leave both decomposition classes intact if present. Tests to update for later validation are the existing exact-code assertions in the Workflow producer test families named in task 6; no new test is required for enum or factory glue.

2. Preserve the shared workflow-state handled set before removing its base class. Serves AC-001 through AC-003. In the contracts shellcontent classification helpers, add or reuse `Throwable.isInvalidWorkflowStateFailure()`. During conversion it recognizes the remaining legacy state class, `WorkflowFailureCode`'s state-schema entry, and `FeatureTaskRuntimeFailureCode`'s checkpoint-identity-version entry. If `InvalidFeatureTaskRuntimeCheckpointIdentityVersionError` still exists, convert it in `FeatureTaskRuntimeShellContentErrors.kt`, create or extend `FeatureTaskRuntimeFailureCode.kt`, and convert its producers and assertions as part of removing its superclass. Keep its original message and cause. Preserve the checkpoint-specific branch ahead of broader state handling in `runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/remediation/FeatureTaskRuntimeRemediationBaseReconciler.kt`. Add newly created area enums to `ShellContentContractFailures.kt`, retain `GoalTelemetryRowFailureCode` classification, and never add Scaffold. Core must not import shellcontent. Later validation uses the existing checkpoint identity tests and `CheckpointHistoryRefusalTest.kt` together with workflow boundary tests. The realistic regression is a checkpoint-version failure escaping a boundary that previously handled it as invalid workflow state.

3. Convert Workflow producers and compatible catch-to-value boundaries. Serves AC-002 and AC-003. Change former constructions, return types, factory callbacks, imports, `is`, and `as?` discrimination across domain `workflow/engine/`, durable artifact readers, task-runtime persistence models, goal-runner models, ports workflow record mapping, and SQLite workflow reads. Keep persistence and decoding behavior with their current owners; ports remain declaration-only. Use `SkillBillRuntimeException` in changed failure-returning signatures. Caught failures expose only `code`, `message`, and `cause`; do not parse a message to recover a removed property.

   Retain guarded catches using `isInvalidWorkflowStateFailure()` in `runtime-application/src/main/kotlin/skillbill/application/workflow/service/WorkflowService.kt`, `workflow/decomposition/WorkflowStateRepositoryParentDiscovery.kt`, `DecompositionWorkflowContinuation.kt`, and `runtime-engine/src/main/kotlin/skillbill/engine/operation/verify/VerifyWorkflowStore.kt`. Preserve each current error, null, or empty-map result and its caller branch. Apply the same handled set at `VerifyOperation.kt`, `featuretask/slot/attempt/PhaseLaunchPreparation.kt`, `featuretask/runloop/durable/FeatureTaskRuntimeRunPreparation.kt`, and domain `workflow/taskruntime/model/persistence/GoalSubtaskReviewArtifactDecoder.kt`. Use `rethrowUnless` so unrelated coded failures, database failures, cancellation, and interruption still propagate. Do not introduce decoder refactors or new `runCatching`. Later validation uses `WorkflowStateStoreTest.kt`, `WorkflowEngineUpdateTest.kt`, persistence-model tests, `WorkflowRecordMappingTest.kt`, workflow persistence/service tests, `VerifyOperationTest.kt`, and core DI workflow tests. Preserve their observable assertions and change only exception assertions to exact codes. No new caller-branch test is needed because this plan retains the existing catch boundaries rather than introducing returned absence.

4. Merge the existing IDE status and diagnostic catches without widening them. Serves AC-002 and AC-003. In `runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusService.kt`, replace the adjacent work-list and workflow-state catches with one coded catch. Branch on the work-list code and shared workflow-state predicate, preserving `Incompatible work-list record.` and `Incompatible workflow record.` respectively, including current message fallback behavior and problem-snapshot emission. Rethrow failures outside that handled set.

   In `featuretask/lifecycle/core/FeatureTaskRuntimeRejectedOutputRecorder.kt`, merge each catch group in `producerOutput` and `degradeDiagnosticFailure`. Map exactly the producer-evidence-schema and rejected-diagnostic-schema Workflow codes to `FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA`. Keep the existing diagnostic codes' classifications and unrelated-failure rethrows. Preserve recovery, quarantine, measurement, diagnostic records, payloads, causes, and existing nested message text. Any touched class-name rendering uses `failureCodeLabel() ?: existingExpression`, so uncoded rendering remains identical. Later validation uses the IDE status tests supported by `IdeStatusServiceTestSupport.kt` and existing application diagnostic tests. The realistic bugs are selecting the wrong fallback, losing a problem snapshot, misclassifying a diagnostic, or swallowing a database failure. Convert existing assertions rather than adding overlapping tests.

5. Preserve refusal text and finish the owned declaration cleanup. Serves AC-001 through AC-003 and the common acceptance criteria. Convert prose-write refusal, issue-key conflict, legacy prose workflow, and verification-cap producers with the original constructor messages and causes. Preserve complete legacy-prose refusal and retry-command text. Update `runtime-engine/src/main/kotlin/skillbill/engine/featuretask/review/finding/FeatureTaskRuntimeFindingVerificationBoundaryMemoryPrompt.kt` to consume the coded failure without changing its bounded refusal text. The digest does not enumerate every producer path or factory parameter for these failures. Implement must confirm them at the named error definitions and their references, using the original expressions rather than inventing replacements. No removed-field reads or aliases may remain.

   Remove only whole baseline rows for classes actually deleted from `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`, including the checkpoint subclass only if converted here. Check main-source subclasses and codeless constructions during implement. The digest establishes external legacy uses, so assume the open exception, codeless constructor, and legacy base remain necessary. Finish the parent transition only if intervening work has removed every such use. Retain compatible shell-content classification and all guarded rethrows until that condition permits deleting the legacy classifier term. Shared pieces already exist according to the digest; restore a missing piece only under the supplied shared rules. Later validation uses `FailureCodeTotalityArchitectureTest`, including its existing baseline and totality fixtures. Do not edit unrelated baseline rows or grow `ArchitectureScanSupport.kt`.

6. Convert the existing regression assertions and hand off verification to its owning phases. Serves AC-001 through AC-003 and the common acceptance criteria. Update the existing infrastructure `WorkflowStateStoreTest.kt` and `SQLiteWorkListRepositoryTest.kt`, domain `WorkflowEngineUpdateTest.kt` and persistence-model tests, ports `WorkflowRecordMappingTest.kt`, engine workflow persistence/service and IDE status tests, `VerifyOperationTest.kt`, application diagnostic tests, and core DI workflow tests. Use `assertFailsWith<SkillBillRuntimeException>` plus the exact owner enum entry. Preserve existing message, payload, expected-output, exit-code, wire-fixture, recovery, and refusal assertions. Only pinned converted class labels become code labels. Preserve established test packages, source sets, slotbaseline resource paths, and persisted bytes.

   Test obligations for new tests are empty under this plan. Existing regressions already cover the supplied boundaries, and no value-returning decoder refactor is planned. Never weaken coverage of a past bug, governed parity, or validator rules. If implementation must instead change a catch-to-null boundary to return absence, the required additional obligation is one caller-boundary test tied to AC-003. Name the concrete bug first, such as malformed stored workflow state taking the success branch rather than the existing absence or error branch, and add it only if existing coverage does not prove that outcome.

### Constraints and phase ownership

Implement produces the repository end states above. Audit checks every acceptance criterion and the preserved handled sets. Review applies A1 through A12 and G1 through G7, with attention to A7 handled sets, A4 declaration-only ports, A6 wire ownership, A9 package cycles, and A10 test placement. Keep the SKILL-380 attempt boundary phase-generic and preserve accepted-step authority. No new module, dependency, alias, exception property, family metadata, raw-map public result, dependency bag, locator accessor, suppression, or `runCatching` is permitted. Domain stays free of ports and `java.nio`; contracts stay free of engine, infrastructure, and MCP code. Keep detekt limits at ThrowsCount 2, ReturnCount 4, LongMethod 70, and CyclomaticComplexMethod 15. Authored Kotlin has no line comments or non-KDoc block comments, and KDoc remains limited to interfaces and their members.

The later build owner provides buildability proof using the pack build command. Validate owns test execution and the full gate, `./gradlew check --continue --parallel -q --warning-mode none`, under JDK 21, plus strict agnix and the required formatting, detekt, unit, and architecture checks. Architecture coverage includes failure totality, ports declarations, typed parse boundaries, package cycles, wire vocabulary, and comment guards. Spotless must run in a plain clone. Required review or validation repairs may touch production wiring, test setup, formatting, or lint while preserving behavior, assertions, and architecture rules. No build, compile, test, or validation command runs during plan.

No persisted schema, payload, feature flag, dependency, authored skill, renderer, support pointer, or generated artifact change is planned. History, commit, push, PR, and runtime settlement remain with their owning phases or the parent runtime. This child plans no installation work and no new decomposition. This phase edits only this sub-spec's Implementation Details and leaves its existing sections unchanged.
