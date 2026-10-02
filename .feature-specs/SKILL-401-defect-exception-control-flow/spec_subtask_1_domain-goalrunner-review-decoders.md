# SKILL-401 Subtask 1 - domain-goalrunner-review-decoders

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
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
