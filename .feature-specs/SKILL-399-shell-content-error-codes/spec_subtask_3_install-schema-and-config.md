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

Plan from the supplied preplan digest at `8527efaee41cfc2beb316be9b6eec4be017f9deb`. Planning reads no repository evidence beyond this sub-spec. This subtask has no dependencies and does not change the eight-slice decomposition.

### Ordered implementation tasks

1. Replace the sixteen owned declarations with Install codes and message factories. Serves AC-001 and AC-002. Touch `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/InstallShellContentErrors.kt`, the sibling `InstallFailureCode.kt`, and `ShellContentContractFailures.kt`. Create or extend the area enum without replacing entries already introduced by subtask 4. Give each individually listed failure in Scope its own entry; unreadable repo-local config and malformed machine config share the family entry. All sixteen conditions involve configuration, persisted records, schema input or selected install state, so retain coded failures rather than treating them as defects. Structured failures used at multiple sites become public factories returning `SkillBillRuntimeException`, with the old input parameters and optional cause. Message-only `ContractVersionMismatchError` becomes a direct coded construction at each producer. Preserve every message expression byte-for-byte, including blank substitutions and nullable suffixes. In particular, baseline manifest reason renders either `: <reason>.` or `.`; malformed configuration retains path, key, value and reason substitutions. Add Install classification while preserving `GoalTelemetryRowFailureCode`, other converted areas and every guarded rethrow. Core must not import shellcontent. Existing schema and configuration tests will assert the exact codes during validation; do not add factory-by-factory tests.

2. Convert install persistence, selection, baseline and native-agent composition producers. Serves AC-002 and AC-003. Touch `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/install/FileSystemInstallSelectionPersistence.kt`, `FileSystemInstallSelectionValidation.kt`, `FileSystemBaselineManifestWire.kt`, and the existing `nativeagent/composition/NativeAgentBundle.kt` and `NativeAgentCompositionSchemaValidator.kt` owners. Change `toMalformedInstallSelection(Path)` and `toUnreadableBaseline(Path)` to return `SkillBillRuntimeException`. Reuse an existing failure only when its code equals the respective malformed-selection or unreadable-baseline entry; otherwise construct the original message with its original cause. Replace former typed catches and checks with exact code guards, retaining unrelated failure propagation and the existing handled sets. Preserve reconciliation behavior and persistence operations. Convert existing assertions in `FileSystemInstallSelectionPersistenceTest.kt`, `InstallPlanSchemaViolationsTest.kt`, `InstallReconcileTest.kt`, `InstallReconcileApplyTest.kt` and native-agent schema tests. Keep their message, cause, persisted-state and payload assertions intact. These existing boundaries cover the realistic regressions of misclassifying malformed records, losing causes or changing reconciliation outcomes.

3. Convert configuration and remaining schema producers and consumers. Serves AC-002 and AC-003. Touch `runtime-infra/host/src/main/kotlin/skillbill/infrastructure/host/FileSystemRepoLocalConfig.kt`, `runtime-application/src/main/kotlin/skillbill/application/config/ConfigResolutionService.kt`, existing infrastructure-contracts install, goal-observability, goal-progress and IDE-status validators, MCP telemetry validation, and domain goal-review-state and observability decoders. Replace constructed former classes and callback return types with coded failures without changing validation rules, wire keys, schema versions or ownership. Keep the review-state catches in `runtime-domain/src/main/kotlin/skillbill/workflow/model/goalreview/GoalSubtaskReviewState.kt` and `workflow/taskruntime/model/persistence/GoalSubtaskReviewArtifactDecoder.kt` narrow through the exact review-state code. In `decodeRepairReceipts`, switch only the outer review-state construction; preserve its indexed path and cause. Subtask 6 owns removal of the inner receipt typed-property reads. Convert existing `FileSystemRepoLocalConfigTest.kt`, infrastructure-contracts validator tests, domain goal-review-state tests and MCP telemetry schema tests to exact code assertions while preserving observable outputs. The digest names some producer families without individual filenames. Implement must locate their current owners and confirm factory inputs there, without introducing a new owner or parallel validator.

4. Preserve discrimination and rendered labels across the affected boundaries. Serves AC-002 and AC-003 and the common message and ownership criteria. Apply `rethrowUnless` to converted catches, merging adjacent catches only when they belong to these sixteen failures and preserving branch-specific behavior. Caught failures expose only `code`, `message` and `cause`; never parse messages to recover former fields. Keep `failureCodeLabel() ?: existingExpression` rendering and update only pinned labels for converted Install failures, including install-apply `causeClass` expectations and `runtime-core/src/test/kotlin/skillbill/di/install/InstallSelectionRuntimeBoundaryTest.kt`. The digest corrects two historical Scope anchors: `InstallApply.kt`'s identity-mismatch rethrow belongs to ReviewContext in subtask 2, and the paired `IdeStatusService.kt` catches handle Workflow failures in subtask 7. Leave both changes to their owners. Do not introduce an Install code for identity mismatch or merge the IDE catches here. Preserve database, gate, cancellation and interruption propagation. Existing boundary assertions are the test obligations for this task; no additional behavior test is prescribed.

5. Remove obsolete baseline rows and inspect the transition condition. Serves AC-001 through AC-003 and the common baseline criteria. Remove only whole rows for the sixteen deleted classes from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Keep preparation schema and conflict declarations if present. After implementation, inspect main code for remaining subclasses of either transitional base and calls to the codeless constructor. The digest establishes external legacy uses, so assume the transition remains open unless implementation finds that intervening work removed every use. Retain the open base, legacy code, codeless constructor and legacy classifier term while any use remains. Only if the complete transition condition holds, delete those transition pieces, make the coded exception final and retarget remaining legacy references under their original guards. Audit checks declarations, code coverage, typed-property removal and handled sets. Later validation runs `FailureCodeTotalityArchitectureTest`, retaining its existing synthetic fixtures and removing no unrelated baseline row.

### Constraints and phase ownership

Implement produces the repository end states and converts existing tests; it does not need completed build or validation evidence to begin. Audit inspects each criterion. Build proof belongs only to the build phase. All test execution and the full repository gate belong to validate, including unit tests, detekt, formatting and runtime-core repository architecture checks. Validation uses the digest's collect-all command `./gradlew check --continue --parallel -q --warning-mode none` under JDK 21 and covers failure totality, ports declarations, typed parse boundaries, package cycles, wire vocabulary and comment guards. Spotless runs in a plain clone. Preserve existing tests and their source sets. New test obligations are empty because the digest prescribes assertion conversion only and identifies existing boundary coverage.

Apply A1 through A12 and G1 through G7 during later implementation and review, especially A7 handled sets, A6 wire ownership, A9 package cycles and A10 test placement. Keep validators outside domain and artifact decoding in domain. Add no module, dependency, alias for a deleted class, runtime-exception property, raw-map public result, locator accessor, new `runCatching`, suppression, authored line comment or non-interface KDoc. Keep the SKILL-380 attempt boundary phase-generic and preserve accepted-step authority. Respect the existing detekt limits and do not grow `ArchitectureScanSupport.kt` or weaken guards, baselines, exemptions or governed parity tests.

This conversion changes coded labels only. Persisted schemas, payloads, recovery, quarantine, diagnostics and measurement records retain their current behavior. No feature flag, dependency or generated artifact change is needed. History, commits and PR work remain with their owning phases. Planning executes no build, test, full check or workflow command. The only planning edit is this section; the existing title, scope, criteria, dependencies, validation strategy and next path remain unchanged.
