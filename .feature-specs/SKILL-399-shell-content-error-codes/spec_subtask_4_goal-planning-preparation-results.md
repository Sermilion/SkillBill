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

This plan uses only the upstream preplan digest. Its current symbols and ownership supersede the historical census and line anchors above. Implement confirms the assumptions below against the current tree, without waiting for another subtask. No new decomposition or dependency is required.

### Ordered implementation tasks

1. Declare preparation outcomes at the ports boundary. Serves AC-002, AC-003 and AC-005.

   Update `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/goalrunner/GoalPlanningPreparationRepository.kt`. Put `GoalPlanningPreparationConflict` and declaration-only sealed result families in its existing `skillbill.ports.goalrunner.model` package, following `WorkflowGitOperationResult`'s nested variant style. The conflict carries workflow ID, subtask ID, reason and nullable cause. Preserve the current identity types and nullability rather than inventing replacements.

   Give shared checkpoint, replacement and provenance advance an applied outcome; shared lookup a found outcome carrying its nullable value; shared deletion and invalidation an applied outcome carrying their existing count. Give subtask checkpoint and replacement an applied outcome, lookup a found outcome carrying its nullable value, and ordered listing an outcome carrying the existing ordered list. Every family also has `Conflicted(conflict)`. A successful lookup with null remains distinguishable from conflict. Ports contain no unwrapping extension, repository-driving function or new default body. Assumption for implement to confirm: operation-specific result declarations can share success shapes without losing existing return information; prefer the fewest narrow typed families that preserve those distinctions. Update implementations, consumers and existing test doubles together. Later validation runs `PortsDeclarationArchitectureTest`.

2. Replace SQLite preparation conflicts with returned facts while preserving atomicity. Serves AC-002 and AC-003.

   Update the stores `SharedGoalPreplanStore.kt`, `GoalSubtaskPlanStore.kt` and `GoalPlanningPreparationStore.kt`, and their SQL owners `GoalSharedPreplanSql.kt`, `GoalSubtaskPlanSql.kt` and `GoalPlanningPreparationSqlNormalize.kt` in the digest's `runtime-infra/sqlite` workflow packages. Carry immutable-checkpoint, compare-and-set, provenance, stale-discard, identity, legacy-row, descriptor, manifest-order, spec-hash and path conflicts into `Conflicted`, with the original workflow ID, subtask ID, reason and cause. Replace `rejectLegacy`'s throw with a conflict outcome. Preserve `translateSqlFailure`'s existing wrapping text and SQL cause at the preparation boundary; arbitrary SQL failure must never become found-null or a successful operation. Unrelated database failures keep their existing propagation.

   Detect known conflicts before mutation. Keep shared replacement, sibling-plan deletion and provenance restamping under the existing `ConnectionTransactions.kt` `inNestedWriteTransaction` owner. A conflict must stop subsequent writes and must not commit preceding writes. Assumption for implement to confirm: pre-mutation checks cover ordinary stored-state conflicts, but a late compare-and-set or SQL failure can still follow mutation. If necessary, adapt the existing transaction boundary narrowly to rollback a rejected result before returning it, rather than throwing a preparation-conflict exception or creating a second transaction owner. Preserve nested transaction semantics, and never replay committed writes. Existing preparation-store tests become result assertions and retain persistence assertions. Add or strengthen one atomicity assertion only if those tests do not already prove that a late conflict leaves the prior shared record, sibling plans and provenance intact. This catches committing partial replacement after an exception becomes a normal return.

3. Carry conflicts through engine recovery and selected-child launch. Serves AC-002 and AC-003.

   Update `runtime-engine`'s `goalplanning/GoalPlanningPreparationCheckpoint.kt` checkpoint, recheckpoint, lookup, recovery-progress, provenance-advance and refresh paths. Propagate conflict values through recovery consumers. Terminating callers that never inspect the conflict may use the engine-owned `GoalPlanningPreparationConflict.toFailure()` extension described in task 4. Such conversion must not replace the value boundary for a conflicted stored plan.

   Change `goalrunner/launch/GoalRunnerSubtaskLaunchPrepare.kt`, `goalrunner/planning/hydration/GoalChildPlanningHydrator.kt` and `goalrunner/reset/WorkflowGoalRunnerChildWorkflowPersistence.kt` to carry the conflict through hydration and persistence. `blockedOnRecoveryError` takes the value. Preserve the transaction containing child creation, planning hydration and `manifestStore.saveNewChildWorkflow`; a conflict cannot leave a child or attempted manifest partially persisted. Update `GoalPlanningSweepOutcomeDerivationTerminalClass.kt`, `GoalPlanningOperatorRemedies.kt`, `GoalPlanningRecoveryKind.kt` and `GoalPlanningRunProgress.kt` to consume the value. `pendingUnits` branches on the conflict's subtask ID; `requireStoredPlansReady` returns the unready subtask or a conflict. Sweep and child-import reasons use the recovery reason directly, without the failure factory's prefix. Remove obsolete conflict catches, merge remaining paired catches only with their original handled sets, and retarget `GoalPlanningPhaseAttemptGateBurstCap.kt` if it still discriminates either removed class.

   Preserve `GoalPlanningStatusReasonCoherenceTest`'s reason assertions. Convert existing sweep, persistence and `GoalPlanningPreparationCheckpointTest` conflict fixtures to values. Satisfy the existing selected-subtask launch obligation with one regression, reusing current engine planning fixtures and source sets, unless an existing converted test already proves both the conflicting child identity and its exact reason. Its concrete bug is dropping a returned conflict or blocking another child's subtask.

4. Replace the two Install throwable declarations with codes and factories. Serves AC-001 and supports AC-002 and AC-003.

   Remove `IncompatibleGoalPlanningPreparationRecoveryError` and `InvalidGoalPlanningPreparationSchemaError` from `runtime-contracts`' `error/shellcontent/InstallShellContentErrors.kt`. Create or extend `InstallFailureCode` with the schema, conflict and contract-incompatible entries from this spec, retaining entries already introduced by other work. Keep shared structured failure factories next to the enum with the old parameters and cause. The engine extension builds the conflict failure through that factory and preserves `Goal planning preparation '<workflowId>' subtask <subtaskId> cannot be recovered: <reason>` byte-for-byte. Schema factories also preserve their original substitutions, suffixes and causes. Caught failures expose only code, message and cause.

   Include `InstallFailureCode` in `ShellContentContractFailures.kt` without dropping `GoalTelemetryRowFailureCode` or broadening guarded catches. Preserve `failureCodeLabel() ?: existingExpression` at touched rendering seams. Remove only the two deleted classes' whole rows from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Convert schema-parity and validator exception assertions to `SkillBillRuntimeException` plus the exact code, retaining message and payload assertions. Later validation runs `FailureCodeTotalityArchitectureTest` and preparation schema parity coverage.

5. Select contract incompatibility from producer facts and classify causes by code. Serves AC-003 and AC-004.

   Update preparation producers named by the digest: `GoalPlanningPreparationValidator.kt`, `GoalPlanningStoredRecord.kt`, `GoalPlanningPreparationSqlNormalize.kt`, `GoalPlanningPreparationRecordSqlHydrate.kt`, shared and subtask normalization, `GoalPlanningSchemaMigrations.kt`, and infrastructure contracts' `workflow/goal/GoalPlanningPreparationSchemaValidator.kt`. Known planning or phase-output contract ID and version mismatches select the Install contract-incompatible code from structured fields or validation facts. Preserve ordered violations, the phase-output-version hard-reset instruction and logger behavior. For sites formerly using another slice's throwable, change only the producer and receiving handled sets required by this conversion; leave that class's ownership intact.

   Make `GoalPlanningRecoveryKind.kt`'s `causeIndicatesContractVersionHardReset` walk causes and match that exact code. Remove preparation and phase-output exception-property reads, without parsing a completed message to recover fields. Preserve the generic reason classifier and its four contract-key tokens, hard-reset wording and contract-version incompatibility wording. Retain existing hard-reset behavior for wrapped failures and unrelated causes. Use `GoalPlanningRecoveryClassificationTest`, `GoalPlanningPreparationValidatorTest` and schema-parity tests for code and recovery assertions. If existing coverage lacks it, add one boundary case proving a wrapped incompatible code triggers hard reset without a typed property or recognizable message; an unrelated code must retain its existing classification. This catches misclassifying coded version rejection after typed fields disappear.

6. Complete integration, transition checks and the later-phase handoff. Serves all criteria and common acceptance criteria.

   Implement checks the remaining subclass and codeless-construction condition after its changes. The digest proves external legacy users remain, so assume the open exception, codeless constructor and legacy base must stay. Finish their removal only if intervening work has removed every remaining use under the supplied parent transition rule. Preserve every guarded rethrow and coded classifier branch; do not remove the legacy classifier term prematurely while legacy failures still need recognition.

   Keep recovery, quarantine, telemetry, diagnostic payloads, persisted schemas, resource paths and bytes unchanged. Review and audit apply A1 through A12 and G1 through G7, with attention to A4 declaration-only ports, A6 wire ownership, A7 handled sets, A9 package cycles and A10 test placement. No new modules, dependencies, aliases, public raw-map results, locator accessors, bags, suppressions or `runCatching`. Core cannot import shellcontent, domain cannot import ports or `java.nio`, and contracts cannot depend on engine or infrastructure. Preserve the phase-generic SKILL-380 attempt boundary and accepted-step authority. Stay within the stated detekt limits; do not add authored Kotlin line comments or non-interface KDoc, grow `ArchitectureScanSupport.kt`, loosen architecture guards or move test setup into production.

   Implement adds or adapts the boundary tests described above. Test execution belongs to validate, and build proof belongs to build. Later validation covers the preparation-store, checkpoint, schema-parity, recovery-classification, status-reason, selected-launch, sweep and persistence tests, then the full project validation strategy including unit tests, detekt, formatting, failure totality, ports declarations, typed parse boundaries, package cycles, wire vocabulary and comment guards. The digest identifies the full Kotlin gate as `./gradlew check --continue --parallel -q --warning-mode none`, with JDK 21 and strict agnix required by CI. Spotless runs in a plain clone. Validation and review may repair required production wiring, test setup, formatting and lint while preserving behavior and architecture rules. History, commits, PR work and runtime settlement stay with their owning phases. No installer or generated-artifact work is planned.

### Test obligations and phase limits

Reuse existing regression coverage before adding a test. The required selected-child conflict outcome serves AC-002 and AC-003. Atomic rollback coverage serves AC-002, and wrapped-code hard-reset coverage serves AC-004; add either only if the existing suites do not already prove that behavior. Governed parity and architecture coverage remain mandatory. No trivial result-constructor or forwarding tests are needed.

This phase changes only this sub-spec. No source discovery, build, test execution, full validation or installation occurs during planning. There is no missing input that prevents implementation; assumptions about result family shape, current identity types and late-conflict rollback are recorded in their affected tasks for implement to confirm.
