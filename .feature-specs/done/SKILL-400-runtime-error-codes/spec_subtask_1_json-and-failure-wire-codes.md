# SKILL-400 Subtask 1 - json-and-failure-wire-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](spec.md)
Issue key: SKILL-400

## Scope

Convert `MalformedJsonTextError.kt` (`MalformedJsonTextError`, `JsonWrongRootTypeError`, `UnsupportedJsonValueError`) and `UnrecognizedFailureWireCodeError` (`FailureWireCodeContract.kt`) in runtime-contracts `skillbill.error.core`. All four extend `ShellContentContractException`.

- **`JsonFailureCode { MALFORMED_TEXT, WRONG_ROOT_TYPE, UNSUPPORTED_VALUE }`** in `skillbill.error.core`, with factories that take the old constructors' parameters (`malformedJsonText(cause)`, `jsonWrongRootType(expectedRoot)`, `unsupportedJsonValue(...)`). Rename the file to `JsonFailureCode.kt`.
  - `JsonCodec` throw sites use the factories.
  - Catch sites become code checks:
    - `JsonCodec.kt:35` (contracts);
    - `WorkflowRecordMapping.kt:116` (ports);
    - `ReviewRunLaneSegmentAccountingJson.kt:32-34` (domain): the two catches merge into one with a `when (e.code)`;
    - `GoalRunnerWorkerSubtaskRequestParser.kt:204` (domain);
    - `AcceptanceAuditRemainingCriteria.kt:67` (engine);
    - `WorkflowServiceFeatureTaskAbandon.kt:91` (application);
    - `WorkflowCliCommands.kt:238` (cli);
    - `FeatureTaskRuntimeExecutionPlanSchemaValidator.kt:75` (infra-contracts, `UNSUPPORTED_VALUE`).
  - Where a handler maps the failure to null or empty, a code-checked catch is enough; do not widen into a returning-decode refactor.
- **`FailureWireDecodeCode { UNRECOGNIZED }`** in `FailureWireCodeContract.kt`, thrown by `failureWireByValue` with the same text. It implements `RuntimeFailureCode` and not `FailureWireCode`.
- Add `JsonFailureCode` and `FailureWireDecodeCode` to `isShellContentContractFailure()`.

## Acceptance Criteria

1. No main source declares the four classes. `skillbill.error.core` holds `JsonFailureCode` and `FailureWireDecodeCode` with their factories.
2. Each former failure throws `SkillBillRuntimeException` with an entry of one of the two enums.
3. Every listed catch site handles exactly the failures it handled before, and the guarded shell-content edge sites still handle these failures.

## Non-Goals

Every other class in `skillbill.error.core`; JSON codec behaviour beyond the failure type.

## Test obligations

None beyond the converted assertions.

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

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_1_json-and-failure-wire-codes.md

## Implementation Details

This plan uses only the upstream preplan digest for repository knowledge. Its discovery anchor is `6e512ed9ca1d0bf43619880b9f7560a58704d5f2` on `base/SKILL-380-phase-slot-strategies`. This subtask has no dependencies. Implement confirms the affected anchors against its current tree and preserves changes already present in shared files. No additional decomposition is needed.

### Ordered tasks

1. Replace the four declarations with owner codes and factories. Serves AC-001 and AC-002.

   In `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/core`, rename `MalformedJsonTextError.kt` to `JsonFailureCode.kt`. Declare `JsonFailureCode` implementing `RuntimeFailureCode` with `MALFORMED_TEXT`, `WRONG_ROOT_TYPE`, and `UNSUPPORTED_VALUE`. Its factories return `SkillBillRuntimeException`: `malformedJsonText(cause: Throwable)`, `jsonWrongRootType(expectedRoot: String)`, and `unsupportedJsonValue(message: String)`. Preserve respectively `"JSON text is malformed: ${cause.message.orEmpty()}"`, `"JSON root must be $expectedRoot"`, and the supplied message. Preserve the original cause for malformed text.

   In `FailureWireCodeContract.kt`, replace `UnrecognizedFailureWireCodeError` with `FailureWireDecodeCode.UNRECOGNIZED`. The enum implements `RuntimeFailureCode`, never `FailureWireCode`. Add a factory taking `hierarchy: String` and `rejectedToken: String`, returning the shared exception with `"Unrecognized failure wire code '$rejectedToken' for hierarchy '$hierarchy'."`. The digest does not name this factory. Use `unrecognizedFailureWireCode(hierarchy, rejectedToken)` as the planning assumption for implement to confirm against local naming conventions. Keep the existing generic signature and constraints of `EnumEntries<E>.failureWireByValue(value, hierarchy)` unchanged and route its failure through this factory.

   Reuse `RuntimeExceptionBases.kt`, `RuntimeFailureCode`, `rethrowUnless`, and `failureCodeLabel`; the digest confirms they already exist. Add no replacement throwable, typealias, exception property, or duplicate shared helper.

2. Convert JSON construction and its internal recovery boundary. Serves AC-002 and AC-003.

   Update `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/JsonCodec.kt` to use the three JSON factories. Preserve the public behavior of `parseObjectOrNull`, `parseJsonArrayStrict`, `anyToStringAnyMap`, `valueToJsonElement`, and `parseValue`. Keep finite-number, unsupported-type, and string-map-key messages and serialization unchanged. Replace its malformed-text catch with a `SkillBillRuntimeException` catch guarded by `JsonFailureCode.MALFORMED_TEXT`; rethrow the same exception for every other code. Retain existing null or empty results without introducing a returning-decode refactor.

3. Convert the listed consumer catches without broadening their handled sets. Serves AC-003 and the common message and propagation criteria.

   Paths below are relative to each named module's `src/main/kotlin/`:

   - runtime-ports `skillbill/ports/workflow/model/WorkflowRecordMapping.kt`: replace the former typed handler with its exact JSON-code guard and preserve its contextual workflow-state failure and cause. Update only the existing handling; add no new ports behavior or declarations carrying implementation.
   - runtime-domain `skillbill/review/context/model/packet/ReviewRunLaneSegmentAccountingJson.kt`: merge malformed-text and wrong-root catches into one shared catch discriminating `MALFORMED_TEXT` and `WRONG_ROOT_TYPE`. Preserve the schema-error message and cause. Rethrow all other codes.
   - runtime-domain `skillbill/goalrunner/GoalRunnerWorkerSubtaskRequestParser.kt`: guard the converted handler with the former failure's exact code and retain the recorded `malformed_json` substitution and null result.
   - runtime-engine `skillbill/engine/featuretask/slot/audit/AcceptanceAuditRemainingCriteria.kt`: retain `Unusable("Malformed JSON remaining-criterion list.")` only for the formerly handled malformed-text failure.
   - runtime-application `skillbill/application/workflow/service/WorkflowServiceFeatureTaskAbandon.kt`: preserve contextual workflow-state wrapping and cause under the former handler's exact code.
   - runtime-cli `skillbill/cli/workflow/WorkflowCliCommands.kt`: preserve the existing `UsageError` outcome and message under the exact code guard.
   - runtime-infra/contracts `skillbill/infrastructure/contracts/workflow/featuretask/FeatureTaskRuntimeExecutionPlanSchemaValidator.kt`: handle only `JsonFailureCode.UNSUPPORTED_VALUE` and rethrow other codes.

   The digest explicitly identifies the paired segment-accounting codes and the validator code. For the remaining typed catches, plan on `MALFORMED_TEXT`, consistent with the digest's malformed-JSON outcomes; implement confirms the former type at each anchor before choosing the exact guard. This is a local confirmation of the planned conversion, not a reason to widen handling. Keep catch precedence, original failures on rethrow, cancellation, interruption, contextual causes, suppression, and existing diagnostics intact. Do not add `runCatching`.

4. Preserve shell-content classification and shrink the throwable baseline. Serves AC-001 and AC-003 and the common capture-parity and baseline criteria.

   In runtime-contracts `src/main/kotlin/skillbill/error/shellcontent/ShellContentContractFailures.kt`, register both `JsonFailureCode` and `FailureWireDecodeCode` in `Throwable.isShellContentContractFailure()`. Preserve all existing families, the deliberate Scaffold exclusion, and the legacy term while the transition remains open. Existing guarded edge handlers must continue to recognize these four failures without absorbing unrelated runtime codes. Their MCP no-capture route follows this predicate, so no new MCP-only registration or top-level error-arm change is needed.

   Remove only the four deleted whole rows for `MalformedJsonTextError`, `JsonWrongRootTypeError`, `UnsupportedJsonValueError`, and `UnrecognizedFailureWireCodeError` from `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` by hand. Preserve all unrelated rows, the nonempty real-tree scan, and the synthetic rejection fixture of `FailureCodeTotalityArchitectureTest`. Leave `ArchitectureScanSupport.kt` unchanged.

   The digest establishes that other SKILL-400 throwable classes and transition callers remain. This slice therefore leaves `ShellContentContractException`, `LegacyFailureCode`, and the codeless constructor in place. Implement checks the transition-finish condition in all source sets after its edits as required by Shared Rules. Retire the transition only if that condition actually holds because intervening work removed every remaining caller; otherwise name that it remains open. Never weaken coded edge guards to make retirement possible.

5. Convert existing assertions and prepare validation handoff. Serves AC-001 through AC-003 and the common preservation criteria.

   Update runtime-contracts `src/test/kotlin/skillbill/contracts/JsonCodecTest.kt` and runtime-domain `src/test/kotlin/skillbill/workflow/decomposition/model/FailureWireCodeConformanceTest.kt`. Replace former-type assertions with `assertFailsWith<SkillBillRuntimeException>` plus the exact enum entry assertion, and replace any constructed former failures with their factories. Preserve message, expected-output, wire-fixture, payload, and exit-code assertions. Change a pinned rendered class label only to its corresponding code label where required.

   No new behavioral test is planned, and `test_obligations` is empty. The existing assertions already cover the JSON and wire-decode branches. Their conversion catches the realistic regression where a factory emits the wrong code while preserving its message. Keep governed parity and validator-backed coverage intact rather than replacing it with tests of implementation structure.

   Buildability proof belongs exclusively to the build phase. Validate runs the affected unit tests and the full required gate, including detekt, formatting, and runtime-core repoTest architecture checks such as `FailureCodeTotalityArchitectureTest`. Spotless runs in a plain clone. Audit inspects each criterion and the preserved handled sets; review and validate may repair production wiring, tests, formatting, or lint within their authorized scope. Plan and implement execute no build, test, or validation command. History, commit, push, PR, and installation remain with their owning phases or parent runtime.

### Constraints and handoff

Preserve all message bytes, payload values, failure-code rendering for uncoded failures, and persisted wire formats. No contract version or schema change is required. Keep contracts code ownership and dependency direction, especially no import from `skillbill.error.core` to `skillbill.error.shellcontent`. Apply the digest's architecture rules A1, A2, A4, A7, A9, A10, and G7. Add no module, dependency, family metadata, suppression, relaxed mock, or helper module. Preserve existing real-bug regression coverage. Keep authored Kotlin free of line and non-KDoc block comments, with KDoc only on interfaces and their members. Respect detekt's throw, return, length, complexity, and declaration-name limits.

The plan resolves the digest's remaining naming and per-handler detail through the explicit assumptions above. There is no blocking input or phase-authority conflict. Only this sub-spec changes during plan. Implementation and assertion edits remain for implement; command evidence remains for build and validate. No installer or install-sync action is part of this child plan.
