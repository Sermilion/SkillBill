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

## Implementation Details

All paths are under `runtime-kotlin/`. Line anchors come from the SKILL-399 preplan digest; apply each step to the code wherever it is now. Main sources live under `<module>/src/main/kotlin/skillbill/...`.

### Settled decisions

- **D1. Result scope.** Only repository methods that have a conflict producer return results: `checkpointSharedPreplan`, `replaceSharedPreplan`, `findSharedPreplan`, `checkpointSubtaskPlan`, `replaceSubtaskPlan`, `findSubtaskPlan`, `listSubtaskPlansOrdered` and `markPrepared`, plus whichever of `advanceSharedPreplanProvenance`, `deleteSharedPreplan` and `invalidateSharedPreplan` own the conflicts at `GoalSharedPreplanSql.kt:136, :180, :211`. **Assumption for implement to confirm:** those three lines belong to advance, delete and invalidate respectively. Count, status, list-id, hash, cascade and `deleteByGoal` methods keep their signatures, which limits churn in the ports test fixtures and test fakes. Where such a method only translates an `SQLException`, it keeps throwing, now the coded CONFLICT failure from the message function. That signals a SQL fault, not a conflicted stored plan, so AC-002 holds.
- **D2. Contract-incompatible producers.** CONTRACT_INCOMPATIBLE goes on producers that reject a stored record for a contract mismatch. In sqlite these are `normalizedProvenanceFailure` (the four `provenance.*_contract_*` reasons, including the `phase_output_contract_version` hard-reset text), `normalizedEnvelopeFailure` (`"contract_version is incompatible"`), and `RecordSqlValidation.provenanceFailure` and `envelopeFailure` (the "must be '…'" reasons). Any other site the spec's census grep finds rejecting a stored record on one of those fields also gets it. JSON-schema "must be the constant value" reasons raised by the infra schema validators keep SCHEMA; the classifier's message branch (D3) still classifies them as HARD_RESET.
- **D3. Classifier.** `causeIndicatesContractVersionHardReset` walks the cause chain and decides per element:
  - code CONTRACT_INCOMPATIBLE: true;
  - code SCHEMA, code CONFLICT, a `FeatureTaskRuntimePhaseOutputFailureCode` code, or `is InvalidFeatureTaskRuntimePhaseOutputSchemaError`: `reasonIndicatesContractVersionHardReset(message.orEmpty())`;
  - anything else: false.

  The phase-output arm reads only `message`. Subtask 5 owns that class, so its `is` arm stays until subtask 5 replaces it; whichever of the two lands second keeps both edits. Every one of these messages embeds the fieldPath and reason, so the existing cases 3 and 5 of `GoalPlanningRecoveryClassificationTest` still classify as HARD_RESET with constructor-to-function edits only. The untyped `IllegalStateException` case still returns SCOPED_REPLAN. `reasonIndicatesContractVersionHardReset` stays unchanged. The classifier gets no new test.
- **D4. Transition finish.** This subtask does not finish the transition. Legacy subclasses remain in FeatureTaskRuntime and Workflow shellcontent, `PhaseSlotContractErrors`, `DurableExternalDecodeErrors`, `MalformedJsonTextError`, `ExternalPlatformPackErrors`, `ExternalAddonErrors`, the execution-plan errors and `InvalidMcpToolArgumentError`. `ShellContentContractException`, `LegacyFailureCode`, the codeless constructor and the open base therefore stay. Implement re-checks this after its edits and records the result.
- **D5. Burst cap.** `GoalPlanningPhaseAttemptGateBurstCap.kt` no longer exists, so that spec anchor drops out.

### Task 1. Codes, message functions, deletions (AC-001, AC-004)

- In `runtime-contracts/.../error/shellcontent/InstallShellContentErrors.kt`, add three `InstallFailureCode` entries: `INVALID_GOAL_PLANNING_PREPARATION_SCHEMA`, `GOAL_PLANNING_PREPARATION_CONFLICT` and `GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE`.
- Add three message functions next to the enum. Each returns `SkillBillRuntimeException` and takes `cause: Throwable? = null`:
  - `invalidGoalPlanningPreparationSchemaError(sourceLabel, fieldPath, reason, cause)`: SCHEMA code, text `"Goal planning preparation '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at '${fieldPath.ifBlank { "<root>" }}': $reason"`.
  - `incompatibleGoalPlanningPreparationContractError(sourceLabel, fieldPath, reason, cause)`: the same text with the CONTRACT_INCOMPATIBLE code. Producers pick this function explicitly; never derive the code from `fieldPath`.
  - `incompatibleGoalPlanningPreparationRecoveryError(workflowId, subtaskId, reason, cause)`: CONFLICT code, text `"Goal planning preparation '$workflowId' subtask $subtaskId cannot be recovered: $reason"`.
- Delete both classes (around lines 188 and 199).
- In `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`, delete exactly the rows `runtime-contracts:IncompatibleGoalPlanningPreparationRecoveryError` and `runtime-contracts:InvalidGoalPlanningPreparationSchemaError`.
- `isShellContentContractFailure()` already accepts `InstallFailureCode`, so the guarded catch sites keep absorbing these failures without a change.
- Widen two function-type parameters from `ShellContentContractException` to `SkillBillRuntimeException`, because the new functions do not return a `ShellContentContractException`:
  - `nonObjectError` at `ContractValidatorWireInput.kt:27`;
  - `error: (String) -> …` at `FeatureTaskRuntimeHandoffFoundationSchemaValidators.kt:42`.

  Existing lambdas that return legacy subclasses still type-check.

### Task 2. Ports value and result families (AC-002, AC-003, AC-005)

- Add `data class GoalPlanningPreparationConflict(val workflowId: String, val subtaskId: Int, val reason: String, val cause: Throwable?)` under `runtime-ports/.../ports/goalrunner/model/`.
- Add result families under `skillbill.ports.goalrunner.model`, shaped like `WorkflowGitOperationResult`. Each is a `sealed interface` with nested data variants and a `data class Conflicted(val conflict: GoalPlanningPreparationConflict)`. Copy the nested data-variant shape, not that type's `get()` bodies.

  | Family | Success variant |
  |---|---|
  | write | `data object Applied`, or `data class Applied(val value: …)` when the current return is not `Unit` |
  | shared lookup | `data class Found(val checkpoint: SharedGoalPreplanCheckpoint?)` |
  | plan lookup | `data class Found(val plan: GoalSubtaskPlanCheckpoint?)` |
  | plan list | `data class Found(val plans: List<GoalSubtaskPlanCheckpoint>)` |
  | delete/invalidate | `Applied` carrying the current return value, if any |

- Change the D1 methods in `runtime-ports/.../ports/goalrunner/GoalPlanningPreparationRepository.kt` to return the matching family. Leave every other method, and the existing default bodies on `boundedStatus` and `preparedPlanCount`, unchanged.
- `PortsDeclarationArchitectureTest` constraints: no top-level object, no class with behaviour, no cast, no extension or unwrap function, and no new interface default body.
- Update the ports testFixtures `GoalPlanningPreparationRepositoryDefaults` and `EmptyGoalPlanningPreparationRepository` so they return `Found(null)`, `Found(emptyList())` or `Applied`.

### Task 3. SQLite stores return `Conflicted` (AC-002, AC-004)

These files are under `runtime-infra/sqlite/.../infrastructure/sqlite/workflow/goalrunner/`.

- `planning/GoalPlanningPreparationSqlNormalize.kt`:
  - Next to `translateSqlFailure` (:26), add a result-producing sibling for the D1 result methods. It returns `Conflicted(GoalPlanningPreparationConflict(workflowId, subtaskId, "SQLite rejected the immutable planning checkpoint: …", sqlException))`.
  - The throwing form stays for non-result methods and is built with `incompatibleGoalPlanningPreparationRecoveryError`.
  - `rejectLegacy` (:41) yields a conflict value with the reason `"legacy 0.1 pair requires hard reset or operator migration"`.
  - Schema throws at :53 and :66 use the SCHEMA function, except the D2 producers `normalizedProvenanceFailure` and `normalizedEnvelopeFailure`, which use the CONTRACT_INCOMPATIBLE function.
- `shared/GoalSharedPreplanSql.kt` returns `Conflicted` instead of throwing at:
  - :48, the immutable checkpoint, subtask 0, after a failed insert;
  - :89, a replace UPDATE that matched 0 rows, before the cascade or restamp;
  - :136, :180 and :211;
  - :344, the `ResultSet.toShared` identity mismatch, which flows into `findSharedPreplan`.
- `subtask/GoalSubtaskPlanSql.kt` returns `Conflicted` at :129, :143, :172, :185 and :280. :280 flows into find and list.
- `GoalPlanningPreparationRecordSql.kt:22`: `recoveryIdentityFailure` inside `inNestedWriteTransaction` makes `markPrepared` return `Conflicted`.
- **Implement must confirm:** `inDatabaseTransaction` commits any returned value. Each `Conflicted` return must therefore come before any statement in the same nested transaction has written. Check the replace and cascade path at `GoalSharedPreplanSql:89` and `markPrepared` at `RecordSql:22` in particular. Where a write precedes the conflict, move the conflict check ahead of the write into a pre-write helper returning `GoalPlanningPreparationConflict?`; that helper also keeps the code under detekt ReturnCount and ThrowsCount. SQL-fault translation keeps throwing, so a mid-cascade fault still rolls back.
- The remaining schema throwers switch to the SCHEMA function with unchanged text:
  - `GoalSubtaskPlanSql.kt` and `GoalSharedPreplanSql.kt`;
  - `GoalPlanningStatusProjectionSql` (5 sites) and `GoalPlanningPreparationSqlHydrate` (5);
  - `RecordSqlQueries:167` and `RecordSqlHydrate:14`;
  - `RecordSqlValidation:13`, except its `provenanceFailure` and `envelopeFailure` (D2), which use CONTRACT_INCOMPATIBLE;
  - `core/migration/area/GoalPlanningSchemaMigrations.kt:36`. It uses SCHEMA unless the census grep shows it rejects one of the four contract fields.
- Update the store wrappers that delegate the D1 methods, and `SQLiteRepositories.kt`, to the new signatures.

### Task 4. Infra-contracts and engine schema sites (AC-001, AC-004)

- The infra-contracts schema sites move to the SCHEMA function with unchanged text: `ContractValidatorWireInput.kt:93` (the `GOAL_PLANNING_PREPARATION_ENVELOPE` kind, fieldPath `"<root>"`) and `workflow/goal/GoalPlanningPreparationSchemaValidator.kt:43, 140, 149, 168`. The JSON-schema const reasons stay SCHEMA (D2).
- These engine sites also use SCHEMA:
  - `goalplanning/GoalPlanningPreparationValidator.kt:29, 45`;
  - `GoalPlanningPreparationCheckpoint.kt:286` (`requirePlanningPayloadHash`);
  - `GoalPlanningStoredRecord.kt:20, 32` and `GoalPlanningPreparationRecordMapping.kt:35`;
  - `hydration/GoalChildPlanningHydrator.kt:92, 102, 242`;
  - `context/GoalPlanningSharedContextPacket.kt:53, 282`.
- `goalrunner/planning/recovery/GoalPlanningProvenanceRecoverability.kt:54, 69, 86` use SCHEMA, except any site that rejects a stored record on one of the four contract fields; that site uses CONTRACT_INCOMPATIBLE (D2).
- Implement re-runs the spec's census grep and applies D2 to every hit.

### Task 5. Engine conversion helper and the checkpoint (AC-002, AC-003)

- Add `fun GoalPlanningPreparationConflict.toFailure(): SkillBillRuntimeException = incompatibleGoalPlanningPreparationRecoveryError(workflowId, subtaskId, reason, cause)` in a new small file under `runtime-engine/.../engine/goalplanning/`.
- In `GoalPlanningPreparationCheckpoint.kt`, callers that never inspected the conflict unwrap the results and `throw conflict.toFailure()` on `Conflicted`, which keeps today's behaviour. They are the `markPrepared` checkpoint, the shared and subtask checkpoint and recheckpoint paths, and the shared refresh advance and replace.
- `requireRecoverablePlan` (:135) and `readPlanForRecovery` (:194) produce conflict values instead of throwing ("provenance differs"):
  - add `Conflicted(conflict)` to the existing `GoalPlanningRecoveryProgress` and to the private `PlanRecoveryRead`;
  - `recoveryProgress` returns `GoalPlanningRecoveryProgress.Conflicted` for these and for repository `Conflicted` lookups;
  - update every exhaustive `when` over `GoalPlanningRecoveryProgress`.
- `planningRecordRejection` (:294–302) merges its paired catches into one `catch (error: SkillBillRuntimeException)` guarded by `rethrowUnless(code == INVALID_GOAL_PLANNING_PREPARATION_SCHEMA || code == GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE || error is InvalidFeatureTaskRuntimePhaseOutputSchemaError)`. It keeps returning `"stored record failed its durable contract: ${error.message}"`. Subtask 5 later retargets the phase-output arm.
- `reset/WorkflowGoalRunnerScopedReplanPersistence` throws `conflict.toFailure()` on `Conflicted`, so its transaction still rolls back on the throw.

### Task 6. Launch reader path (AC-002, AC-003, test obligation)

- **Hydrator.** In `hydration/GoalChildPlanningHydrator.kt`:
  - `hydrate` returns an engine sealed result: `Hydrated(GoalChildPlanningHydrationResult)` or `Conflicted(conflict)`.
  - Its conflict sources are repository `Conflicted` lookups, `requireMatchingPreparation` (:122) and `requireImportedPayloadValid`.
  - `requireMatchingImport` (:77) returns `GoalPlanningPreparationConflict?`.
  - `requireImportedPayloadValid` merges its paired catches (:301/:303) into one catch with the Task 5 guard, and returns the conflict value that `importedPayloadRecoveryError` (~:313) now builds, with its cause kept.
  - The phase-output throw at :229 stays for subtask 5.
  - Update `GoalChildPlanningHydratorPort`, `GoalChildPlanningHydratorPortAdapter` and the test no-ops.
- **Child persistence.** In `reset/WorkflowGoalRunnerChildWorkflowPersistence.kt`:
  - `saveInTransaction` returns `Saved(…)` or `Conflicted(conflict)`.
  - Its conflict sources are `requireConsistentChildSetup`, the persisted-identity mismatch (:71), `requireMatchingGoalContinuation` (:186), `requireMatchingImport` and hydration.
  - **Required end state:** no write precedes a returned conflict. For a new child, run the read-only hydration before `updateParentForChildWorkflow` and pass its result into `openGoalChildWorkflow`.
  - **Implement must confirm** that `engine.openRecord` performs no write that hydration depends on.
  - Non-conflict failures keep throwing and roll back. Extract a pre-write check returning `GoalPlanningPreparationConflict?` to stay within the detekt limits.
- **Manifest store.** In `manifest/WorkflowGoalRunnerManifestStore.kt`, `saveNewChildWorkflow` (:354) returns `Saved(state)` or `Conflicted(conflict)`. On `Conflicted`, the transaction commits nothing and the projection file is not written. Update the `GoalRunnerManifestStore` declaration and its fakes.
- **Launch prepare.** In `launch/GoalRunnerSubtaskLaunchPrepare.kt`:
  - `prepareAttemptedLaunch` (:191) returns `Prepared(…)` or `Conflicted(conflict)`.
  - `blockedOnRecoveryError` (:137) becomes `blockedOnPreparationConflict(state, conflict, request)`. It targets `conflict.subtaskId`, which keeps 0 for shared-preplan conflicts, and builds its reason with `goalPlanningChildImportConflictBlockedReason(issueKey, conflict.subtaskId, conflict)`.
  - The `else -> throw error` arm goes away. The existing `runCatching` around `markBlocked` and `blockedReviewBaselineIteration` stay.
- **Selected-subtask loop.** In `execution/core/GoalRunnerSelectedSubtaskLoop.kt:160`, replace `runCatching { prepareAttemptedLaunch(...) }.fold(...)` with a `when` over the result, so non-conflict failures propagate as they do today. This removes a `runCatching` and adds none.
- File sizes are 341, 441, 444 and 429 lines. Split a file where the line-ceiling guard or detekt requires it, keeping it in the same package.

### Task 7. Run-progress, sweep, remedies and classifier readers (AC-003, AC-004)

- `planning/state/GoalPlanningRunProgress.kt`:
  - `pendingUnits` and `recoveredPendingUnits` branch on `GoalPlanningRecoveryProgress.Conflicted`. They stop on `conflict.subtaskId` with `preparationStateReadReason(conflict, issueKey, subtaskId)`.
  - The existing `runCatching` stays for non-conflict failures, which stop on subtask 0 with `"Goal planning preparation state could not be read: ${error.message}"`.
  - `requireStoredPlansReady` (~:253) returns the unready subtask id (`Int?`) instead of throwing. Its caller stops through the existing `goalPlanningPreparationStateReadStopReason(reason, recordedSubtaskId, issueKey, subtaskId)` overload with the reason `"stored plan's governed sub-spec has no ready implementation details; replan this subtask"`.
  - :158 uses the conflict overload.
- Delete `recoverySubtaskId` from `outcome/GoalPlanningSweepOutcomeDerivationTerminalClass.kt:45`.
- In `outcome/GoalPlanningSweepOutcomeDerivation.kt:54`, `preparationStateReadReason` takes the conflict value. A `Throwable` overload returns the generic text only.
- `sweep/GoalPlanningSweep.kt:71` and `context/GoalPlanningSharedPreplanSettlement.kt:182` branch on the shared lookup result: `Conflicted` stops with the conflict-overload reason, and their existing `runCatching` stays for coded schema and SQL faults.
- `remedies/GoalPlanningOperatorRemedies.kt`:
  - `goalPlanningPreparationStateReadStopReason(conflict: GoalPlanningPreparationConflict, issueKey, subtaskId)` replaces the `Throwable` version with its `as?` cast.
  - The generic `Throwable` fallback text stays available for non-conflict failures.
  - `statusRecoverabilityOrRefuse` drops its `as?` arm. Lookup `Conflicted` results classify through `classifyGoalPlanningRecovery(conflict)`, and anything else through `classifyGoalPlanningRecovery("", error)`.
- `recovery/GoalPlanningRecoveryKind.kt`:
  - `classifyGoalPlanningRecovery(conflict: GoalPlanningPreparationConflict)` calls `classify(conflict.reason, conflict.cause)`.
  - `goalPlanningChildImportConflictBlockedReason(issueKey, subtaskId, conflict)` uses `conflict.reason.ifBlank { conflict.toFailure().message.orEmpty() }`.
  - `causeIndicatesContractVersionHardReset` follows D3, with no reads of `fieldPath`, `reason` or `payloadFreeReason`.

### Task 8. Test conversions (common acceptance criteria)

Convert type assertions to `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(InstallFailureCode.<ENTRY>, error.code)`. Where a method now returns a result, assert on `Conflicted.conflict` instead. Keep every message, `contains`, payload and persistence assertion byte-for-byte. Constructions of the deleted classes switch to the message functions or `GoalPlanningPreparationConflict`.

- **Classes asserted:** `GoalPlanningPreparationStoreTest`, `GoalPlanningSweepTest`, `GoalPlanningPreparationCheckpointTest`, `WorkflowServiceTest`, `GoalPlanningRecoveryClassificationTest` (constructor-to-function edits only, D3), `GoalPlanningPreparationSchemaValidatorTest`, `GoalPlanningPreplanProseReadTest`, `GoalPlanningPreparationValidatorTest`, `GoalPlanningSharedContextPacketTypedErrorTest`, `GoalPlanningPreparationStoreSchemaParityTest` (:89, :100), `GoalPlanningPhaseOutputMigrationTest`, `GoalPlanningPreparationSchemaContractVersionTest`, `GoalRunnerReplanTest`, `GoalPlanningPreparationRecordMappingTest` and `GoalRunnerTest`.
- **`GoalPlanningStatusReasonCoherenceTest`:** only fixture construction changes. Every assertion stays, including the one that "cannot be recovered" is absent.
- **`FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest`:** the pinned `GOAL_PLANNING_PREPARATION_ENVELOPE` label becomes `"InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA"`.
- **Fakes and supports:** update every one that implements the changed ports, the hydrator port or the manifest store. Among them are `ApplicationPersistencePortTestSupport`, `GoalRunnerTestFactory`, `FeatureTaskRuntimeRunnerTestSupport`, `FeatureTaskGitIntegrationTestSupport`, `IdeStatusServiceTestSupport`, `WorkflowStateTestFixtures` and `SqliteTestDatabaseFixtures`. These edits only change return shapes; they add no behaviour.

### Task 9. New regression (test obligation)

Add one test in `GoalRunnerTest`, reusing its in-memory manifest store and runner factory.

- **Setup:** the store's `saveNewChildWorkflow` returns `Conflicted` for subtask 2 with a distinctive reason, in a manifest where subtask 1 is already done.
- **Assertions:**
  - the run blocks subtask 2;
  - the blocked reason contains the distinctive reason;
  - the blocked reason does not contain "cannot be recovered";
  - no phase agent was launched.
- **Bugs caught:** the returned conflict is dropped, or it is routed to the wrong subtask id.

No other new test is planned. The other converted tests already cover the classifier and the sweep and status readers.

### Constraints

- Messages stay byte-identical, including the `<unknown>` and `<root>` handling.
- Pass enum entries, never wire strings.
- Add no new `runCatching`, typealias, `@Suppress`, relaxed mock, module, `//` or non-KDoc comment, or property on `SkillBillRuntimeException`.
- `skillbill.error.core` does not import shellcontent. runtime-domain is untouched.
- detekt limits: ThrowsCount 2, ReturnCount 4, LongMethod 70, CyclomaticComplexMethod 15. `ArchitectureScanSupport.kt` must not grow.
- No installer or install-sync step, and no persisted schema, payload, flag or dependency change.
- Implement runs no build, tests or checks. The build phase owns compile proof. The validate phase owns the full gate, including `PortsDeclarationArchitectureTest`, `FailureCodeTotalityArchitectureTest`, the package-cycle, wire-vocabulary and comment guards, and Spotless in a plain clone.
