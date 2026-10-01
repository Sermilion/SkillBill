# SKILL-398 Subtask 6 - no-control-flow-on-defect-exceptions

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
Issue key: SKILL-398

## Scope

(F-006) `IllegalArgumentException` and `IllegalStateException` mean code defects. Main code catches them at 97 sites (infra 42, domain 27, cli 11, application 9, engine 6, contracts 1, ports 1 at the census tree; found with `catch \(\w+: (IllegalStateException|IllegalArgumentException)\)`), mostly to turn a `require` failure into a value or into another exception. Remove that control flow, by kind:

1. **The `try` body is the runtime's own `require`/`check`/decoder used to validate input** (for example `runtime-domain/.../goalrunner/AttemptLedgerDecoding.kt:73`, `.../goalrunner/model/FeatureTaskRuntimeGoalContinuationOutcome.kt:87`, `.../review/parallel/ParallelReviewFindingParser.kt:203`, `:221`, `.../review/parallel/ParallelReviewTrailingStructuredFields.kt:127`, `.../review/context/model/packet/ReviewRunLaneSegmentAccountingJson.kt:60`, `.../goalrunner/subtaskreview/GoalSubtaskReviewStructuredFindingsParse.kt:126`): add or use a non-throwing validator (`...OrNull`, a sealed validation result) and branch on it; where the caller rethrows a schema failure, it throws that failure directly from the validation result. The original `require` stays only where it guards an invariant no input can break.
2. **The `try` body is a JVM or library API with a non-throwing form** (`toInt` / `toLong` / `toBigInteger` → `...OrNull`; `enumValueOf` / `valueOf` → `entries.firstOrNull`): use the non-throwing form and drop the catch.
3. **The `try` body is a JVM or library API with no non-throwing form** (for example `URI.create` in `runtime-infra/http/.../HttpRequestUri.kt:11`, kotlinx `SerializationException` in `runtime-contracts/.../JsonCodec.kt:111`, `Path.of` → `InvalidPathException`): keep the catch, narrowed to the most specific type the API documents.
4. **SKILL-392 follow-up.** `RuntimeOwnedReviewMode.parse` (`runtime-application/.../review/service/RuntimeOwnedReviewMode.kt`), `decodeScaffoldPayloadObject` (`runtime-application/.../scaffold/ScaffoldCommandRequestDecoder.kt`) and `validateReleaseRef` (`runtime-infra/skills/.../scaffold/runtime/validation/RepoValidationRuntime.kt`, behind the `RepoValidationGateway` port) report malformed user input with `require`, so the CLI catches `IllegalArgumentException` at 11 sites (SKILL-392 investigation lines 122, 248, 355, 377). Each owner returns a result or throws `SkillBillRuntimeException` with a code; the CLI maps the result or code to the same `UsageError` text and exit code; the CLI's IAE catches for these paths go. If the two wrap-and-`initCause` bodies (`runCatching { usage.initCause(error) }` in `FeatureTaskRuntimeRunRequestAssembly.kt` and `GoalCliRunCommands.kt`) still exist, they go with it.

Any `runCatching` inside a touched function follows the parent spec's constraint.

Excluded: the top-level arms in `runtime-cli/.../core/CliRuntime.kt` and `runtime-mcp/.../core/McpToolDispatcher.kt` (investigation F-008), and catches inside custom exception types owned by subtasks 3 and 5 (for example `ClaudeMcpProfileFailure : IllegalArgumentException`); if those types are already gone, their catches are in scope.

## Acceptance Criteria

1. Outside `CliRuntime.kt` and `McpToolDispatcher.kt`, no main source catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`. The only remaining catches in their family are of a specific library subtype (for example `SerializationException`, `InvalidPathException`) at a call into a JVM or library API that has no non-throwing form; `NumberFormatException` is not caught where an `...OrNull` function exists.
2. No main function validates external input with `require`/`check` and relies on a caller catching it; each such validator has a non-throwing form or a result the caller branches on.
3. `RuntimeOwnedReviewMode.parse`, `decodeScaffoldPayloadObject` and `validateReleaseRef` do not throw `IllegalArgumentException` for malformed input, and runtime-cli has no `IllegalArgumentException` catch for them; the CLI prints the same `UsageError` texts and exit codes as before.
4. Every user-visible message and every persisted byte is unchanged; existing tests pass with only exception-type assertion edits where a validator now returns a value.
5. `TypedParseBoundaryArchitectureTest` and detekt pass.

## Non-Goals

- Changing `CliRuntime.kt` or `McpToolDispatcher.kt` classification (F-008).
- The repo-wide `runCatching` sweep (F-007).
- Replacing `require`/`check` that guard true invariants.

## Dependency Notes

Depends on: none.
If subtasks 4 or 5 already turned a rethrown schema error into a code, throw that code from the validation result. Performs the follow-up SKILL-392 recorded and the `require` work SKILL-397 listed as a non-goal; applies to the files as they are after those bundles if they landed. The second lander keeps both edits.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: one test per new non-throwing validator that a former catch depended on, covering the invalid input path, and one CLI test per SKILL-392 path (review mode, scaffold payload, release ref) asserting the unchanged `UsageError` text and exit code.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_6_no-control-flow-on-defect-exceptions.md
