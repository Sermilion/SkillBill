# SKILL-413 Subtask 2 - Code-quality report section and telemetry category

Parent spec: `.feature-specs/SKILL-413-code-quality-review-lane-additive-to-the-existing-code-review/spec.md`

## Scope

This subtask makes code-quality findings report separately from failure findings, and gives them their own telemetry category. Separate reporting covers the prose report contract, the generic baseline finding discipline, and the deterministic merger: partitioning, Minor normalization, the cap, and section output. Failure findings must come out exactly as they do today. A finding counts as code-quality when its lane skill name or `specialist=` attribution ends with `-code-quality`. That rule works whether or not the lane is composed yet, so apply it to the tree as present.

## Acceptance Criteria

1. The "Shared Report Structure" section of `orchestration/review-orchestrator/PLAYBOOK.md` and of `orchestration/review-orchestrator/specialist-contract.md` contains the same added text, byte-identical in both files, and every pre-existing line in both sections is unchanged. The added text says:
   - code-quality findings go in a `#### Code Quality (non-blocking)` sub-section at the end of `### 2. Risk Register`, after all failure findings
   - their F-ids continue the sequence, each keeps its `specialist=` attribution, and each is Minor
   - they never change the Verdict
   - their action items, if any, come after failure action items
2. The Finding Discipline in the generic baseline `platform-packs/generic/code-review/bill-generic-code-review/content.md` has added bullets stating the same sub-section placement and non-blocking rule, and its existing bullets are unchanged.
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
8. `docs/review-telemetry.md` lists `code_quality` in the `issue_category` taxonomy, alongside the existing values.
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
5. **Telemetry.** Add the `ReviewIssueCategory` value and the routed rule and aliases in `ReviewIssueCategoryNormalization.kt`, and update `docs/review-telemetry.md` (~line 309).
   - Assumption to confirm: the DB stores category as free text with no CHECK constraint, and `ReviewFindingStats`/`ReviewHealthStats` group by the stored string. If either enumerates categories explicitly, add the new value there.
   - Update `ReviewStatsRuntimeTest`, `DatabaseSchemaTest`, `ParallelReviewFindingParserTest`, `ReviewRunLaneResolverTest` and the runtime-mcp golden `mcp-triage-findings-orchestrated.json` only if they enumerate the full category list.
6. **Tests.** Add the merger tests (criterion 9) and the normalization test (criterion 10).

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
