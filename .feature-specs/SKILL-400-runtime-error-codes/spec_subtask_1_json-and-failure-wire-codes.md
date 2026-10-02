# SKILL-400 Subtask 1 - json-and-failure-wire-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](./spec.md)
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
