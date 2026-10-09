# SKILL-413 Subtask 2 - Code-quality report section and telemetry category

Parent spec: `spec.md`

## Scope

This subtask makes code-quality findings report separately from failure findings, and gives them their own telemetry category. Separate reporting covers the prose report contract, the generic baseline finding discipline, and the deterministic merger: partitioning, Minor normalization, the cap, and section output. Failure findings must come out exactly as they do today. A finding counts as code-quality when its lane skill name or `specialist=` attribution ends with `-code-quality`. That rule works whether or not the lane is composed yet, so apply it to the tree as present.

## Acceptance Criteria

1. The "Shared Report Structure" section of `../../../orchestration/review-orchestrator/PLAYBOOK.md` and of `orchestration/review-orchestrator/specialist-contract.md` contains the same added text, byte-identical in both files, and every pre-existing line in both sections is unchanged. The added text says:
   - code-quality findings go in a `#### Code Quality (non-blocking)` sub-section at the end of `### 2. Risk Register`, after all failure findings
   - their F-ids continue the sequence, each keeps its `specialist=` attribution, and each is Minor
   - they never change the Verdict
   - their action items, if any, come after failure action items
2. The Finding Discipline in the generic baseline `../../../platform-packs/generic/code-review/bill-generic-code-review/content.md` has added bullets stating the same sub-section placement and non-blocking rule, and its existing bullets are unchanged.
3. A single domain predicate identifies code-quality findings by a `-code-quality` suffix on the lane skill name or specialist skill name. The merger uses it, and no other site re-derives the rule.
4. `ParallelReviewMerger` partitions code-quality findings from failure findings before fuzzy dedup, then treats the two groups as follows:
   - **Failure group:** sorted, fuzzy-deduped and formatted exactly as today.
   - **Code-quality group:**
     - deduped only within itself
     - severity normalized to Minor
     - ordered by confidence (highest first, ties in emission order)
     - truncated to a named constant cap of 5
     - appended after all failure findings
5. `ParallelReviewMerger`'s `formattedOutput` emits failure findings first, then the `#### Code Quality (non-blocking)` heading, then the code-quality findings. When there are no code-quality findings, it emits no heading and the output is identical to today's. If the verdict-grouped path (`ReviewFindingRegisterOutcome` headers) formats findings, code-quality findings stay in that trailing sub-section there too.
6. Any runtime code that derives a verdict, blocking status or actionability from merged findings treats code-quality findings as non-blocking.
7. `ReviewIssueCategory` has `CODE_QUALITY("code_quality")`. `ReviewIssueCategoryNormalization` gets two additions:
   - a routed rule for `code-quality`, placed first in `routedCategoryRules`
   - explicit aliases `code_quality` and `code-quality`

   The existing `quality` alias still maps to `TESTING_QUALITY_GATE`, the `quality-check` routed rule still maps to testing, and `classifyFindingCategory` keyword fallback is unchanged.
8. `../../../docs/review-telemetry.md` lists `code_quality` in the `issue_category` taxonomy, alongside the existing values.
9. `ParallelReviewMerger` tests (runtime-domain) cover two cases:
   - **(a)** The input holds a code-quality finding on the same file as a failure finding, with near-identical description text above the 0.6 Jaccard threshold. Both survive as separate findings. The merged failure findings and their formatted lines are identical to merging the same input without the code-quality finding (regression criterion).
   - **(b)** Seven code-quality findings, one marked Major, yield exactly 5 code-quality findings. All are Minor, and they appear after all failure findings under the sub-section heading.
10. A `ReviewIssueCategoryNormalization` test (runtime-domain) asserts:
    - a finding from specialist `bill-generic-code-review-code-quality` resolves to `code_quality`
    - the explicit category `code_quality` resolves to `code_quality`
    - `quality` still resolves to `testing_quality_gate`

## Implementation Steps

1. **Prose contract.** Add the identical text to the Shared Report Structure in `PLAYBOOK.md` (~lines 129-160) and `specialist-contract.md` (~lines 81-112). Then add the generic baseline Finding Discipline bullets.
2. **Predicate.** Add the predicate in the `runtime-domain` review package next to the merger or the finding model. It is applied to raw findings (lane skill) and parsed findings (`specialistSkillName`).
3. **Merger.** In `runtime-domain/.../review/parallel/ParallelReviewMerger.kt`, partition before clustering, keep the failure path byte-for-byte equivalent, and add the code-quality tail with the constant cap (for example `CODE_QUALITY_FINDING_CAP = 5`).
   - If the merger assigns F-ids, code-quality ids continue after the failure ids.
   - Assumption to confirm: the inline (`InlineReviewStrategy.kt:210`) and standalone (`StandaloneReviewStrategies.kt:344`) callers replace `formattedOutput` with raw agent prose. There, section placement comes from the prose contract (step 1), while structured findings (persistence/telemetry) still come from the merger's partitioned, capped list. Confirm this. Do not rewrite agent prose.
   - Assumption to confirm: `ParallelReviewFindingParser` is heading-agnostic, so the new sub-section heading parses without parser changes. Add parser handling only if a parser test proves otherwise.
4. **Verdict/actionability.** Locate any deterministic verdict, blocking or actionability derivation (for example `ReviewActionability` or `ReviewFindingRegisterOutcome` grouping).
   - Assumption to confirm: Minor findings are already non-blocking. If so, the fixed Minor severity is enough and no code change is needed; record that in the implement notes. Otherwise exclude code-quality findings via the predicate.
5. **Telemetry.** Add the `ReviewIssueCategory` value and the routed rule and aliases in `ReviewIssueCategoryNormalization.kt`, and update `../../../docs/review-telemetry.md` (~line 309).
   - Assumption to confirm: the DB stores category as free text with no CHECK constraint, and `ReviewFindingStats`/`ReviewHealthStats` group by the stored string. If either enumerates categories explicitly, add the new value there.
   - Update `ReviewStatsRuntimeTest`, `DatabaseSchemaTest`, `ParallelReviewFindingParserTest`, `ReviewRunLaneResolverTest` and the runtime-mcp golden `mcp-triage-findings-orchestrated.json` only if they enumerate the full category list.
6. **Tests.** Add the merger tests (criterion 9) and the normalization test (criterion 10).

## Implementation Details

This ordered plan uses the upstream preplan digest as its repository evidence. It resolves the discovery placeholders in Implementation Steps without changing this subtask's scope or requirements. AC references below correspond to the numbered Acceptance Criteria above. Implementation produces the repository changes and test cases; validate owns their execution and command evidence.

### 1. Add the report contract text

Serves AC-001 and AC-002. Append the same text, byte-identical, within `Shared Report Structure` in `../../../orchestration/review-orchestrator/PLAYBOOK.md` and `orchestration/review-orchestrator/specialist-contract.md`. Require the `#### Code Quality (non-blocking)` sub-section at the end of `### 2. Risk Register`, after all failure findings. Require continuing F-ids, Minor severity, machine-readable `specialist=` attribution, an unchanged Verdict, and optional quality action items after failure action items. Reports without quality findings omit the sub-section.

Add corresponding bullets to Finding Discipline in `../../../platform-packs/generic/code-review/bill-generic-code-review/content.md`. Preserve every existing line in both shared report sections and every existing baseline bullet, including any routing addition already present from subtask 1. Do not add subtask 1's shared specialist exception here.

Test obligation: retain `../../../runtime-kotlin/runtime-infra/skills/src/repoTest/kotlin/skillbill/scaffold/platformpack/substanceaudit/SpecialistContractParityTest.kt`. Its existing section comparison catches drift between the two report contracts. No duplicate prose-parity test is needed. Validate runs this governed check.

### 2. Define and preserve quality identity

Serves AC-003 and supports AC-004 through AC-006. Add one narrowly visible, pure domain predicate beside the finding models in `../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/parallel`. It recognizes the `-code-quality` suffix on either the lane identity or specialist identity. All merge and formatting classification uses this predicate; no consumer repeats suffix logic or classifies description keywords.

Use the actual model inputs established by preplan: `ParallelReviewLaneResult.agentId`, `ParallelReviewRawFinding.specialistSkillName`, and merged `specialistSkillNames`. Do not introduce a separate lane skill-name field merely to match the requirement's wording. Preserve the identifying skill through merge provenance so a quality entry recognized from `agentId` alone remains identifiable when `formattedOutput` and `withRecordedVerdicts` receive merged findings. Preserve existing failure provenance exactly.

Assumption for implement to confirm within these symbols: a skill-shaped `agentId` ending in the suffix is the available lane identity, and the existing merged specialist provenance can retain it when raw specialist attribution is absent. If preserving that identity requires a model adjustment, keep it local to the domain finding representation and retain existing failure values and formatting. Do not add a port or forwarding wrapper.

Test obligation: exercise both specialist-attributed and lane-identity-only quality input in the merger boundary regressions below. The realistic bug is recognizing an entry during merging but losing its quality identity during formatting or verdict overlays.

### 3. Partition and merge findings deterministically

Serves AC-004, AC-006, and AC-009. Change `../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/parallel/ParallelReviewMerger.kt`, principally `merge`, to collect entries in the current lane-one, lane-two, integration emission order and partition them before fuzzy clustering.

Keep the failure path's same-file matching, Jaccard threshold strictly above `0.6`, severity-based representative selection, coalescence ordering, provenance, citations, commit attribution, and verdict data unchanged. Preserve failure-relative emission order when quality entries are interspersed. Failure F-ids and formatted lines must equal a merge with those quality entries removed.

For the quality group, retain same-file fuzzy dedup only within that group. Normalize severity to `ParallelReviewSeverity.MINOR` before any severity-sensitive selection. Choose each cluster's representative by confidence High, Medium, Low, then first emission, so an incorrectly emitted Major cannot beat a higher-confidence Minor. Preserve cluster provenance. Order resulting quality findings by representative confidence, with original representative emission order for ties. Apply one named constant, `CODE_QUALITY_FINDING_CAP = 5`, after deduplication and ordering. Append this group after all failures and assign sequential F-ids to the assembled list.

Test obligations in `../../../runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/parallel/ParallelReviewMergerTest.kt`:

- Add the AC-009 same-file case with failure and quality descriptions above the fuzzy threshold. Assert both survive, then compare complete failure findings and their formatted lines against merging the same input without quality. Exercise the two available quality identity sources without duplicating the test logic. This catches cross-group absorption, lost identity, and failure identifier or provenance drift.
- Add seven distinct quality findings and at least one failure. Include an emitted Major and mixed confidences with a tie. Assert exactly five quality findings, all Minor, ordered by confidence with emission-order ties, following every failure with continuing F-ids. This catches missing normalization, pre-order truncation, unstable ties, and a misplaced cap.
- Add one within-quality fuzzy duplicate pair whose lower-confidence entry is Major and whose higher-confidence entry is Minor. Assert one quality representative with the higher-confidence description, Minor severity, and retained attribution. This catches severity influencing representative selection and accidental loss of within-group dedup. It covers a different branch from the seven-distinct-findings case.

### 4. Keep the quality tail outside verdict grouping

Serves AC-005 and AC-006. In `ParallelReviewMerger.formattedOutput`, partition with the same identity predicate before deciding whether failure findings need `ReviewFindingRegisterOutcome` headers. Format failures through their existing path, then append the quality heading and quality lines. Quality lines retain the singular machine-readable `specialist=` syntax supported by `ParallelReviewFindingParser`; existing failure lines retain their trailing `specialists=` field unchanged. Emit no heading or additional whitespace when quality is absent. Preserve the same partition when `withRecordedVerdicts` regenerates output.

Preplan established that `FeatureTaskRuntimeReviewSeverity.blocksAdvance` and `requiresRemediation` are true only for Blocker or Major, and `FeatureTaskRuntimeReviewVerdict` uses those properties. Minor normalization therefore satisfies non-blocking behavior on that runtime path. `ReviewFindingActionability.isActionable` evaluates claim verdict and scope; it does not decide blocking. Preserve its semantics and optional quality action items. Do not exclude valid Minor suggestions from all actionability merely to prevent blocking.

Preplan also confirmed that `ParallelReviewFindingParser.parse` ignores headings and already reads `specialist=`. No parser change is planned. `InlineReviewStrategy.kt`, `StandaloneReviewStrategies.kt`, `ParallelCodeReviewRunnerResultAssembly.parallelResult`, and recorded-verdict handling in `ParallelCodeReviewRunner.kt` preserve agent prose on their displayed-output paths. Do not rewrite that prose or change these forwarding paths. The authored report contract governs displayed placement there; deterministic normalization and capping govern structured findings and do not repair agent prose that violates the rubric.

Test obligation: add or extend one merger verdict-overlay regression to assert unchanged failure outcome groups, a trailing attributed quality section, and the same quality identity after `withRecordedVerdicts`. Compare its no-quality output to the corresponding existing failure-only output, including whitespace. This catches quality entering verdict groups and accidental output changes on the legacy path. Retain existing complete-line, fuzzy-boundary, provenance, and verdict tests. Reuse existing runtime severity/verdict coverage for Minor's non-blocking rule; add a boundary assertion only if that coverage does not exercise approval and remediation from the normalized merge result. The concrete bug would be a quality-only merge requiring remediation or preventing advancement.

### 5. Add the telemetry category without changing existing classification

Serves AC-007, AC-008, and AC-010. Add `CODE_QUALITY("code_quality")` to `../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/model/ReviewIssueCategory.kt`. In `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/attribution/ReviewIssueCategoryNormalization.kt`, put the `code-quality` routed rule first in `routedCategoryRules` and accept explicit `code_quality` and `code-quality` through the existing punctuation normalization. Reference the owning enum's wire value rather than restating it downstream. Keep explicit and embedded category precedence, the `quality` alias, `quality-check` routing, and `classifyFindingCategory` unchanged.

Add `code_quality` alongside existing `issue_category` values in `../../../docs/review-telemetry.md`. Preplan established unrestricted TEXT storage and string-based stats aggregation. No changes to `DatabaseSchemaStatements.kt`, `ReviewHealthStats`, `ReviewFindingStats`, database migrations, or telemetry events are planned. Do not add speculative full-category-list updates to unrelated tests or goldens.

Test obligation in `../../../runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/attribution/ReviewIssueCategoryTest.kt`: add one compact normalization regression covering specialist `bill-generic-code-review-code-quality`, routed skill identity, both explicit spellings, `quality` resolving to `testing_quality_gate`, and unchanged `quality-check` routing. The realistic bug is the generic quality rule stealing the specialist category or the new aliases stealing the existing testing category. Assert resolved wire values at the public normalization boundary, without duplicating classifier logic.

### 6. Preserve boundaries and hand validation to its owner

Serves all criteria. Apply the digest's architecture rules A1, A2, A5, A6, A7, A10, A11, and A12. Identity, grouping, formatting policy, and classification stay pure in runtime-domain. Use imported simple names, existing enum wire owners, and narrow helpers. Respect file and package limits and the ban on authored Kotlin line/block comments. Add no modules, raw-map seams, guard exemptions, baseline debt, or generated support files. These constraints preserve behavior and architecture; they do not prevent required review or validation repairs to production wiring, test setup, formatting, or lint.

This plan has no dependency on subtask 1. Leave lane authoring, manifests, universal composition, sidecar delivery, snapshots for composition, and the shared specialist carve-out with that subtask. Tracker comments were unavailable during preplan; the supplied spec and digest settle this work without them. No additional repository discovery or decomposition is needed for planning.

Implement writes the scoped source changes and regression tests. Audit inspects each criterion, including byte-preserved existing prose and failure behavior. Review retains the supplied branch-diff scope. Validate alone executes the required full suite, including `./gradlew check`, formatting/static analysis, domain tests, repo parity checks, and required golden and agent-config checks. Its spotless work uses a local clone, with `--no-configuration-cache` only for the documented stale JVM-local-cache failure. No tests, compilation, build gate, or full checks run in plan. Installation refresh stays with the parent runtime's authorized operation; history, commits, push, PR, and monitoring stay with their owning later phases.

## Test Obligations

- Merger test (a): catches fuzzy dedup absorbing a code-quality finding into a failure finding or vice versa, and any change to failure output (regression criterion).
- Merger test (b): catches a missing cap, a missing Minor normalization, or code-quality findings sorting above failures.
- Normalization test: catches routed-rule ordering that would classify the lane as `testing_quality_gate` or `behavior_correctness`, and any regression of the existing `quality` alias.

## Non-Goals

- Lane authoring, manifest wiring, the shared-contract carve-out bullet and lane composition (subtask 1).
- Changing parsing of finding lines, failure-finding ordering, the fuzzy threshold or existing category mappings.
- Telemetry schema or event changes: `issue_category` is a free string in `telemetry-event-schema.yaml`.
- Keyword-based reclassification of existing findings.

## Dependency Notes

None. This subtask is independent of subtask 1. The predicate keys on the `-code-quality` suffix, so it applies to any such finding whether or not the lane is composed yet. With no code-quality findings present, behaviour is unchanged. Apply every rule to the tree as present.

## Validation Strategy

The validate phase runs `./gradlew check`. That covers spotless, detekt, unit tests, repoTests including the PLAYBOOK/specialist-contract parity validation, and golden tests. Run spotless in a local clone, not a linked worktree. Implement and audit inspect the tree only.

## Next Path

```bash
skill-bill goal SKILL-413
```
