# SKILL-400 Subtask 6 - domain-and-mcp-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](./spec.md)
Issue key: SKILL-400

## Scope

Convert the runtime-domain classes `ReviewAttributionResolutionError` and its nested `MalformedVocabulary` (`review/model/ReviewAttributionModels.kt`, extends `IllegalArgumentException`) and `SkillBillRollbackException` (`skillremove/`, extends `SkillBillRuntimeException`). Also convert the runtime-mcp class `InvalidMcpToolArgumentError` (`skillbill.mcp.shared`, extends `ShellContentContractException`).

- **`SkillBillRollbackException`** becomes `SkillRemoveFailureCode.ROLLBACK_INCOMPLETE` in `skillbill.skillremove` (domain). Inline the messages at the two runtime-infra/skills throw sites.
  - `SkillRemove.kt:140-141` becomes one `is SkillBillRuntimeException` branch with `rollbackComplete = error.code != ROLLBACK_INCOMPLETE`. Keep the database rethrow there if SKILL-398 subtask 5 added one.
  - MCP captured it before and still does.
- **`ReviewAttributionResolutionError.MalformedVocabulary`.** If the vocabulary at `ReviewAttributionCanonicalization.kt:161` is code-owned, it becomes `throw IllegalArgumentException(<same text>)`. Otherwise it becomes `ReviewAttributionFailureCode.MALFORMED_VOCABULARY` in `skillbill.review.model`. That code joins `uncapturedAtMcp()`, because the former class was IAE, and any IAE handler it reaches checks the code.
  - Update the type assertions in `ReviewAttributionCanonicalizationTest` and the MCP `ReviewAttributionResolutionParityTest`.
- **`InvalidMcpToolArgumentError`** becomes `McpToolArgumentFailureCode.INVALID` in `skillbill.mcp.shared` (runtime-mcp), with factory `invalidMcpToolArgument(toolName, argumentKey, detail, cause)`. Use it at the dispatcher and `McpToolArguments.kt` throw sites.
  - Its only edge is `McpToolDispatcher`, in the same module. Add `code is McpToolArgumentFailureCode` to `uncapturedAtMcp()` instead of to `isShellContentContractFailure()`, so runtime-contracts declares no MCP code.
  - MCP output and the no-capture behaviour are unchanged.

## Acceptance Criteria

1. No main source declares the four classes.
2. Each former failure throws `SkillBillRuntimeException` with an entry of `SkillRemoveFailureCode`, `ReviewAttributionFailureCode` or `McpToolArgumentFailureCode`, or fails through `IllegalArgumentException` (`MalformedVocabulary` only, per the rule above).
3. `skill remove` reports `rollbackComplete` exactly as before. MCP tools return the same error result for invalid arguments and malformed attribution vocabulary, and capture telemetry for none of them.
4. runtime-contracts declares no MCP code.

## Non-Goals

Other domain or MCP failure handling; the MCP top-level arm beyond `uncapturedAtMcp()`.

## Test obligations

- **MCP capture parity.** In the `McpCaptureDiagnosticsTest` style, an `invalidMcpToolArgument(...)` failure is not captured. Bug it catches: the MCP-owned code is missing from the no-capture set, so argument errors start writing telemetry rows.
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

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_6_domain-and-mcp-codes.md
