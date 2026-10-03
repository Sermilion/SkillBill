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

Source: the SKILL-399 preplan digest, sections "Failure model", "Shared conventions" and "Subtask 7". Paths are relative to `runtime-kotlin/`. Line numbers are approximate; apply each rule to the code wherever it is now (parent Execution Rule). Items marked **Assume** are for implement to confirm.

### Facts this plan relies on

- `SkillBillRuntimeException`, `rethrowUnless`, `failureCodeLabel()` and the codeless constructor live in `runtime-contracts/.../skillbill/error/core/RuntimeExceptionBases.kt`. `isShellContentContractFailure()` lives in `skillbill/error/shellcontent/ShellContentContractFailures.kt`. Today it accepts every `ShellContentContractException` plus the `FailureWireCode`, Manifest, SkillStaging, ReviewContext, AgentAddon, GovernedReview, GoalTelemetryRow and Install codes.
- All 8 classes extend `ShellContentContractException`. About 55 main catch sites guard with `rethrowUnless(error.isShellContentContractFailure())`, so these failures pass those guards today.
- `InvalidWorkflowStateSchemaError` has 113 main constructions, all passing only `(message, cause?)`. Its only subclass is `InvalidFeatureTaskRuntimeCheckpointIdentityVersionError(expected, actual, cause)`, thrown at `FeatureTaskRuntimeCheckpointIdentityModels.kt:187`.
- Main catch sites read only `message`; no typed property of these 8 classes is read in main. `ProseFeatureTaskWorkflowWriteRefusedError`, `WorkflowIssueKeyConflictError` and `LegacyProseWorkflowError` are thrown at 2–4 sites each and are never caught by type in main.
- Subtasks 5, 6 and 8 are pending in the manifest. **Assume** they have not landed when implement runs, and handle both cases where the plan says so.

### Ordered tasks

1. **Code enum and predicate registration (AC-002).** In `runtime-contracts/.../skillbill/error/shellcontent/WorkflowShellContentErrors.kt`, declare `enum class WorkflowFailureCode : RuntimeFailureCode` with these entries:
   - `INVALID_WORKFLOW_STATE_SCHEMA`
   - `PROSE_FEATURE_TASK_WORKFLOW_WRITE_REFUSED`
   - `INVALID_WORK_LIST_ROW`
   - `WORKFLOW_ISSUE_KEY_CONFLICT`
   - `LEGACY_PROSE_WORKFLOW`
   - `INVALID_REJECTED_OUTPUT_DIAGNOSTIC_SCHEMA`
   - `INVALID_PRODUCER_OUTPUT_EVIDENCE_SCHEMA`
   - `GOAL_VERIFICATION_BOUNDARY_CAP_EXCEEDED`

   If subtask 8 already created the enum, add only the missing entries and keep its entries. Add `WorkflowFailureCode` to `isShellContentContractFailure()` in `ShellContentContractFailures.kt`. This keeps propagation unchanged at every guarded site. The digest ranks a missing predicate registration as the bundle's highest-impact risk.

2. **Workflow-state family predicate (AC-002, AC-003).** Settles open decision 2 of the digest. If it is missing, add `fun Throwable.isInvalidWorkflowStateFailure(): Boolean` to `ShellContentContractFailures.kt`, next to `isShellContentContractFailure()`. It is true when `(this as? SkillBillRuntimeException)?.code` is `WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA` or `FeatureTaskRuntimeFailureCode.INVALID_FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_VERSION`. If subtask 6 already added the predicate, keep it and only confirm both codes are covered. The file is in `shellcontent`, so `skillbill.error.core` imports nothing new.

3. **Checkpoint-identity-version subclass (AC-001, AC-002).** Do this only if `InvalidFeatureTaskRuntimeCheckpointIdentityVersionError` still exists; the subclass cannot outlive its base.
   - If `FeatureTaskRuntimeFailureCode : RuntimeFailureCode` is missing, create it in `FeatureTaskRuntimeShellContentErrors.kt` with the entry `INVALID_FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_VERSION`, and register it in `isShellContentContractFailure()`. Subtask 6 adds its other entries later.
   - Delete the class. It is thrown at one site, so inline the throw at `FeatureTaskRuntimeCheckpointIdentityModels.kt:187` as `SkillBillRuntimeException(FeatureTaskRuntimeFailureCode.INVALID_FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_VERSION, "<same text>", cause)`. Keep the text byte-identical: `"...checkpoint-identity record uses unsupported contract version '${actual.ifBlank { "<absent>" }}'; this runtime reads '$expected'. The store is quarantined and regenerated rather than reinterpreted."` with the class's exact prefix. If implement finds a second throw site, add a message function `invalidFeatureTaskRuntimeCheckpointIdentityVersionError(expected, actual, cause = null)` instead.
   - `engine/.../lifecycle/remediation/FeatureTaskRuntimeRemediationBaseReconciler.kt:80` has `catch (_: CheckpointIdentityVersion)` returning `Refused("Checkpoint identity semantics are unsupported. ...")`, followed by a generic catch with `rethrowUnless(code == InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA)` returning `Absent`. Merge them into one `catch (error: SkillBillRuntimeException)` with `when (error.code)`: the checkpoint-version entry returns the same `Refused`, the Install entry returns `Absent`, and anything else is rethrown.
   - Change no other FeatureTaskRuntime class.

4. **Message functions (AC-001, AC-002).** Next to the enum, add one function per class. This follows the landed precedent of one function per class, even for message-only classes (for example `missingManifest(message, cause)`). With 113 constructions of the state error, it keeps the call sites short. Each function returns `SkillBillRuntimeException`, takes the old constructor parameters plus `cause: Throwable? = null` where the class had a cause, and builds the old text byte for byte:
   - `invalidWorkflowStateSchemaError(message, cause = null)`
   - `proseFeatureTaskWorkflowWriteRefusedError(workflowId, cause = null)`, using the fixed retired-prose text
   - `invalidWorkListRowError(message, cause = null)`
   - `workflowIssueKeyConflictError(workflowId, persisted, requested)`
   - `legacyProseWorkflowError(workflowId, issueKey: String?)`, keeping `issueKey?.trim()?.ifEmpty { null } ?: "<ISSUE_KEY>"`
   - `invalidRejectedOutputDiagnosticSchemaError(message)`
   - `invalidProducerOutputEvidenceSchemaError(message)`
   - `goalVerificationBoundaryCapExceededError(message)`

   Use the `Error` suffix, matching the landed `invalidGoalSubtaskReviewStateSchemaError`. **Assume** no existing top-level function in `skillbill.error.shellcontent` has these names; if one does, rename the new function. Then delete the 8 class declarations. Declare no typealias, and leave the subtask 8 decomposition-manifest classes in the file.

5. **Throw sites (AC-002).** Replace every `throw`, return or construction of a former class with its message function. The state error's heaviest files are:
   - `FeatureTaskRuntimeValidationGateProgressModels.kt` (14) and `FeatureTaskRuntimeAuditGapPersistenceModels.kt` (6)
   - 5 each: ports `WorkflowRecordMapping.kt`, sqlite `WorkflowStateSqlReads.kt`, `FeatureTaskRuntimeRequiredArtifactPresenceResolver.kt`, `FeatureTaskRuntimeDeliveredProjectionRecord.kt` and `FeatureTaskRuntimeGoalContinuationArtifact.kt`
   - infra `WorkflowStateSchemaValidator.kt` (4). Its callback `identityFailure = { reason -> InvalidWorkflowStateSchemaError(reason) }` at :118 becomes `{ reason -> invalidWorkflowStateSchemaError(reason) }`. Widen any function-type parameter declared to return a former class, or `ShellContentContractException`, to `SkillBillRuntimeException`.

   Convert the ports throw sites (`WorkflowRecordMapping`, `WorkflowStateRecord`, `WorkflowArtifactTimestampMapping`) in place. That behaviour already lives in ports, so add no class, object or default body there.

   **Defect review (settled):** all 8 failures come from stored records, rows, issue-key input, retired prose state, agent output or goal limits, so all keep codes. None becomes `require`/`check`/`error()`.

6. **Catch sites (AC-002, AC-003).** Each former `catch (e: InvalidWorkflowStateSchemaError)` becomes `catch (e: SkillBillRuntimeException) { e.rethrowUnless(e.isInvalidWorkflowStateFailure()); <same body> }`. Other classes use `e.rethrowUnless(e.code == WorkflowFailureCode.X)`. Bodies keep reading only `message`, with the same fallbacks.
   - `application/.../WorkflowService.kt:143` (open, returns `WorkflowOpenResult.Error`), `:165` (update input) and `:173` (around `database.transaction`, returns `WorkflowUpdateResult.Error`). The `:173` catch must stay outside the transaction, so the throw still rolls it back.
   - `WorkflowStateRepositoryParentDiscovery.kt:76` and `DecompositionWorkflowContinuation.kt:438` return null.
   - `VerifyWorkflowStore.kt:59` returns `emptyMap()`. `VerifyOperation.kt:346` emits a best-effort warning with the message.
   - `IdeStatusService.kt:78` (WorkListRow, `incompatibleRecord`) and `:86` (state, `incompatibleRecord`). If both sit on one `try`, merge them into one catch with `when`, so the work-list code and the state family each keep their own branch. Otherwise convert each separately.
   - `GoalSubtaskReviewArtifactDecoder.kt:62` and `:118` rewrap through `reviewStateError` with the message. Copy the neighbouring `decodeContinuationOnlyWire` pattern.
   - `FeatureTaskRuntimeRunPreparation.kt:39`.
   - `FeatureTaskRuntimeFindingVerificationBoundaryMemoryPrompt.kt:46` uses the cap code and keeps the message with its fallback.
   - **Order-sensitive chains:**
     - `FeatureTaskRuntimeValidationGateExecutionEvidence.kt:108–117` runs: catch ValidationEvidence and rethrow, then catch the state error and throw ValidationEvidence with `addSuppressed`, then catch `IllegalArgumentException`. If subtask 6 has not landed, keep the typed ValidationEvidence catch first and convert only the state catch to the code-checked `SkillBillRuntimeException` catch after it. Kotlin allows a subclass catch before its supertype. If subtask 6 has landed, use one `SkillBillRuntimeException` catch with `when`: the validation-evidence code rethrows, the state family wraps exactly as today, and anything else rethrows. The `IllegalArgumentException` catch stays last. In both cases validation evidence is checked first.
     - `PhaseLaunchPreparation.kt:203/205`: the projection catch at :203 (subtask 5) stays first, as it is. The state catch at :205 becomes the code-checked catch feeding `rejectedDurableBriefingLaunch`. If subtask 5 already merged :203 into a `SkillBillRuntimeException` catch, add the state family as a `when` arm and keep the projection arm first.
     - `engine/.../lifecycle/core/FeatureTaskRuntimeRejectedOutputRecorder.kt:205/207` and `:235/237`: each pair is a typed catch (ProducerOutputEvidence, then RejectedOutputDiagnostic) followed by a generic `catch (error: SkillBillRuntimeException) { ... error.degradableFailureClass() ?: throw error }`. Collapse each pair into one `SkillBillRuntimeException` catch whose first `when` arm is the specific code with the old body, and whose `else` arm is `error.degradableFailureClass() ?: throw error`. Do not fold the codes into `degradableFailureClass`.
   - Every `is` or `as?` check on these classes that the census finds becomes `(error as? SkillBillRuntimeException)?.code == ...`, or `isInvalidWorkflowStateFailure()` for the state family.
   - Every main `catch`, `is` or `as?` on `ShellContentContractException` touched here keeps, or gains, the `isShellContentContractFailure()` guard.
   - Add no `runCatching`. Let cancellation and interruption propagate.
   - **Assume** the census finds no other typed catch. If it does, convert it by the same rule.

7. **Catch-to-value sites (AC-003). Settled: code-checked catches, no decoder refactor.**
   - `WorkflowService.kt:173` must stay a catch, because the transaction rolls back only on a throw.
   - The `:143`, `:165`, `VerifyWorkflowStore.kt:59` and `WorkflowStateRepositoryParentDiscovery.kt:76` sites wrap shared decoders that throw from many of the 113 sites. Returning values there would be the decoder refactor the scope forbids.
   - Every site keeps its fallback value and every diagnostic or degradation record it emits today.
   - **Assume:** if implement finds a site whose decoder is private and used only by that caller, it may return the value instead, and must then add the one caller-branch test from task 9.

8. **Typed-property reads (AC-003).** The digest finds none in main; every catch reads only `message`. Implement re-checks during the census. Any read that only feeds a message switches to `message`. Add no property to `SkillBillRuntimeException`.

9. **Tests.** Edit only to convert types; message, `contains`, payload and exit-code assertions stay byte for byte.
   - `assertFailsWith<FormerClass>` becomes `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(WorkflowFailureCode.<ENTRY>, error.code)`.
   - Where a test relied on subclassing, for example `assertFailsWith<InvalidWorkflowStateSchemaError>` catching a checkpoint-version failure, assert `FeatureTaskRuntimeFailureCode.INVALID_FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_VERSION`.
   - Tests that construct a former class call its message function. Pinned class-name labels become `"WorkflowFailureCode.<ENTRY>"`.
   - Files:
     - `FeatureTaskRuntimePersistenceModelsTest` (43), `WorkflowServiceTest` (12), `WorkflowStateSchemaViolationsTest` (11), `WorkflowStateStoreTest` (7), `GoalRunnerControlStoreTest` (6) and repoTest `PlatformPackSchemaCleanupTest` (6)
     - About 35 files with 2–5 references each, including ports `WorkflowRecordMappingTest`, `IdeStatusServiceTestSupport`, `ApplicationPersistencePortTestSupport`, application testFixtures `WorkflowStateTestFixtures`, `DatabaseMigrationsTestSupport`, `RejectedOutputDiagnosticServiceTest`, `VerifyOperationTest` and `WorkListServiceTest`
     - If task 3 ran: `FeatureTaskRuntimeCheckpointIdentityModelsTest`, `CheckpointHistoryRefusalTest` and any reconciler test asserting `Refused`
   - Use no `relaxed = true` mocks, no `environment = emptyMap()` and no new helper module.
   - **New tests: none.** Task 7 keeps code-checked catches, so no catch-to-null becomes a returned value. The converted assertions cover every code. The predicate registration and the merged reconciler and recorder catches keep behaviour already pinned by existing tests (`WorkflowServiceTest`, `RejectedOutputDiagnosticServiceTest` and the reconciler tests). The one exception is task 7's assumed private-decoder case.

10. **Baseline (AC-001).** In `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`, delete by hand the rows `runtime-contracts:GoalVerificationBoundaryCapExceededError`, `InvalidProducerOutputEvidenceSchemaError`, `InvalidRejectedOutputDiagnosticSchemaError`, `InvalidWorkListRowError`, `InvalidWorkflowStateSchemaError`, `LegacyProseWorkflowError`, `ProseFeatureTaskWorkflowWriteRefusedError` and `WorkflowIssueKeyConflictError`. If task 3 deleted it, also delete `InvalidFeatureTaskRuntimeCheckpointIdentityVersionError`. Edit no other row; a stale or missing row fails `FailureCodeTotalityArchitectureTest`.

11. **Transition-finish check.** After the edits, search main for classes extending `SkillBillRuntimeException` or `ShellContentContractException`. The digest lists survivors outside this subtask: `PhaseSlotContractErrors.kt`, the `error/core` decode and external-pack errors, the execution-plan errors, `InvalidMcpToolArgumentError` and the pending subtask 5, 6 and 8 classes. The transition therefore does not finish here. Keep `ShellContentContractException`, `LegacyFailureCode`, the codeless constructor, the `open` modifier and the `is ShellContentContractException` predicate term.

### Constraints

- `skillbill.error.core` imports nothing from `skillbill.error.shellcontent` (`ApplicationPackageAcyclicityArchitectureTest`).
- Ports stay declarations only (`PortsDeclarationArchitectureTest`): convert existing ports throws in place and add no objects, behaviour classes, casts or default bodies.
- runtime-domain gains no `java.nio` or ports imports.
- Add no new module, dependency, `Result` library, typealias, `@Suppress`, `runCatching`, `//` comment or property on `SkillBillRuntimeException`.
- Pass enum entries, never wire strings.
- detekt limits: ThrowsCount 2, ReturnCount 4, LongMethod 70, CyclomaticComplexMethod 15. If a merged `when` pushes a function over a limit (watch `PhaseLaunchPreparation.kt`, 429 lines, and the recorder), extract a small private helper in the same file.
- Keep production files under the line-ceiling guard. `ArchitectureScanSupport.kt` must not grow.
- Messages, payloads and rendered labels stay byte-identical.
- Touch subtask 5, 6 and 8 classes only where task 3 or task 6 requires it.

### Validation

This plan runs nothing. The build phase compiles. The validate phase runs build, unit tests, detekt, the runtime-core repoTest suite (`FailureCodeTotalityArchitectureTest`, `PortsDeclarationArchitectureTest`, the package-cycle, wire-vocabulary and comment guards) and the full `./gradlew check`. There is no install or generated-artifact work.
