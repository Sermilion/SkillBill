# SKILL-391 Subtask 1 - runtime-contracts kernel ownership cleanup

Parent spec: [.feature-specs/SKILL-391-runtime-contracts-kernel-ownership-cleanup/spec.md](./spec.md)
Issue key: SKILL-391

## Scope

Seven changes (evidence and feasibility for each are in `investigation.md`):
- F-001: move InvalidMcpToolArgumentError from runtime-contracts error.core to runtime-mcp skillbill.mcp.shared as internal.
- F-003(a): runtime-mcp McpLearningsSkippedContract references NO_APPLIED_LEARNINGS.
- F-003(b): InvalidFeatureTaskRuntimePhaseOutputSchemaError.failureCode defaults to FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID.wireValue.
- F-004: delete the Array<E>.failureWireByValue overload.
- F-005: make six declarations that are read only inside their own file private.
- F-006: repackage the six runtime-infra/contracts repoTests out of skillbill.contracts.* into the existing skillbill.infrastructure.contracts.review and skillbill.infrastructure.contracts.workflow.featuretask packages.
- F-007: keep OperationUsageError in runtime-contracts skillbill.error.operation. Move the other 28 classes in OperationErrors.kt into runtime-engine main as one file in skillbill.engine.operation.core, declared internal. Engine main and test files change only their imports.

Files touched:
- runtime-contracts main: error/core/InvalidMcpToolArgumentError.kt (deleted), error/core/FailureWireCodeContract.kt, error/shellcontent/FeatureTaskRuntimeShellContentErrors.kt, error/shellcontent/ReviewContextShellContentErrors.kt, contracts/learning/LearningContracts.kt, contracts/validation/ValidationReportContracts.kt;
- runtime-mcp main: mcp/shared (new error file), mcp/shared/McpToolArguments.kt, mcp/core/McpToolDispatcher.kt, mcp/review/McpAdapterContracts.kt;
- runtime-infra/contracts repoTest: the six test files;
- runtime-contracts main: error/operation/OperationErrors.kt (keeps only OperationUsageError);
- runtime-engine main: engine/operation/core (one new error file), plus import edits in engine/operation/{core,featureguard,featureguardcleanup,prreviewfix,release,unittestvalue,verify};
- runtime-engine test: import edits in the six engine/operation tests that reference operation errors.

## Acceptance Criteria

1. No file under runtime-kotlin/runtime-contracts/src declares InvalidMcpToolArgumentError. runtime-kotlin/runtime-mcp/src/main declares it once, in package skillbill.mcp.shared, as internal, extending skillbill.error.core.ShellContentContractException with constructor parameters toolName, argumentKey, detail and cause, and the unchanged message template MCP tool '<toolName or <unknown>>' argument '<argumentKey>': <detail>. runtime-contracts/src/main/kotlin/skillbill/error/core holds 11 files.
2. In runtime-mcp mcp/review/McpAdapterContracts.kt, the LearningPayloadKeys.APPLIED_LEARNINGS entry of McpLearningsSkippedContract references skillbill.contracts.learning.NO_APPLIED_LEARNINGS instead of a string literal. NO_APPLIED_LEARNINGS remains public with value none.
3. In FeatureTaskRuntimeShellContentErrors.kt, InvalidFeatureTaskRuntimePhaseOutputSchemaError declares its failureCode default as FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID.wireValue. InvalidFeatureTaskRuntimeBuildReceiptSchemaError is unchanged.
4. FailureWireCodeContract.kt declares no Array<E>.failureWireByValue overload. The EnumEntries<E>.failureWireByValue overload, FailureWireCode and UnrecognizedFailureWireCodeError are unchanged.
5. These are declared private: LearningSummaryWire, ValidationReportPayloadKeys, REVIEW_HUNK_EVIDENCE_LOCATOR_MISSING, REVIEW_HUNK_EVIDENCE_LOCATOR_UNREADABLE, REVIEW_LEARNING_RULE_TEXT_TOO_LONG and REVIEW_LEARNING_TITLE_TOO_LONG. UpdateCheckPayloadKeys and WorkflowContinueSessionSummaryPayloadKeys stay public, because SKILL-395 reflects over them from runtime-core. Every constant value and key string is unchanged. REVIEW_HUNK_EVIDENCE_INTEGRITY stays public.
6. No Kotlin file outside runtime-kotlin/runtime-contracts declares package skillbill.contracts or any subpackage of it. The six former orphan tests live under runtime-kotlin/runtime-infra/contracts/src/repoTest/kotlin/skillbill/infrastructure/contracts/review/ (ReviewWirePayloadKeysYamlParityTest, ReviewContextSchemaContractVersionTest) and .../infrastructure/contracts/workflow/featuretask/ (FeatureTaskRuntimeCheckpointIdentitySchemaContractVersionTest, FeatureTaskRuntimeExecutionPlanSchemaContractVersionTest, FeatureTaskRuntimeVerifyFindingsDispositionSchemaRepoTest, FeatureTaskRuntimeProjectionCanonicalizationSchemaRepoTest). Each declares the matching package, and its class name and test method names are unchanged.
7. No build.gradle.kts, architecture-test class or architecture baseline file differs from the base branch. The four runtime-contracts baselines under runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/ remain empty.
8. runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/operation declares exactly one class, OperationUsageError, unchanged. Each of the other 28 classes formerly in OperationErrors.kt (OperationRefusalError, DuplicateOperationIdError, UnknownOperationIdError, OperationConfirmationUnsupportedError, UnknownOperationTokenError, ConsumedOperationTokenError, SupersededOperationTokenError, ForeignOperationTokenError, MovedOperationAnchorsError, OperationAnchorUnreadableError, MissingReleaseBumpError, MissingOperationIntakeError, UnresolvableOperationScopeError, InvalidOperationArgumentError, MissingOperationSelectionError, InvalidOperationSelectionError, PullRequestNotFoundError, PullRequestBranchNotCheckedOutError, ProtectedBranchPushError, PushWorktreeDirtyError, ReleaseWorktreeDirtyError, UnresolvableVerifyTargetError, VerifySpecRehydrateNeededError, VerifyTargetNotCheckedOutError, UnknownVerifyWorkflowError, ForeignVerifyWorkflowError, ClosedVerifyWorkflowError, ReleaseBranchBehindRemoteError) is declared exactly once, internal, under runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/operation/core/. Each keeps its name, constructor parameters, base class and message template. No source outside runtime-kotlin/runtime-engine references any of the 28. runtime-cli OperationCommand.kt still imports OperationUsageError from skillbill.error.operation.

## Non-Goals

- Moving McpToolPayloadKeys or switching sqlite ReviewRowMappers off it: SKILL-395 owns both.
- Moving any of the other 93 single-owner errors out of the kernel, or moving OperationUsageError.
- Moving DecompositionManifestValidationFailureCode out of runtime-domain, or typing any failureCode field as an enum.
- Replacing the infra/contracts or domain failure-code literals: SKILL-396 and SKILL-397 own them.
- Adding or extending an architecture scanner, or adding test obligations.
- Changing JsonCodec, JsonPayloadContract, DecompositionPlanningContracts, LegacyProseWorkflowError text, or any wire value.
- Editing ARCHITECTURE.md's EXACT_PACKAGE_SCC sentence: SKILL-392 owns it.

## Dependency Notes

Depends on: none
No dependency on another subtask.

SKILL-395 (runtime-mcp) edits McpToolArguments.kt, McpToolDispatcher.kt and McpAdapterContracts.kt, and adds McpToolPayloadKeys to skillbill.mcp.shared. Whichever lands second applies the other's change to the files present:
- InvalidMcpToolArgumentError is imported from skillbill.mcp.shared;
- the NO_APPLIED_LEARNINGS reference is kept in whatever DTO shape SKILL-395 leaves, including internal DTOs.

If SKILL-396 (infra) moves or edits the six runtime-infra/contracts repoTests first, whichever lands second applies the package rule of criterion 6 to the files present.

SKILL-390 (engine) subtask 3 repackages three engine test files that declare the bare package skillbill.engine.operation. None imports an operation error. If any engine test it moves does import one, whichever lands second points that import at skillbill.engine.operation.core.

No overlap with SKILL-389, SKILL-392, SKILL-393 or SKILL-397 files. This subtask waits for no other issue. SKILL-387 also edits FeatureTaskRuntimeShellContentErrors.kt (it deletes InvalidFeatureTaskRuntimePlanningProjectionSchemaError), a different class from the one F-003(b) changes; whichever lands second keeps both edits.

## Validation Strategy

Validate phase only. Checks:
- `:runtime-contracts:test`;
- `:runtime-mcp:test` and `:runtime-mcp:repoTest`, including the tools-list golden and the review tool tests asserting applied_learnings none;
- `:runtime-infra:contracts:repoTest`;
- `:runtime-engine:test` (the operation registry, release, verify and pr-review-fix tests that assert the relocated errors and their messages);
- `:runtime-cli:test` (operation usage-error rendering);
- `:runtime-application:test`;
- `:runtime-domain:test`;
- `:runtime-core:repoTest` (purity lock, package ownership, package acyclicity, wire vocabulary, failure-code totality, raw-map guards);
- spotless and detekt via the pack gate.

No new tests: the existing suites pin every affected behavior and wire value.

## Next Path

skill-bill goal SKILL-391

## Spec Path

.feature-specs/SKILL-391-runtime-contracts-kernel-ownership-cleanup/spec_subtask_1_runtime-contracts-kernel-ownership-cleanup.md
