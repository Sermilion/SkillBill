# SKILL-439 Subtask 1 - Inline review keeps pre-#471 behavior with an additive code-quality lane

Parent spec: `spec.md`

## Scope

All paths are under `../../../runtime-kotlin`.
- `ENG` = `runtime-engine/src/main/kotlin/skillbill/engine`
- `ENGT` = `runtime-engine/src/test/kotlin/skillbill/engine`

Production files in scope:
- `ENG/featuretask/slot/standalonereview/StandaloneReviewStrategies.kt`. Covers `standaloneDirective`, `runStartedReview`, and the inline vs delegated standalone strategies.
- `ENG/featuretask/slot/codereview/InlineReviewStrategy.kt`. Covers `InlineReviewPass.review`, `InlineReviewDirective.compose` and `InlineReviewResultDecoder`.
- `ENG/operation/verify/VerifyCodeReviewStep.kt`. Only the text of the rubric section; it switches to the shared framing helper.
- A new shared internal framing helper in the `skillbill.engine.featuretask.slot.codereview` package (`ENG/featuretask/slot/codereview/`).
- If the test-seam decision below is taken, a narrow resolver `fun interface` in the same package, plus its wiring in `runtime-core/src/main/kotlin/skillbill/di/featuretask/RuntimeFeatureTaskSlotProvides.kt`.

What changes:
1. **Shared additive lane section.** One internal function builds the code-quality lane section. It is the only builder; standalone inline, `InlineReviewDirective.compose`, and the verify inline step all call it, so the three copies cannot drift. The section has three parts, in order:
   - The heading `## Selected local code-quality rubric`.
   - Framing text with these points:
     - The section adds a code-quality lane to the review and does not replace it.
     - The agent remains responsible for every review area.
     - Any routing or Ignore lines in the rubric that hand defects to other lanes or specialists apply only to delegated review and must not narrow this inline review.
     - Findings from this rubric are attributed with the exact specialist identity.
   - The rubric text exactly as the resolver returns it.

   Keep the heading and the "exact specialist identity" wording so existing attribution and verify tests keep matching.
2. **Standalone inline directive.**
   - With no rubric, `standaloneDirective` produces exactly its pre-#471 text. That text is the current function minus the `qualityRubric` block; `git show 9fb13f174` on this file shows the baseline.
   - With a rubric, it appends the shared section where the block sits today: after the "A changes_requested verdict is a valid completed report..." line and before the Opus 5.5 suffix.
   - The delegated standalone strategy keeps its current directive and its blocking on planning failure. Branch on the inline vs delegated path; today the inline path has `reviewRunner == null`.
3. **Full-run inline directive.**
   - `InlineReviewDirective.compose` with no rubric produces exactly its pre-#471 text.
   - With a rubric, it appends the shared section where the block sits today: after `CodeReviewDirectives.inlineReview` and before the "Emit the bounded authoring evidence record..." paragraph.
   - The `revisions: Pair<String, String>` signature from #471 stays.
4. **Non-blocking rubric failure on both inline paths.**
   - When `resolveInlineCodeQualityRubric` returns `ParallelCodeReviewPlanned.Failed`, standalone inline no longer calls `block(...)`, and `InlineReviewPass` no longer returns `ParallelCodeReviewRunOutcome.PlanningFailed`.
   - Each launches without the lane section and records a warning through `RuntimeDiagnostics.warning(...)`. The message includes `failure.message`, for example "code-quality rubric unavailable; running inline review without the code-quality lane: <message>".
   - Use a `when` over `Ready`/`Failed`, not `runCatching` or `catch`. Engine sources may not have a catch followed by `diagnostics.warning` (`RuntimeDiagnosticsBestEffortGuardArchitectureTest`).
   - Assumption for implement to confirm: the review binding (`PhaseStepBinding` or `PhaseRunState` in `ENG/featuretask/slot/state`) exposes the `RuntimeDiagnostics` accessor. If neither inline path can reach diagnostics, record the warning in the retained run diagnostics instead. Never put it in the prompt.
5. **Decoder passthrough.**
   - In `InlineReviewResultDecoder.launchedResult`, restore `mergeResult = merged.copy(formattedOutput = stdout.ifBlank { "Review completed." })`.
   - Delete `inlineOutputWithNormalizedRegister`, `isTerminalVerdict`, `isRegisterSectionHeader` and the now-unused `ParallelReviewParseResult` import.
   - Leave `ParallelReviewParseResult.admittedLinePositions` in runtime-domain and `ParallelReviewMerger.formattedOutput(..., includeAgentLabels)` as they are. Both are harmless and also used outside this change.
   - Parsing of `specialist=` tokens into `specialistSkillName` is unchanged, so code-quality findings keep their identity.
6. **Test seam, decided.**
   - Add a narrow resolver type in the codereview slot package: `fun interface InlineCodeQualityRubricResolver { fun resolve(request: ParallelCodeReviewRequest): ParallelCodeReviewPlanned<String?> }`.
   - In `RuntimeFeatureTaskSlotProvides`, wire it as `reviewRunner::resolveInlineCodeQualityRubric`.
   - The inline strategies and `InlineReviewPass` take this type, so a test can return `Failed` without building a `ParallelCodeReviewRunner`.
   - The delegated standalone strategy keeps the same resolver behavior through the same wiring, and its own `ParallelCodeReviewRunner` dependency is unchanged.
   - If the shared `StandaloneReviewStrategy` base makes this awkward, implement may keep `ParallelCodeReviewRunner?` and test the failure branch through a pure helper that takes the `Planned` result. Record that choice.

## Acceptance Criteria

1. With no rubric, `standaloneDirective` produces exactly its pre-#471 text. With a rubric, the inline standalone directive appends the shared lane section before the Opus 5.5 suffix. The section states that it adds to the review, that the agent remains responsible for every review area, and that the rubric's routing/Ignore lines apply only to delegated review.
2. With no rubric, `InlineReviewDirective.compose` produces exactly its pre-#471 text. With a rubric, it appends the same shared lane section before the evidence-record paragraph. `VerifyCodeReviewStep` builds its inline rubric section through the same shared helper and keeps its current failure handling.
3. When rubric resolution returns `Failed`, standalone inline review and `InlineReviewPass` both launch the review without the lane section and emit a `RuntimeDiagnostics` warning containing the failure message. They do not call `block(...)` and do not return `PlanningFailed`. The delegated standalone strategy still blocks on that failure.
4. `InlineReviewResultDecoder` returns the agent's raw stdout as `formattedOutput` (or "Review completed." when stdout is blank). `inlineOutputWithNormalizedRegister`, `isTerminalVerdict` and `isRegisterSectionHeader` no longer exist. Parsed findings still carry the `specialist=` identity as `specialistSkillName`.
5. `StandaloneReviewReportAdmission`, `isCodeQualityFinding`, delegated review launch and planning, `ParallelCodeReviewRunner`, and platform-pack rubric files are unmodified by this subtask.

## Non-Goals

- Changing verify's rubric-failure behavior (`VerifyCodeReviewOutcome.Failed`), its `reviewOutcome` severity rule, or its delegated mode.
- Changing delegated review, specialist selection, delegated lane planning, or per-pack rubric text.
- Re-designing inline depth or the delegated fan-out; reverting SKILL-421's platform-pack additions.
- New review modes, flags, contracts, schemas, or migrations.
- Removing the runtime-domain `admittedLinePositions` field.

## Dependency Notes

- None. This is the only subtask and runs on the current tree.
- The standalone inline path (`launchInline`) already returns the raw stdout as `formattedOutput` and `rawOutput`. `StandaloneReviewReportAdmission.admit` reads raw output. So criterion 4 needs no change on the standalone path; it is pinned only by the existing admission tests.
- Restoring the decoder also restores verify's inline output handling, because `VerifyCodeReviewStep.reviewOutcome` calls `InlineReviewResultDecoder.decode`. The constraint allows this.
- Detekt `LongMethod` has recently failed on `runStartedReview` and `InlineReviewPass.review`. Extract the rubric-resolution `when` into a small private function if either grows.

## Test Obligations

Use kotlin.test with backtick names. Never use `relaxed = true` for mocks; use `relaxUnitFun = true`. Build each "pre-#471 baseline" from the no-rubric output of the same code, not from a hand-copied string.

1. Full-run prompt pin (AC2). In `ENGT/featuretask/slot/codereview/InlineReviewDirectiveTest.kt`, using its existing `compose` helper extended with an optional rubric: the with-rubric text equals the no-rubric text with the lane section inserted before the evidence-record paragraph. The lane section contains the additive, every-area and delegated-only routing statements and the rubric verbatim. Catches: framing dropped, pre-#471 text altered, or section moved after the evidence paragraph.
2. Standalone inline prompt pin (AC1). Add a new test class under `ENGT/featuretask/slot/standalonereview/`. Drive `InlineStandaloneReviewStrategy` with a fake runner and resolver, building the run and binding the way `PhaseStrategyCompositionTest` and `testFixtures/.../TestPhaseStrategies.kt` do. Capture the launched directive for `Ready(rubric)` and `Ready(null)`. The first equals the second with the lane section inserted before the Opus 5.5 suffix. Catches the narrowing regression this issue fixes, on the path where it was observed.
3. Rubric failure does not block standalone inline (AC3). Same class: with the resolver returning `Failed`, the review launches, the directive has no lane section, and a recording `RuntimeDiagnostics` holds a warning containing the failure message. Catches inline still blocking, or failing silently without a warning.
4. Rubric failure does not block the full-run pass (AC3). With `InlineReviewPass` and a resolver returning `Failed`, the outcome is not `PlanningFailed`, the review launches without the lane section, and a warning is recorded. Catches the second blocking site being missed.
5. Delegated standalone still blocks (AC3). Add this only if no existing test pins it: with the delegated `StandaloneReviewStrategy` and a resolver returning `Failed`, the step blocks with the failure message. Catches a shared-base change that makes delegated review non-blocking too.
6. Decoder passthrough (AC4). Update `ENGT/featuretask/slot/codereview/InlineReviewResultDecoderTest.kt`:
   - The "inline output keeps merged local quality attribution and verdict visible" test asserts `result.output == rawStdout`. The input includes Actionable/Refuted/Unresolved headers, a register in a non-final position, and a `specialist=bill-…-code-quality` line.
   - Assert that the parsed findings still carry that `specialistSkillName`.
   - Replace assertions that only held under the normalizer; do not add a sibling test.
   - Catches the register rewrite returning, or code-quality identity being lost.
7. Keep passing unchanged:
   - `StandaloneReviewReportAdmissionTest` (SKILL-413 rule, AC5).
   - `VerifyOperationTest`'s "inline verify receives the selected complete quality rubric in its launch", which asserts the rubric text and "exact specialist identity" in the directive.
   - The existing delegated review tests.

## Implementation Details

All paths use the `ENG`/`ENGT` prefixes from Scope. The tasks are ordered so each one compiles on top of the previous one. Implement runs no build or test; validate owns that.

### Task 1 - Shared lane section and resolver seam (serves AC1, AC2, AC3)

New file `ENG/featuretask/slot/codereview/InlineCodeQualityLane.kt`, package `skillbill.engine.featuretask.slot.codereview`:
- `fun interface InlineCodeQualityRubricResolver { fun resolve(request: ParallelCodeReviewRequest): ParallelCodeReviewPlanned<String?> }`. It must be **public**, because `runtime-core` DI constructs it.
- `internal object CodeQualityLaneSection` with `const val HEADING = "## Selected local code-quality rubric"` and `fun render(rubric: String): String`. `render` returns these lines, in order, newline-terminated, with the rubric last and verbatim:
  ```
  ## Selected local code-quality rubric
  This section adds a code-quality lane to this review. It does not replace or narrow the review.
  You remain responsible for every review area, including correctness, security, persistence, reliability, and accessibility.
  Routing or Ignore lines in the rubric below that hand defects to other lanes or specialists apply only to delegated review. In this inline review, report those defects yourself.
  Attribute findings from this rubric with the exact specialist identity.
  <rubric>
  ```
  Keep the wording "exact specialist identity" so `VerifyOperationTest` keeps matching. Tests should reference `HEADING` and `render(...)` rather than re-typing the sentences.
- `internal fun InlineCodeQualityRubricResolver?.resolveInlineRubric(request: ParallelCodeReviewRequest, diagnostics: RuntimeDiagnostics): String?`. Behaviour:
  - A `null` receiver returns `null`.
  - `Ready` returns `value`.
  - `Failed` calls `diagnostics.warning("code-quality rubric unavailable; running inline review without the code-quality lane: ${failure.message}")` and returns `null`.
  - Use a plain `when`, never `runCatching` or `catch`, to satisfy `RuntimeDiagnosticsBestEffortGuardArchitectureTest`.
  - This is the one non-blocking resolution path for both inline call sites. It also keeps `runStartedReview` and `InlineReviewPass.review` under detekt `LongMethod`.

### Task 2 - Full-run directive (serves AC2)

`ENG/featuretask/slot/codereview/InlineReviewStrategy.kt`, `InlineReviewDirective.compose`:
- Replace the #471 block ("Use the full owner rubric below...") with `qualityRubric?.let { ...; append(CodeQualityLaneSection.render(it)) }`.
- Keep the block's current position: after `CodeReviewDirectives.inlineReview` and before the "Emit the bounded authoring evidence record..." paragraph.
- Keep the same leading and trailing blank-line handling the block has today, so the evidence paragraph stays separated by one blank line.
- When `qualityRubric == null`, nothing is emitted, so the text is the pre-#471 text.
- Keep the signature `compose(target, revisions, specPath, agentAddonsSection, qualityRubric: String? = null)` unchanged.

### Task 3 - `InlineReviewPass` is non-blocking (serves AC3)

Same file, `InlineReviewPass.review`:
- Replace the `when (resolveInlineCodeQualityRubric(...)) { is Failed -> return ParallelCodeReviewRunOutcome.PlanningFailed(...) ... }` with `val qualityRubric = qualityRubricResolver.resolveInlineRubric(request, diagnostics)`.
- Pass `request` the same way it is built today.
- Pass `qualityRubric` into `compose`.
- The `"$composed\n\n${InlineReviewOpus55Strategy.DIRECTIVE}"` suffix composition stays as it is.
- Change the pass's resolver dependency type, and that of `InlineReviewStrategy` and `InlineReviewOpus55Strategy`, from `ParallelCodeReviewRunner?` to `InlineCodeQualityRubricResolver? = null`.
- Remove the now-unused `ParallelCodeReviewRunner` import where nothing else in the file uses it.
- Assumption for implement to confirm: the strategy that calls `InlineReviewPass.review` holds the phase binding, and `PhaseStepBinding`/`PhaseRunState` in `ENG/featuretask/slot/state` expose `RuntimeDiagnostics`. If the pass cannot reach the binding, add a `diagnostics: RuntimeDiagnostics` parameter to `review(...)` and supply it from the calling strategy. If no inline path can reach `RuntimeDiagnostics`, record the warning in the retained run diagnostics, as Scope item 4 says, and note the choice in the implement summary. The warning never goes into the prompt.

### Task 4 - Decoder passthrough (serves AC4)

Same file, `internal object InlineReviewResultDecoder`:
- In `launchedResult`, set `mergeResult = merged.copy(formattedOutput = stdout.ifBlank { "Review completed." })`.
- Delete `inlineOutputWithNormalizedRegister`, `isTerminalVerdict`, `isRegisterSectionHeader` and the `ParallelReviewParseResult` import.
- Leave `ParallelReviewMerger.formattedOutput(..., includeAgentLabels)` and runtime-domain `admittedLinePositions` untouched.
- If `ParallelReviewMerger` becomes unused in this file, drop its import as well.
- Finding parsing (`ParallelReviewFindingParser`) is unchanged, so `specialistSkillName` survives.

### Task 5 - Standalone inline vs delegated (serves AC1, AC3, AC5)

`ENG/featuretask/slot/standalonereview/StandaloneReviewStrategies.kt`:
- Change `StandaloneReviewStrategy`'s `qualityRubricResolver` type to `InlineCodeQualityRubricResolver? = null`. Keep its `reviewRunner: ParallelCodeReviewRunner?` dependency for delegated launch exactly as it is.
- In `runStartedReview`, branch on the inline path (`reviewRunner == null`):
  - Inline: `val qualitySection = qualityRubricResolver.resolveInlineRubric(delegatedReviewRequest(...same args...), binding diagnostics)?.let(CodeQualityLaneSection::render)`. It never calls `block(...)`.
  - Delegated: keep today's `when` exactly, with `is Failed -> return block(binding, iteration, resolved.failure.message)`. Compute `qualitySection` from the existing three-line block via a private `delegatedQualityRubricSection(rubric)` that reproduces today's `appendLine` sequence byte for byte (heading, "Use this complete owner rubric and attribute its findings with the exact specialist identity.", rubric).
  - Extract the branch into a small private function if `runStartedReview` grows, to stay under detekt `LongMethod`.
- Change `standaloneDirective(run, target, head, qualitySection: String?)` to append `qualitySection?.let { appendLine(); append(it) }` at the current block position: after the "A changes_requested verdict is a valid completed report..." line and before the Opus 5.5 suffix. Match today's trailing newline so the delegated output is byte-identical.
- With `qualitySection == null`, the output is the pre-#471 text.
- Do not touch `launchInline`, `launchDelegated` or `StandaloneReviewReportAdmission`.

### Task 6 - Verify uses the shared section (serves AC2)

`ENG/operation/verify/VerifyCodeReviewStep.kt`:
- Replace the inline-mode copy of the rubric section ("## Selected local code-quality rubric" / "Use this complete owner rubric...") appended to `VerifyPromptSections.codeReviewDirective(...)` with `CodeQualityLaneSection.render(rubric)`. Keep today's separator before it.
- Keep `is Failed -> return VerifyCodeReviewOutcome.Failed(prepared.failure.message)`, the `reviewOutcome` severity rule, `VerifyDelegatedReviewer`, and delegated mode unchanged.

### Task 7 - DI wiring (serves AC3)

`runtime-core/src/main/kotlin/skillbill/di/featuretask/RuntimeFeatureTaskSlotProvides.kt`, around lines 131-150:
- Build `val inlineRubricResolver = InlineCodeQualityRubricResolver(reviewRunner::resolveInlineCodeQualityRubric)` once.
- Pass it in place of `reviewRunner` as the resolver argument to `InlineReviewStrategy`, `InlineReviewOpus55Strategy`, `InlineStandaloneReviewStrategy` and `InlineStandaloneReviewOpus55Strategy`.
- Pass it as `qualityRubricResolver = inlineRubricResolver` to the delegated `StandaloneReviewStrategy`, whose `reviewRunner` argument stays `reviewRunner`.
- `testFixtures/.../TestPhaseStrategies.kt` and `PhaseStrategyCompositionTest` build these strategies without a resolver. They need no change unless they pass the resolver by name; if they do, update only the type.

### Task 8 - Tests (Test Obligations 1-6; AC1-AC4)

- **Obligation 1**, `ENGT/featuretask/slot/codereview/InlineReviewDirectiveTest.kt`:
  - Give the private `compose` helper `qualityRubric: String? = null`.
  - Assert that `withRubric == noRubric` with `CodeQualityLaneSection.render(rubric)` (plus the separator) inserted immediately before the index of "Emit the bounded authoring evidence record".
  - Assert that the inserted text contains the rubric verbatim.
- **Obligations 2 and 3**, new `ENGT/featuretask/slot/standalonereview/InlineStandaloneReviewDirectiveTest.kt`:
  - Build `InlineStandaloneReviewStrategy` with a fake runner and an `InlineCodeQualityRubricResolver` lambda, following the run and binding construction in `PhaseStrategyCompositionTest` and `TestPhaseStrategies`.
  - Capture the launched directive from the fake runner's step input.
  - Pin `Ready(rubric)` against `Ready(null)`: the lane section is inserted before `InlineStandaloneReviewOpus55Strategy.DIRECTIVE`, or before the end when the non-Opus strategy is used.
  - Pin the `Failed` case: the review launched, the directive lacks `CodeQualityLaneSection.HEADING`, and a recording `RuntimeDiagnostics` warning contains the failure message.
  - Assumption for implement to confirm: how a `ParallelCodeReviewPlanningFailure` is constructed. Read its declaration in runtime-application.
- **Obligation 4**: add one test for `InlineReviewPass` with a `Failed` resolver. Put it in the existing codereview test class that already drives `InlineReviewPass` or `InlineReviewStrategy` if one exists; otherwise put it in `InlineReviewDirectiveTest`'s package as a new class. Assert that the outcome is not `PlanningFailed`, the launch directive lacks the heading, and a warning is recorded.
- **Obligation 5**: first search the existing standalone tests for a delegated planning-failure block. Add the delegated-blocks test only if none exists.
- **Obligation 6**, `ENGT/featuretask/slot/codereview/InlineReviewResultDecoderTest.kt`:
  - Rewrite the "inline output keeps merged local quality attribution and verdict visible" test so its stdout has Actionable/Refuted/Unresolved headers, a register that is not in final position, and a `specialist=bill-kotlin-code-review-code-quality` line.
  - Assert `result.output == stdout`.
  - Assert that the parsed findings include that `specialistSkillName`.
  - Drop assertions that only held under the normalizer, such as the `"#### Code Quality (non-blocking)"` section and `endsWith("verdict: approved")`. Keep the first test's `ParallelReviewFindingParser.parse(result.output)` assertion; it still holds on raw output.
- **Obligation 7**: no edits to `StandaloneReviewReportAdmissionTest`, delegated tests, or `VerifyOperationTest`. If the verify harness asserts the old "Use this complete owner rubric" sentence verbatim, update only that literal to the new section text.
- Mocks: never `relaxed = true`; use `relaxUnitFun = true`. Prefer hand-written fakes and lambdas for the resolver and diagnostics.

### Constraints

- Files modified are limited to the four ENG production files above, the one new ENG file, the DI file, and the tests above.
- Untouched (AC5): `StandaloneReviewReportAdmission`, `isCodeQualityFinding`, `ParallelCodeReviewRunner` and runtime-application planning, delegated launch, platform-pack rubric files, and runtime-domain.
- The delegated standalone directive and its blocking behavior must be byte-identical to today. The pre-#471 inline text must be byte-identical when no rubric is present.
- No new modes, flags, contracts, schemas, migrations, or desktop schema bumps. No install or uninstall steps.
- Run spotless, detekt and `check` only in validate (`./gradlew spotlessApply`, then `./gradlew check` in `runtime-kotlin`).

### Assumptions recorded for implement

- A `RuntimeDiagnostics` accessor is reachable from the review binding (`PhaseStepBinding`/`PhaseRunState`). The fallback is in Task 3.
- The second positional constructor argument of the four inline strategies is the resolver (from the DI call shape `InlineReviewStrategy(runner, reviewRunner)`).
- The verify inline section is appended after `VerifyPromptSections.codeReviewDirective(...)` with a single separator, and that separator is kept.

## Validation Strategy

- Implement and audit run no builds or tests.
- The validate phase runs `./gradlew spotlessApply`, then `./gradlew check`, in `runtime-kotlin`. If spotless reports a stale JVM-local cache, rerun with `--no-configuration-cache`. This covers the obligations above, detekt, and the runtime-core architecture guards.
- Audit checks each criterion by reading the files named in Scope. To confirm criteria 1 and 2 byte-for-byte, it compares against `git show 9fb13f174`.

## Next Path

```bash
skill-bill goal SKILL-439
```
