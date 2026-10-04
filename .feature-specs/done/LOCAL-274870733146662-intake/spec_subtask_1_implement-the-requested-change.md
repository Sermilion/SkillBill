# LOCAL-274870733146662 Subtask 1 - Implement the requested change

Parent spec: [.feature-specs/LOCAL-274870733146662-intake/spec.md](spec.md)
Issue key: LOCAL-274870733146662

## Scope

Implement automatic durable schema migration for supported goal-planning and feature-task phase-output versions. Use the current base/SKILL-380-phase-slot-strategies branch as the base.

Problem: GoalPlanningProvenanceRecoverability requires exact phase-output version equality and classifies every version change as HARD_RESET, even though SQLite migrations retain older planning rows and the runtime can read historical phase outputs. Operators need to resume safely after schema upgrades without discarding completed subtasks or commits.

Required outcomes: Declare explicit supported source-to-target migration paths for persisted goal-planning preparation records and phase-output payloads. Migrate supported older versions automatically at the owned readiness or resume boundary. Validate old records against their source contract before conversion and validate converted records against the target contract before publishing them. Preserve workflow identity, completed and skipped subtask state, commit SHAs, checkpoint ownership, provenance meaning, and completed work. Update coupled payload bytes, hashes, provenance, and version fields atomically in the owning transaction. Make migration idempotent and recoverable after interruption. Emit content-free migration diagnostics identifying source version, target version, and result. Unsupported or corrupt input must block with typed actionable errors and leave original durable state intact. Never merely rewrite a version token to make invalid content pass. Where a supported schema change requires planning refresh, regenerate only affected unfinished planning while preserving completed subtasks and commits. Implement and test the concrete 0.6-to-0.7 transition where the old payload supplies enough information; explicitly classify non-convertible records instead of inventing evidence.

Also prevent the installation failure that prompted this work: freshly packaged runtime producers and bundled schemas must agree on all relevant contract-version pins before installation is considered successful. A mixed runtime artifact is an install failure, not a durable-state migration case.

Scope: existing goal-planning preparation and phase-output contract families, their persistence and resume paths, and packaging parity checks. Reuse existing migration ownership and modules. Do not build a speculative migration framework for unrelated schemas. This supersedes the policy requiring hard reset for every historical schema version. Keep loud failure for unsupported versions, corruption, or unsafe conversion. Follow all repository architecture, wire-key, source-generation, test-value, and comment rules. Do not alter SKILL-399 specs or work.

Validation: real persisted historical fixtures cover automatic supported migration, preservation of completed subtask commits, repeat migration, transactional rollback on invalid target or injected failure, interruption and resume, unsupported versions, and corrupt input. Packaging checks reject mismatched producer and bundled schema pins. Run the full required project validation strategy.

If the intake contains an unresolved tracker link or issue key, fetch that exact issue through its connected tracker before planning. Linear, Jira, and other connected trackers use the same rule. Use the returned requirements, not the URL title. If lookup fails or the connection is unavailable, block with the returned reason before implementation; never infer or substitute requirements. A LOCAL key identifies this workflow and is not a claim that a tracker issue exists.

## Acceptance Criteria

1. Implement automatic durable schema migration for supported goal-planning and feature-task phase-output versions. Use the current base/SKILL-380-phase-slot-strategies branch as the base. Problem: GoalPlanningProvenanceRecoverability requires exact phase-output version equality and classifies every version change as HARD_RESET, even though SQLite migrations retain older planning rows and the runtime can read historical phase outputs. Operators need to resume safely after schema upgrades without discarding completed subtasks or commits. Required outcomes: Declare explicit supported source-to-target migration paths for persisted goal-planning preparation records and phase-output payloads. Migrate supported older versions automatically at the owned readiness or resume boundary. Validate old records against their source contract before conversion and validate converted records against the target contract before publishing them. Preserve workflow identity, completed and skipped subtask state, commit SHAs, checkpoint ownership, provenance meaning, and completed work. Update coupled payload bytes, hashes, provenance, and version fields atomically in the owning transaction. Make migration idempotent and recoverable after interruption. Emit content-free migration diagnostics identifying source version, target version, and result. Unsupported or corrupt input must block with typed actionable errors and leave original durable state intact. Never merely rewrite a version token to make invalid content pass. Where a supported schema change requires planning refresh, regenerate only affected unfinished planning while preserving completed subtasks and commits. Implement and test the concrete 0.6-to-0.7 transition where the old payload supplies enough information; explicitly classify non-convertible records instead of inventing evidence. Also prevent the installation failure that prompted this work: freshly packaged runtime producers and bundled schemas must agree on all relevant contract-version pins before installation is considered successful. A mixed runtime artifact is an install failure, not a durable-state migration case. Scope: existing goal-planning preparation and phase-output contract families, their persistence and resume paths, and packaging parity checks. Reuse existing migration ownership and modules. Do not build a speculative migration framework for unrelated schemas. This supersedes the policy requiring hard reset for every historical schema version. Keep loud failure for unsupported versions, corruption, or unsafe conversion. Follow all repository architecture, wire-key, source-generation, test-value, and comment rules. Do not alter SKILL-399 specs or work. Validation: real persisted historical fixtures cover automatic supported migration, preservation of completed subtask commits, repeat migration, transactional rollback on invalid target or injected failure, interruption and resume, unsupported versions, and corrupt input. Packaging checks reject mismatched producer and bundled schema pins. Run the full required project validation strategy.

## Non-Goals

- None

## Dependency Notes

Depends on: none
The full goal owns planning and execution of the supplied requirements.

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.

## Next Path

Complete the goal and prepare its pull request.

## Spec Path

.feature-specs/LOCAL-274870733146662-intake/spec_subtask_1_implement-the-requested-change.md

## Implementation Details

This plan uses only the upstream preplan digest. Every ordered task serves AC-001, the single acceptance criterion above. These are implementation steps within the current subtask, not a new decomposition. Title, scope, acceptance criteria, dependencies, validation strategy, and next path remain unchanged.

### Support decisions and constraints

- Use `base/SKILL-380-phase-slot-strategies` as the base. Preplan established that the checkout and decomposition manifest already use that branch at `8527efaee`; the previous outline's claim that the manifest uses `main` was stale. Do not alter SKILL-399 specs or work.
- Support normalized preparation contract 0.2 with planning provenance 0.2 and phase-output provenance 0.6, converting the persisted phase outputs and their provenance to 0.7 while keeping preparation version 0.2. Support independently persisted feature-task 0.6 outputs through the same evidence-preserving conversion. Validate current records without rewriting them. Other transitions, including legacy paired preparation 0.1, are explicitly unsupported. Readable versions and SQL admission do not imply supported conversion.
- The historical 0.6 contract precedes `51a310aae`; that commit introduces 0.7 and `uniformSettlement`. Target uniform outputs require nonblank `produced_outputs.value` for preplan, plan, implement, simplify, audit, validate, write_history, and pr, and `failure_disposition` for blocked or failed uniform outputs. Preserve every supplied semantic field. Never synthesize prose from summaries or dispositions from decoder defaults. Source-valid records missing target evidence are non-convertible, distinct from corrupt or unsupported records.
- The evidenced preplan/plan transition reuses planning without an agent refresh. Do not add a speculative refresh transition. Preserve existing scoped-replan safeguards for any explicitly supported transition that later needs refresh, including terminal-child and liveness checks. Corruption or missing evidence never authorizes refresh or deletion.
- Keep fresh agent prose behavior introduced by `ea92e25db`. Historical schemas apply only to persisted source and converted-target validation. Do not restore the removed strict fresh-response validator.
- Policy belongs in engine planning and lifecycle owners, schema compilation in infrastructure contracts, SQL in SQLite, typed repository declarations in ports, and composition in runtime-core. Apply A1 through A12 and the section 5 architecture review checklist. Declare wire keys once in their owning module, preserve cancellation, introduce no collaborator bags or ambient effects, and widen no guard baseline. Expected classifications use typed results; fatal failures use `SkillBillRuntimeException` with owner-declared `RuntimeFailureCode` entries. Keep authored Kotlin free of prohibited comments and place owner tests alongside production packages, with composition tests under `skillbill.di`.
- Separate structural database migration, durable payload migration, and packaged contract parity. No conversion during query hydration, status reads, or installation. No feature flag, automatic hard reset, unrelated schema framework, or install refresh belongs in this child plan.
- Implement must confirm the exact minimal wiring and failure-code names within the owners identified below. The digest does not name an existing database-free packaged-parity command or a transaction-aware coupled migration operation. Add these bounded capabilities at the stated owners rather than assuming an existing doctor command is database-free. This is an implementation choice, not a missing planning input.

### Ordered tasks

1. Package immutable historical contracts and define the bounded conversion.

   AC-001 coverage: explicit source-to-target support, source validation, target validation, preservation of semantic evidence, and typed actionable refusal.

   Paths and symbols: `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/featuretask/FeatureTaskRuntimeContractVersions.kt`; `orchestration/contracts/goal-planning-preparation-schema.yaml`; `runtime-kotlin/runtime-infra/contracts/build.gradle.kts`; `ClasspathContractSchemaLoader`, `CompiledSchemaRequest`, and `SchemaIdentityRequest`; `GoalPlanningPreparationSchemaValidator`; and the existing engine planning/lifecycle contract boundaries.

   Recover the exact historical phase-output 0.6 contract from the parent of `51a310aae` and the 0.7 contract introduced by that commit. Package immutable migration resources with distinct version-aware resource and cache identities. Supply the historical preparation 0.2 contract applicable to nested phase-output provenance 0.6. Keep historical pins separate from current producer pins. Reuse Draft 2020-12 compilation and schema identity validation rather than adding another schema loader.

   Declare a bounded 0.6-to-0.7 conversion and typed results for current-valid, convertible, unsupported, corrupt, non-convertible, and stale-source outcomes. Validate the source envelope before conversion, including contract IDs and versions, exact UTF-8 hashes, identity, phase, completed planning status, and required context. `readStoredPlanningRecord` alone is insufficient. Reuse `GoalPlanningSharedContextPacket` for packet identity, content, topology, and integrity checks. Preserve prompts, verdicts, prose, structured outputs, finding dispositions, repair receipts, and other semantic evidence. Change only transition-authorized fields, serialize target bytes, recompute their hashes, and validate both target schemas and semantic constraints before publishing anything.

   Test obligations: contract-owner coverage must catch a version-token-only upgrade that accepts invalid source content, a decoder that invents missing prose or disposition, and cache aliasing that validates 0.6 against the wrong contract. Use authentic source-valid convertible and non-convertible records, plus corrupt and unsupported inputs. Keep governed contract/version parity tests. Do not duplicate literal-only cases or test trivial forwarding.

2. Add guarded, transaction-aware publication of the complete coupled set.

   AC-001 coverage: atomic payload/provenance/hash updates, ownership preservation, stale-source refusal, idempotence, interruption recovery, and content-free diagnostics.

   Paths and symbols: `GoalPlanningPreparationRecord.kt`, `GoalPlanningPreparationRepository.kt`, `workflow/goalrunner/planning/GoalPlanningPreparationStore.kt`, `workflow/goalrunner/shared/GoalSharedPreplanSql.kt`, `workflow/goalrunner/subtask/GoalSubtaskPlanSql.kt`, `SQLiteDatabaseSessionFactory`, and `DatabaseSessionFactory`. Preserve structural ownership in `core/migration/area/GoalPlanningSchemaMigrations.kt` and `core/migration/DatabaseMigrations.kt`.

   Extend the existing preparation repository with a bounded typed migration operation and expected-source version/digest guards. Keep JDBC, SQL names, and raw payload maps outside inward public APIs. Join the owning immediate transaction with transaction-aware writes in the current SQLite owners. Do not call replacement operations that use `inNestedWriteTransaction` from inside another write transaction. Do not use `restampSubtaskPlanProvenance` as conversion, since it changes sibling provenance without their payload bytes and hashes.

   Load and validate the complete shared preparation, affected subtask plans, matching child imports, and imported planning outputs before publication. Recheck source versions, digests, identity, and import ownership inside the transaction. Atomically publish new bytes, exact hashes, phase-output provenance, and version fields across that set. Validate the resulting child workflow snapshot through its persistence owner. Do not hydrate again, append completion events, or rewrite ledger metadata. A stale source or mismatched import blocks the entire set.

   Preserve workflow identity, normalized issue/repository identity, parent-spec and immutable-manifest provenance meaning, paths, manifest order, creation times, repair evidence, child links, completed/skipped state, commit SHAs, checkpoint ownership, attempts, ledger sequence, execution origin, status/current step, execution descriptors, review/gate evidence, and finalization records. A valid current target is a no-op. Failure, cancellation, or interruption must expose unchanged source or the complete committed target. Emit migration success only after commit. Record refusals and failures with source version, target version, and result, without payload content; preserve cancellation propagation.

   Test obligations: real SQLite fixtures must catch partial sibling restamping and loss of completed commits or skipped state. Exercise success and repeated admission, stale-source refusal, invalid-target refusal, and an injected failure after an earlier coupled write. Assert original durable bytes on refusal and rollback, not only thrown errors. Exercise cancellation or interruption followed by close/reopen and resume to catch an incompletely published target. Retain migration 46's structural test; its `{}` payloads and placeholder hashes are not valid semantic migration fixtures.

3. Admit goal migration before current planning gates and parent mutation.

   AC-001 coverage: automatic readiness/resume migration, reuse without agent planning, loud refusal instead of silent regeneration, and preservation of completed work.

   Paths and symbols: `GoalPlanningPreparationCheckpoint.kt`, including `GoalPlanningPreparationProjectionGate`; `GoalPlanningStoredRecord.kt`; `GoalPlanningPreparationValidator.kt`; `GoalPlanningProvenanceRecoverability.kt`; `GoalPlanningRecoveryKind.kt`; `GoalRunnerRunPreparation.kt`; `GoalPlanningSweep.kt`; `GoalPlanningSharedPreplanSettlement.kt`; and `GoalRunnerSpecDriftRecovery.kt`.

   Invoke migration at an explicit owned readiness/resume boundary before current-only projection checks, planning-packet recovery, provenance freshness classification, or spec-drift recovery. Preserve admission-before-parent-mutation ordering around repository binding, policies, and pause state. Close diagnostic read scopes before opening repair writes. Ordinary lookup and status operations stay read-only.

   Replace checkpoint lookup and recovery-progress rejection-to-absence behavior for existing invalid or refused records with typed blocking outcomes. Replace blanket historical-version hard-reset guidance and migration-related reason-text matching with typed migration results and non-destructive actionable guidance. Successful migration reaches `classifyGoalPlanningProvenanceRecoverability` with coherent current provenance; retain its current contract equality and freshness checks rather than allowing stale bytes through.

   Paths and symbols for child coupling: `GoalChildPlanningHydrator.hydrate`, `requireMatchingImport`, `PreparedPlanningPayloadValidator`, and `GoalChildPlanningImportMatcher`. Validate matching import provenance, payload hashes, descriptor ownership, completed planning phases, and ledger prefix before converting existing imports. Reuse their validation ownership without invoking hydration or emitting completion again. The shared context stored through `GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD` must remain valid under `GoalPlanningSharedContextPacket`; arbitrary prose cannot replace it.

   Reuse the evidenced 0.6 planning directly. Keep `WorkflowGoalRunnerScopedReplanPersistence.executeScopedReplan`, `cascadeEligiblePlanSubtaskIds`, and spec-drift recovery limited to affected unfinished work when an explicit supported refresh policy applies. Preserve refusal to delete COMPLETE or SKIPPED children and checkpoint pruning only for cleared owners. Do not implement an unevidenced refresh policy for this transition.

   Test obligations: owner tests beside `GoalPlanningSweepTest`, `GoalPlanningRecoveryClassificationTest`, hydration tests, and `GoalRunnerSpecDriftRecoveryTest` must catch a historical checkpoint being mistaken for missing planning, migration after parent mutation, and child-import mismatch being accepted. Assert resumed planning and completed commits remain intact, with no new planning completion or hydration events. Reuse core composition tests under `skillbill.di.goal`, including checkpoint, validator, and store/schema parity coverage, to prove actual wiring. Preserve existing scoped-replan regression coverage; no speculative refresh tests are required.

4. Apply independent feature-task conversion at the shared execution admission boundary.

   AC-001 coverage: automatic feature-task resume, transactional publication, unchanged execution semantics, and no replay of irreversible completed work.

   Paths and symbols: `FeatureTaskRuntimeExecutionEntry.kt`, `FeatureTaskRuntimeExecutionAdmission.admit`, `FeatureTaskRuntimeWorkerCoordinator.kt`, and `FeatureTaskRuntimeWorkflowPersistence.persistArtifactsPatch`. Preserve interpretation-only roles for `NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText`, `fromRecordMap`, `DurableArtifactMapReader`, and `PhaseHistoricalInterpreter`.

   Integrate bounded persisted-output conversion at admission shared by normal entry and worker paths, joining their existing transaction. Preserve immutable identity, execution descriptor compatibility, selected definition, review selection, completed gate evidence, and terminal-state checks. Completed workflows remain terminal. Historical readability and empty-value decoder defaults are not migration proof or execution authorization.

   Validate source records, convert only evidence-complete 0.6 payloads, validate target outputs and the resulting workflow snapshot, then save through the established persistence seam. Preserve outer phase records, attempts, ledger entries, checkpoints, execution origin, workflow status/current step, descriptors, gate/review evidence, and finalization records. Do not replay a settled phase or finalization to fill absent target evidence. Independent migration must not bypass coupled goal-import ownership when that ownership exists.

   Test obligations: admission tests using `ExecutionPlanAdmissionFixture` and `FeatureTaskRuntimeWorkflowPersistenceSeamTest` must catch one worker path skipping migration, historical conversion erasing artifact metadata, and resume replaying finalization. Use persisted evidence and observable workflow outcomes. Verify refusal preserves bytes and prevents execution, and that terminal workflows stay terminal. Share authentic fixtures where ownership allows instead of repeating conversion tests in every caller.

5. Fail mixed CLI and MCP packages before staging promotion.

   AC-001 coverage: actual packaged producer/schema parity, install failure for mixed artifacts, and no durable database access during package checking.

   Paths and symbols: `GoalPlanningPreparationSchemaValidator`, `ClasspathContractSchemaLoader`, `runtime-application/src/main/kotlin/skillbill/application/system/SystemService.kt`, runtime-core composition, `GovernedResourceCopy.kt`, `RuntimeImageConventionPlugin.kt`, and staging/promotion logic in `../../../install.sh`, especially `install_packaged_runtime_distribution` and its source/prebuilt callers.

   Add a database-free packaged contract check suitable for CLI and MCP images. Validate each image against its own classpath resources and producer constants, covering both current preparation variants, preparation contract 0.2, planning provenance 0.2, nested current phase-output provenance 0.7, and supported historical migration resource identities and pins. Missing or mismatched resources fail with an actionable typed package error. Keep historical pins distinct from current producer parity. Do not assume the existing `doctor()` behavior meets the database-free requirement; any health integration must expose the bounded check without database initialization.

   Wire checks into staging before promotion and before installation success for both locally built and checksum-verified prebuilt candidates. Validate CLI and MCP candidates before making the installation successful. Checksums do not establish schema parity. Preserve cleanup and durable databases. Resource-copy or packaging changes must retain governed source-generation rules. This task edits installation code and smoke fixtures; it does not authorize installation refresh in this goal-continuation child.

   Test obligations: retain `PhaseOutputContractVersionPinParityTest`, `GoalPlanningPreparationSchemaValidatorTest`, `GoalPlanningPreparationSchemaContractVersionTest`, and store/schema parity guards. Add actual bundled-resource coverage that catches a nested pin mismatch missed by top-level identity validation, and checks that historical resources do not mask current producer mismatches. Extend `../../../scripts/install_smoke_test.sh` for source and prebuilt CLI/MCP candidates so a checksum-valid mixed artifact cannot promote or report success. Assert package refusal leaves durable state intact and the check works without a database.

6. Complete persisted acceptance evidence and hand execution to validate.

   AC-001 coverage: real historical migration, preservation, repeatability, rollback, interruption recovery, actionable refusal, and full project validation.

   Paths and symbols: SQLite `GoalPlanningPhaseOutputMigrationTest.kt` and `GoalPlanningPreparationStoreTestSupport.kt`; published fixtures `sqliteSessionFactoryForTests` and `establishTemporarySchemaReadiness`; the engine admission/recovery/hydration test owners above; infrastructure-contract tests; core composition tests under `skillbill.di`; and `../../../scripts/install_smoke_test.sh`.

   Build authentic historical envelopes with exact matching UTF-8 hashes and required shared context. Keep placeholder store/structural fixtures separate. Consolidate the task-specific obligations into a few boundary tests covering each distinct rule. The realistic bugs to catch are completed commit loss, duplicate planning or finalization, partial coupled publication, a stale source overwriting newer state, invented conversion evidence, and mixed-package promotion. Cover unsupported version, corrupt source or hash, valid-but-non-convertible source, invalid target, and mismatched parent/child import once each at the owning boundary. Preserve existing past-bug, parity, and validator-backed tests. Add no implementation-shape, call-order-only, or literal-variant tests.

   Validate owns running the complete required strategy identified by preplan in `../../../scripts/validate`, including runtime validation, Gradle check, strict agnix, and agent-config validation, plus installation smoke and actual packaged CLI/MCP parity coverage. Required validation repairs may touch production wiring, fixture setup, formatting, or lint while preserving behavior, assertions, architecture rules, and explicit operator constraints. Buildability proof belongs only to build. Plan runs no tests, compilation, builds, checks, or installation, and any `tests_executed` receipt remains empty.

   Audit inspects the final repository end states against every AC-001 obligation and the architecture section 5 checklist. Review owns branch-diff review. Write_history owns policy/history updates retiring blanket historical-version hard-reset guidance. Commit, push, and PR remain with their owning phases; installation refresh remains with the parent runtime. No further decomposition, dependency work, feature flag, or unresolved planning input is required.
