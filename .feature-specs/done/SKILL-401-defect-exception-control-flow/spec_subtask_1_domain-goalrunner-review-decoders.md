# SKILL-401 Subtask 1 - domain-goalrunner-review-decoders

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE/ISE control flow at the 11 runtime-domain sites under `goalrunner/`, `review/` and `workflow/model/` (kinds A and D).

**Method for each site.** Replace the `try`/`catch (IllegalArgumentException)` with explicit checks before construction:

1. Call `violation(...)` and the `…OrNull` helpers.
2. On failure, throw the schema failure the catch used to throw, with byte-identical text (ground rule 2).
3. Then construct the value. Its `require` is now an invariant that no decoded input can break.

Where the body builds a value only from runtime-produced data (a kind-D site), drop the catch and keep the `require`. For example, check the `fromMeasurements`-style factory at `FeatureTaskRuntimeValidationGateExecutionEvidence.kt:87`.

Sites, all under `runtime-domain/.../`:

- `goalrunner/ledger/AttemptLedgerDecoding.kt:73`. Also convert the `runCatching { parsePersistedInstant(…) }` at `:48` in the same function.
- `goalrunner/model/FeatureTaskRuntimeGoalContinuationOutcome.kt:89`
- `goalrunner/subtaskreview/GoalSubtaskReviewStructuredFindingsParse.kt:126`, via `repositoryRelativePathViolation`.
- `review/parallel/ParallelReviewFindingParser.kt:203`, `:208`, `:221`. Both rejection reasons keep their mapping.
- `review/parallel/ParallelReviewTrailingStructuredFields.kt:127`. Check the path before building `ReviewFindingCitation`; a failure keeps the `"invalid_path"` diagnostic.
- `review/context/model/packet/ReviewRunLaneSegmentAccountingJson.kt:60`
- `workflow/model/goalobservability/GoalObservabilityParsing.kt:100`, via `parsePersistedInstantOrNull`.
- `workflow/model/goalreview/GoalSubtaskReviewState.kt:315`. Includes the `CodeReviewExecutionMode.fromWire` uses at `:289` and `:308`.

Also check `GoalSubtaskReviewFindingArtifacts.kt:130` and `GoalObservabilityModels.kt:94` and `:229`. If a caller catches their throwing calls, switch those calls to the `…OrNull` form. Otherwise leave them.

## Acceptance Criteria

1. No main source under `runtime-domain/.../goalrunner/`, `.../review/` or `.../workflow/model/` catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`.
2. Each decoder throws the same schema failure, with byte-identical text, from an explicit validator check.
3. `ParallelReviewFindingParser` keeps both rejection-reason mappings, and `ParallelReviewTrailingStructuredFields` keeps the `"invalid_path"` diagnostic.

## Non-Goals

The `workflow/taskruntime/` decoders (subtask 2).

## Test obligations

- `decodeParallelReviewStructuredStringOrNull`: a malformed `\u` escape gives `UNPARSEABLE_STRUCTURED_PATH`.
- `repositoryRelativePathViolation`: a traversing path gives `NO_ADMISSIBLE_LOCATION`.

Add each only if no existing test drives that branch.

## Shared Rules

Apply `spec.md` "Shared validators", "Ground rules", "Site classification", "Test rules" and "Execution Rule". If a shared validator this subtask needs is missing, add it as written there.

## Common Acceptance Criteria

- Every user-visible message and every persisted byte is unchanged. Existing tests pass with only exception-type assertion edits where a validator now returns a value.
- The exception type that reaches `CliRuntime.run` or `McpToolDispatcher.dispatch` for a given input is unchanged, or is a code on the MCP no-capture list.
- `TypedParseBoundaryArchitectureTest` and detekt pass. No `ParseBoundarySite` entry is dropped.
- Sites outside this subtask's scope are unchanged, except for callers of a port or validator this subtask changed.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Add only the tests listed under Test obligations, and only where no existing test already drives the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-401

## Spec Path

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_1_domain-goalrunner-review-decoders.md

## Implementation Details

This plan uses the upstream preplan digest at checkout HEAD `5a17798c15c908c1d5a9c40ca025f0cb1bcb50d6`. Planning performed no repository discovery. Paths below are relative to `../../../runtime-kotlin`; domain production paths start at `runtime-domain/src/main/kotlin/skillbill/`, and domain test paths start at `runtime-domain/src/test/kotlin/skillbill/`. Historical line numbers in Scope are not implementation anchors.

### Ordered tasks

1. Convert goal-progress and goal-continuation decoding. Serves AC-001 and AC-002.

   In `workflow/model/goalobservability/GoalObservabilityModels.kt`, add one ordered `GoalProgressEvent.violation` helper for nonblank workflow identity and phase, nonnegative sequence, and the operation name required for operation events. Have the model invariant and `goalrunner/ledger/AttemptLedgerDecoding.kt`'s `decodeDeclaredGoalProgressEvent` use that same helper. Replace the timestamp `runCatching` with `parsePersistedInstantOrNull`, preserving rejection at `timestamp` with `must be an RFC 3339 instant.` Check the event before construction and preserve `<root>` rejection through `invalidGoalProgressEventSchemaError`.

   In `goalrunner/model/FeatureTaskRuntimeGoalContinuationOutcome.kt`, validate the nullable `GoalRunnerTerminalStatus.fromWire` result and the issue-key, positive-subtask, workflow-id, and resumable-step constraints before construction in `fromArtifactMap`. Share ordered model constraints with its invariant and retain `invalidWorkflowStateSchemaError` with `Feature-task-runtime goal-continuation outcome is invalid.` The string-status secondary constructor may remain throwing for edge-only callers.

   Retain existing observable coverage in `workflow/model/goalobservability/GoalObservabilityModelsTest.kt`. No new test obligation is assigned here by the sub-spec. Implement must preserve existing schema-message assertions and model checks without adding tests that repeat them.

2. Replace structured-path exception branches with nullable decoding and explicit path validation. Serves AC-001, AC-002, and AC-003.

   In `review/parallel/ParallelReviewTrailingStructuredFields.kt`, add `decodeParallelReviewStructuredStringOrNull(encoded: String): String?`. Missing quotes, dangling escapes, short Unicode escapes, non-hex Unicode escapes, and unsupported escapes return null. Parse Unicode digits with `toIntOrNull(JSON_UNICODE_ESCAPE_RADIX)`. Remove the throwing decoder if it has no remaining reader. In `review/parallel/ParallelReviewFindingParser.kt`'s `resolvePath`, map null decoding to `UNPARSEABLE_STRUCTURED_PATH`, then map a non-null `repositoryRelativePathViolation` to `NO_ADMISSIBLE_LOCATION`. In `goalrunner/subtaskreview/GoalSubtaskReviewStructuredFindingsParse.kt`, branch on the existing path violation helper instead of catching the throwing validator.

   Validate citation input before constructing `ReviewFindingCitation` in `parseCitationToken`. The model is in `review/model/ReviewStageState.kt`; share its ordered nonblank-path, positive-line, and repository-relative-path checks through a violation helper. Preserve line-number `toIntOrNull`, zero-to-one coercion, `invalid_path`, and all existing diagnostic fields. Retain the shared path validator's ordered checks and its re-export in `review/context/model/hunk/ReviewContextCanonical.kt`.

   Test obligations are limited to the two existing sub-spec obligations. Keep `review/parallel/ParallelReviewFindingParserTest.kt`'s `invalid path still parses and does not abort later findings`, which already covers a short Unicode escape and both rejection categories. Add one traversing-path case in that existing suite to catch the realistic bug where a decoded `../..` path is admitted or receives the decoding-failure category instead of `NO_ADMISSIBLE_LOCATION`. Assert the parser's observable rejection and continued parsing of later findings. Retain `goalrunner/subtaskreview/GoalSubtaskReviewStructuredFindingTest.kt`; do not add duplicate malformed-escape cases.

3. Convert lane-accounting and observation timestamp decoding. Serves AC-001 and AC-002.

   Add an ordered `ReviewLaneSegmentAccounting.violation` beside its model in `review/context/model/packet/ReviewLaneBundleAssembly.kt`. Check nonblank segment id, nonnegative measured bytes and entry count, and lowercase SHA-256 composition digest. Its unlabelled assertions must still yield `Failed requirement.` Have the invariant and `review/context/model/packet/ReviewRunLaneSegmentAccountingJson.kt`'s `decodeSegment` use the helper, with decoder rejection unchanged as `Segment accounting entry [$index] violates its value constraints.`

   In `workflow/model/goalobservability/GoalObservabilityParsing.kt`'s `parseObservationTimestamp`, branch on `parsePersistedInstantOrNull` and preserve `field must be a persisted instant.`, the source label, and the field. Retain all supported timestamp forms.

   Reuse `review/model/ReviewRunLaneSegmentAccountingJsonTest.kt` and `workflow/model/goalobservability/GoalObservabilityModelsTest.kt` as later validation evidence. No new test obligation is assigned to these conversions.

4. Validate the complete durable goal-review construction chain before construction. Serves AC-001 and AC-002.

   In `workflow/model/goalreview/GoalSubtaskReviewState.kt`'s `fromArtifactMap`, parse both execution-mode fields through `CodeReviewExecutionMode.fromWireOrNull`. Extract shared ordered violation checks for contract version, SHA identities, sorted unique baseline paths, pass counts and sequence, executed modes, reservation accounting, disposition relationships, operator decision, and unique repair-receipt rounds. Convert nested pass-result and compact-finding input construction in `GoalSubtaskReviewFindingArtifacts.kt` as part of the same chain, since those checks are inside the former outer catch's reach. Model invariants use the same reason source. Keep `InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA` handling and `reviewStateError` framing, including exact reason placement and validation precedence.

   Use `DurableArtifactMapReader` and exact numeric helpers at durable boundaries. Preserve the separately retained lenient attempt-ledger coercion. Keep `workflow/model/goalreview/GoalSubtaskReviewStateTest.kt`'s existing immutable-mode, malformed-receipt, strict-field, and durable-key coverage. These tests remain the evidence for those branches; no duplicate tests are planned.

5. Convert the two bounded nullable path callers. Serves AC-002 and the common scope exception for callers of changed validators.

   In `runtime-application/src/main/kotlin/skillbill/application/review/verification/ReviewClaimVerificationRunner.kt`, change the cited-region path resolver to branch on `repositoryRelativePathViolation` and retain its null-on-invalid result. Apply the same conversion to `goalPlanningNormalizeFindingPath` in `runtime-infra/workflow/src/main/kotlin/skillbill/infrastructure/workflow/goalplanning/GoalPlanningRepositoryScopeWalk.kt`. Remove their validator `runCatching` calls. Do not expand this into a repository-wide sweep or change either caller's absence policy. These are trivial caller conversions covered by the shared path boundary evidence, so their test obligations are empty.

6. Complete the repository end state and hand off validation evidence. Serves all acceptance criteria and the common architecture criteria.

   Implement removes the digest's 11 domain catch sites and the two bounded caller wrappers rather than hiding them behind another exception boundary. Preserve true invariant assertions and edge-only throwing wrappers. Keep named `ParseBoundarySite` functions; if a required rename occurs, update its location in `runtime-core/src/repoTest/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt` without removing coverage. No guard, baseline, or suppression may widen. The `fromMeasurements` example in Scope belongs to subtask 2 and is not an additional task here; the digest also confirms its invalid-measurement rejection must remain coded there.

   Audit inspects every criterion, validation order, failure code, exact message, rejection category, diagnostic, and bounded caller result. Review retains branch-diff scope. Buildability proof belongs only to the build phase. Validate runs the behavioural suites named above, detekt, `TypedParseBoundaryArchitectureTest`, `FailureCodeTotalityArchitectureTest`, and the full runtime-core repoTest and repository gates. It also runs `../../../scripts/validate_agent_configs` as required by CI. The digest identifies the Kotlin pack's collect-all gate and cache-bypassing counterpart but omits their exact argv; validate resolves those commands from the declared manifest rather than this plan inventing them. Validation may repair production wiring, test setup, formatting, or lint while preserving behaviour, assertions, and architecture rules.

### Constraints and implementation assumptions

- Preserve each validator's existing check order and one source for each reason. Preserve schema failure owners, byte-identical text, map keys and field order, numeric compatibility, optional omissions, timestamp spelling, contract versions, and telemetry capture behaviour. Causes may disappear only where the supplied failure model permits it; emitted messages do not change.
- Reuse the four validators already present. Preserve `ValidationDepth.fromWireOrNull` compatibility for retired `build_only` as `FULL`; no change to that helper is needed. Do not recreate the prior CLI review-mode, release-ref, or scaffold-object conversions.
- Keep domain helpers pure and in their owning model packages, with no `java.nio` or ports dependencies. Follow A1, A2, A4, A7, A10, A11, and G7. Do not add a throwable, typealias, generic result library, runtime exception property, comments forbidden by repository rules, or suppressions. Keep files below the 1,200-line ceiling and respect detekt limits through small helpers.
- Keep cancellation, interruption, and unrelated coded failures propagating. Any retained runtime-exception handler must use its existing owned code or family predicate. Do not change protected CLI/MCP edges, the generic runner, ports, schema versions, skill sources, or other subtasks.
- The digest lists review-state constraint families but not every existing literal or their full internal ordering. Implement confirms those details in the owning constructors and preserves them exactly; planning assumes those constructors are the authoritative reason source. This is an implementation confirmation, not an unresolved product decision or a request for another preplan.
- Add only the traversing-path regression described above. Existing tests supply the malformed-Unicode and durable-decoder evidence. Preserve real regression and governed parity coverage; exception-type assertion changes must never change expected text.
- This phase changes only this sub-spec. It executes no builds, tests, checks, branch operations, or workflow continuation. Later history, commit/push, and PR work remain with their runtime-owned phases. No migration, feature flag, public command, or installation refresh is required. Spotless validation retains the parent plain-clone constraint, and the runtime owns branch preparation.
