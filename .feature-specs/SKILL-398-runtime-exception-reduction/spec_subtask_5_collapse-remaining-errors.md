# SKILL-398 Subtask 5 - collapse-remaining-errors

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
Issue key: SKILL-398

## Scope

(F-005) Replace every other custom `Throwable` declared in production main with `SkillBillRuntimeException` and an owner code, or with `require`/`check`/`error()` for code defects, following the parent spec's "Target failure model" and the same rules as subtask 4 (code enum per owner area, existing `FailureWireCode` reused, message functions, no reading of typed exception properties, catch sites check `code`, tests assert `code`).

Anchors at the census tree (76 declarations):

- `runtime-contracts/.../error/core/`: `ExternalAddonErrors.kt`, `ExternalPlatformPackErrors.kt`, `DurableExternalDecodeErrors.kt`, `MalformedJsonTextError.kt` (incl. `JsonWrongRootTypeError`, `UnsupportedJsonValueError`), `DatabaseAccessErrors.kt` (`DatabaseAccessError`, `DatabaseBusyError`), `TelemetryHttpErrors.kt`, `InvalidFeatureSpecPreparationRequestError.kt`, `UnresolvedEnvironmentContextFieldError.kt`, `FailureWireCodeContract.kt` (`UnrecognizedFailureWireCodeError`).
- `runtime-contracts/.../error/featuretask/`: `PhaseSlotContractErrors.kt` (17 classes), `FeatureTaskRuntimeExecutionPlanAdmissionError.kt` (base plus 4; `reasonCode` becomes the code), `FeatureTaskRuntimeExecutionPlanConflictError.kt`, `FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError.kt`, `InvalidFeatureTaskRuntimeExecutionPlanSchemaError.kt`, `InvalidPhaseStrategyCompositionError.kt` (extends `IllegalArgumentException`), `UnsafeFeatureTaskRuntimeRegenerationError.kt` (extends `IllegalStateException`; its `FeatureTaskRuntimeRegenerationRefusal` enum becomes the code).
- `runtime-contracts/.../error/goalrunner/`, `.../error/learning/` (`InvalidLearningSourceError`; update the `McpToolDispatcher.kt` arm that names it to the code, output unchanged).
- runtime-engine: `RuntimeOwnedFactUnavailable` (`featuretask/persist/RuntimeOwnedPersistenceBoundary.kt`).
- runtime-application: `RuntimeOwnedFactUnavailable` (`runtimepersistence/RuntimeOwnedPersistenceBoundary.kt`) — one code serves both copies; keep the `initCause` behaviour recorded in `runtime-application/agent/history.md`.
- runtime-domain: `ReviewAttributionResolutionError` and `MalformedVocabulary` (`review/model/ReviewAttributionModels.kt`), `SkillBillRollbackException` (`skillremove/`).
- runtime-infra: `GateJvmResolutionErrors.kt` (6, host/jvm), `CursorReviewStreamErrors.kt` (base plus 5, launcher/review; delete the never-constructed `CursorReviewStreamEmptyError`; the base extends `Exception` and becomes a code), `InstallSymlinkException` (skills/install/apply), `ReleaseLicensePolicyError` (skills/scaffold/runtime/validation), `InvalidGoalTelemetryRowError` (sqlite/review/core), `ValidationGateProcessException` (workflow/validation).
- runtime-mcp: `InvalidMcpToolArgumentError` (`skillbill/mcp/shared/`): an MCP-owned code enum in runtime-mcp, per SKILL-391.
- Any other custom `Throwable` in production main not named in subtasks 2, 3 or 4, including ones added by concurrent bundles (for example SKILL-396's typed infra errors) if present.

Specific rules:

- `PhaseSlotContractErrors.kt`, `InvalidPhaseStrategyCompositionError` and other registry or composition wiring failures are defects when the condition can only arise from how the runtime is composed; they become `require`/`check`/`error()`. Selection failures that reach users (`UnknownPhaseReviewTargetError`, `UnknownQualityGateSelectionError`, `PhaseIntakeRequiredError`, `PullRequestBranchRefusedError`) stay tier 3 codes, or become returned values where the CLI already maps them to a usage error (`PhaseCommand.kt:93`, `FeatureTaskRuntimeRunRequestAssembly.kt:92` reads `allowedValues`).
- `DatabaseBusyError` and `DatabaseAccessError` keep their retry and CLI classification behaviour (`InlineReviewPreparation.kt:75` cause-chain check, `GoalCliStatusCommands.kt:94` reads `condition`): the readers check the code; `condition` becomes part of the code (one entry per condition) or of the message.
- `TelemetryProxyRequestFailureError.statusCode` (read at `HttpTelemetryClient.kt:69`): the HTTP client returns the status as a value where it branches on it.
- Execution-plan admission and regeneration refusal end the run: keep throwing, as codes; `FeatureTaskRuntimeExecutionAdmission.kt` and `FeatureTaskContinuationLookupService.kt` read `code` instead of `reasonCode` / `refusal`.

When no class in main extends `SkillBillRuntimeException` or `ShellContentContractException` afterwards, finish the transition as the parent spec describes. If the custom-throwable baseline exists, remove the rows of every deleted class; after this subtask and subtasks 2-4 have landed, it lists only `SkillBillRuntimeException` unless a decisions.md entry records a reason for another class.

## Acceptance Criteria

1. No production main source declares a custom `Throwable` other than `SkillBillRuntimeException`, the classes owned by subtasks 2, 3 and 4 that are still present, and classes whose retention reason is recorded in `runtime-kotlin/agent/decisions.md`.
2. Each former failure throws `SkillBillRuntimeException` with a code from an enum implementing `RuntimeFailureCode`, declared by the module and package that owned the class, or fails through `require`/`check`/`error()` where only a code defect can trigger it. runtime-contracts declares no MCP-, infra- or engine-only code.
3. `RuntimeOwnedFactUnavailable` is gone from both runtime-application and runtime-engine, and both boundaries use one code.
4. Database busy retries, the goal status CLI's database-access payload, telemetry HTTP status handling, execution-plan admission warnings and MCP learning-source handling behave as before; existing tests pass with type-to-code assertion edits only.
5. Every user-visible message is byte-identical; no expected-output, wire-fixture or payload assertion is edited other than replacing an exception-type assertion with a code assertion. No typealias is named after a deleted class.
6. If the custom-throwable baseline exists, it lists none of the deleted classes and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- Classes owned by subtasks 2, 3 and 4.
- The CLI and MCP top-level arms (F-008), beyond renaming the `InvalidLearningSourceError` arm to its code.
- Test-source throwables.

## Dependency Notes

Depends on: none.
If subtask 1 has not landed, add the target-type pieces as the parent spec defines them. Applies to classes wherever SKILL-391 placed them. If subtask 4 finished first, this subtask may be the one that finishes the transition. Coordinates with SKILL-390, SKILL-396 and SKILL-397; the second lander keeps both edits and converts any custom throwable they added.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: one test for the database-busy retry and one for the goal status database-access payload, each driven by the code, if no existing test covers them after the conversion.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_5_collapse-remaining-errors.md
