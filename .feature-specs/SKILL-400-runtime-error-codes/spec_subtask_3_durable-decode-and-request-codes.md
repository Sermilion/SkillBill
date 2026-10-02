# SKILL-400 Subtask 3 - durable-decode-and-request-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](./spec.md)
Issue key: SKILL-400

## Scope

Convert `DurableExternalDecodeErrors.kt` (6 classes), `InvalidFeatureSpecPreparationRequestError`, `UnresolvedEnvironmentContextFieldError` (runtime-contracts `skillbill.error.core`) and `InvalidLearningSourceError` (`skillbill.error.learning`).

- **`InvalidGovernedReviewEvidenceRequestError`** (extends `ShellContentContractException`; thrown and read only in runtime-infra/launcher `GovernedReviewEvidence*`). It becomes an entry of the existing `GovernedReviewFailureCode`, which is already in `isShellContentContractFailure()`. Reuse an entry with the same meaning if SKILL-398 subtask 4 created one.
- **`InvalidNativeAgentLinkInventory{Decode,Write,Reconcile}Error` and `InvalidInstallStagingError`** (extend `ShellContentContractException`; thrown in runtime-infra/skills). They become `DurableInstallStateFailureCode { NATIVE_AGENT_LINK_INVENTORY_DECODE, NATIVE_AGENT_LINK_INVENTORY_WRITE, NATIVE_AGENT_LINK_INVENTORY_RECONCILE, INSTALL_STAGING }` in `skillbill.error.core`, which joins `isShellContentContractFailure()`. The guarded catches in `NativeAgentLinkInventoryDecode.kt:33`, `NativeAgentLinkInventoryWrite.kt:56`, `NativeAgentLinkInventoryReconcile.kt:54` and `InstallStaging.kt:150` keep their handled set.
- **`InvalidAgentAddonAgentIdError`** (extends `ShellContentContractException`; thrown by domain `SupportedAgent`, caught at `ScaffoldServicePlanningPayloadMerge.kt:128`). It becomes an entry of the existing `AgentAddonFailureCode`, and that catch checks the entry.
- **`InvalidFeatureSpecPreparationRequestError`** (extends `SkillBillRuntimeException`; domain and engine throw it). It becomes `FeatureSpecPreparationFailureCode { INVALID_REQUEST }` in `skillbill.error.core`, with factory `invalidFeatureSpecPreparationRequest(fieldPath, reason, cause)` and today's text. MCP captured it before and still does.
- **`UnresolvedEnvironmentContextFieldError`** (thrown only in `sqlite/core/schema/DatabasePaths.kt`). This is a composition defect, so it becomes `error(<same text>)`. If a test pins its CLI output, it becomes an infra-sqlite code instead.
- **`InvalidLearningSourceError`.** `InvalidLearningSourceReason` (`skillbill.error.learning`) implements `RuntimeFailureCode` and is the code. Add factory `invalidLearningSource(reason, reviewRunId, findingId)` with today's `when` text, and delete the class.
  - In `McpToolDispatcher.kt`, the `error is InvalidLearningSourceError` term becomes `code is InvalidLearningSourceReason` inside `uncapturedAtMcp()`. MCP output and capture are unchanged.

## Acceptance Criteria

1. No main source declares the nine classes. `DurableExternalDecodeErrors.kt`, `InvalidFeatureSpecPreparationRequestError.kt`, `UnresolvedEnvironmentContextFieldError.kt` and `InvalidLearningSourceError.kt` are deleted, or renamed to the enum they hold.
2. Each former failure throws `SkillBillRuntimeException` with an entry of `GovernedReviewFailureCode`, `DurableInstallStateFailureCode`, `AgentAddonFailureCode`, `FeatureSpecPreparationFailureCode` or `InvalidLearningSourceReason`, or fails through `error()` (`UnresolvedEnvironmentContextFieldError` only).
3. MCP learning-source tools return the same error result and still skip telemetry capture for an invalid learning source.

## Non-Goals

Other `skillbill.error.core` classes; the SKILL-399 Install and SkillStaging areas.

## Test obligations

- **MCP capture parity.** In the `McpCaptureDiagnosticsTest` style, an `invalidLearningSource(...)` failure is not captured. Bug it catches: the learning-source code drifts out of the no-capture set and writes telemetry rows.
## Shared Rules

Apply `spec.md` "Conversion rules", "Shared pieces", "Transition finish" and "Execution Rule". If a shared piece is missing, add it as written there. After this subtask's edits, check the transition-finish condition and finish the transition if it holds.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- MCP telemetry capture happens for exactly the failures it happened for before, and no unguarded `SkillBillRuntimeException` catch absorbs a failure it did not catch before.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-400

## Spec Path

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_3_durable-decode-and-request-codes.md
