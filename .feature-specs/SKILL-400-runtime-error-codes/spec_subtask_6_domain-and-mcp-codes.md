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

## Implementation Details

This plan uses only the upstream preplan digest. Subtask 6 has no dependencies and does not introduce a new decomposition. The digest settles attribution vocabulary as input-driven: `ReviewServiceLaneComposition.kt` combines port-provided pack skill names and platform slugs with canonical defaults before `ImportedReview.withCanonicalAttribution(...)`. Use a coded failure, not the conditional defect alternative in Scope. Keep the two domain code families in runtime-domain and the MCP-only family in runtime-mcp, as this subtask explicitly requires.

Paths below are relative to `runtime-kotlin/`. Production paths use the named module's `src/main/kotlin/`; unit-test paths use its `src/test/kotlin/`.

1. Convert review-attribution failure construction. Serves AC-001 and AC-002, plus message and typed-property parity. In runtime-domain `skillbill/review/model/ReviewAttributionModels.kt`, delete `ReviewAttributionResolutionError` and nested `MalformedVocabulary`. Add `ReviewAttributionFailureCode.MALFORMED_VOCABULARY` implementing the existing `RuntimeFailureCode` in `skillbill.review.model`. Put its factory beside the enum and preserve the full message generated from `rawValue`, `vocabulary`, and `offendingEntry`. Update private vocabulary validation in `skillbill/review/attribution/ReviewAttributionCanonicalization.kt`, preserving `resolveCanonicalRoutedSkill(...)` and `resolveCanonicalStack(...)` results. Do not replace `offendingEntry` with a new exception property. The digest does not supply the factory's name or exact message literal; use `malformedReviewAttributionVocabulary(rawValue, vocabulary, offendingEntry)` and have implement copy the existing message byte-for-byte. Retarget reachable former IAE handlers to exact code discrimination, rethrowing unrelated shared failures. Convert `ReviewAttributionCanonicalizationTest` and runtime-mcp `ReviewAttributionResolutionParityTest` to shared-exception and exact-code assertions; retain offending text through the unchanged message or local test input. These existing tests protect malformed external vocabulary and canonicalization parity; no duplicate test obligation is added.

2. Convert skill-removal rollback failures and their result mapping. Serves AC-001, AC-002 and AC-003. Replace runtime-domain `skillbill/skillremove/SkillBillRollbackException.kt` with `SkillRemoveFailureCode.kt`, declaring `ROLLBACK_INCOMPLETE` on a `RuntimeFailureCode` enum. Inline coded exceptions with unchanged messages and causes at the two runtime-infra/skills owners, `skillbill/infrastructure/skills/skillremove/SkillRemoveJvmFileSystemApply.kt` and `SkillRemoveJvmFileSystemApplyArtifactDelete.kt`. In runtime-application `skillbill/application/scaffold/SkillRemove.kt`, merge rollback-specific and general runtime-exception handling into one shared-exception branch. Set `rollbackComplete` false only for `SkillRemoveFailureCode.ROLLBACK_INCOMPLETE`; preserve all other result fields, existing database rethrows, cancellation, interruption and cooperative propagation. Convert `SkillRemoveTest.kt` in the same application package to construct and assert coded failures while keeping result assertions. The realistic regression is reporting complete rollback after restoration failed, or reporting incomplete rollback for an unrelated runtime failure. Preserve existing coverage for these outcomes; do not add overlapping tests. Keep this code outside MCP's no-capture set, because rollback failures remain captured.

3. Convert MCP argument failures within the adapter. Serves AC-001, AC-002, AC-003 and AC-004. Replace runtime-mcp `skillbill/mcp/shared/InvalidMcpToolArgumentError.kt` with `McpToolArgumentFailureCode.kt`, containing `McpToolArgumentFailureCode.INVALID` and `invalidMcpToolArgument(toolName, argumentKey, detail, cause)`. Preserve the blank-tool fallback, argument text, message and cause. Update construction in `skillbill/mcp/core/McpToolDispatcher.kt` and `skillbill/mcp/shared/McpToolArguments.kt`. Keep this enum and factory in runtime-mcp; add neither a contracts declaration nor a shell-content classification entry. Reuse the existing shared exception model, factories' conventions and propagation helpers rather than introducing parallel abstractions.

4. Preserve MCP capture and error-result boundaries. Serves AC-003 and AC-004. Extend private `Throwable.uncapturedAtMcp()` in runtime-mcp `skillbill/mcp/core/McpToolDispatcher.kt` with the attribution and MCP argument code families. Preserve all earlier enum additions, existing shell-content classification, IAE and ISE handling, cancellation-before-classification ordering, and `mcpToolErrorResult` with `error.message.orEmpty()`. Do not widen the top-level handler or classify skill-removal rollback failures as client errors. In `skillbill/mcp/core/McpCaptureDiagnosticsTest.kt`, add the existing required invalid-argument no-capture case only if converted coverage does not already prove it. Its test obligation serves AC-003 and catches the concrete bug where a newly coded client error writes persisted telemetry. Reuse `McpRuntimeContext`, `enabledTelemetryEnvironment`, `ensureTestDatabase`, the failing `RemoteTransportPort` and persisted telemetry queries. Assert the unchanged tool error result and absence of capture, rather than mock calls. Use the existing attribution parity test to preserve malformed-vocabulary error behavior. Assumption for implement to confirm: its existing fixtures can also establish the malformed-vocabulary no-capture boundary without a new behavioral test. If persisted telemetry assertions are absent, extend the existing malformed-vocabulary parity case with the diagnostics fixture pattern and an absence-of-capture outcome assertion. This preserves the existing branch coverage without adding a duplicate test.

5. Remove owned throwable rows and assess transition eligibility. Serves AC-001 and AC-002 and the common baseline criteria. Remove only the four deleted declarations' whole rows from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` by hand. Preserve unrelated rows and the synthetic rejection fixture in `FailureCodeTotalityArchitectureTest`; leave `ArchitectureScanSupport.kt` unchanged. Implement checks remaining main subclasses and main, test and testFixtures codeless callers as required by the shared transition rule. The digest still identifies subtask 7 classes, so this plan expects to leave the transition open. If intervening work makes this subtask the final eligible conversion, retire `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor in contracts `skillbill/error/core/RuntimeExceptionBases.kt`, make `SkillBillRuntimeException` final, and remove only the legacy-type term in `skillbill/error/shellcontent/ShellContentContractFailures.kt`. Retain coded guards, retarget remaining guarded legacy references, and reconcile the architecture package description and stale documentation as required by the shared rule. If any caller remains, name it in implement's summary and preserve the transition. This conditional work belongs to implement, not this planning session.

6. Hand verification to the owning phases. Serves all four acceptance criteria and common architecture criteria. Audit checks every criterion, including exact message and cause preservation, code ownership, no replacement typed properties, guarded handled sets, baseline removal and unchanged rollback results. Review keeps the supplied branch-diff scope. Build alone owns the pack build command. Validate runs the affected domain attribution, application skill-removal and MCP parity/capture tests, detekt, formatting and runtime-core repoTest checks, including `FailureCodeTotalityArchitectureTest`. It also verifies A1 and A2 ownership, A4 declaration-only ports, A7 classification and propagation, A9 and A10 placement, and G7 shrinking baselines. Spotless runs in a plain clone. Planning and implement run no compilation, builds, tests or check suites. No commands or test evidence are claimed here. Validation and review may repair required production wiring, test setup, formatting and lint while preserving behavior, assertions and architecture rules. History, commits and PR work remain with their owning phases.

Across these tasks, preserve existing shared-file changes and byte-identical messages, cause chains, suppression and uncoded rendering. Add no module, dependency, typealias, exception property, family metadata, suppression, relaxed mock, helper module or new `runCatching`. Keep runtime-domain free of `java.nio` and ports imports, and keep runtime-contracts free of MCP code. Follow the interface-only KDoc rule and existing detekt limits; enum-and-factory files use the enum's filename. No schema version, persisted wire-format or install refresh is part of this conversion. All implementation inspection and confirmations described above belong to later phases; the preplan digest is not re-verified during plan.
