# SKILL-413 - code-quality-review-lane-additive-to-the-existing-code-review

## Mode

decomposed

## Intended Outcome

Every code review gets a code-quality lane in addition to its existing failure lanes. The lane judges how the code is written, using the idioms and best practices of the project's language. It reports low-severity, capped findings in their own risk-register section and under their own telemetry category. Existing lanes keep producing the same findings.

## Problem

The code review reliably finds behavioural defects, but it never reports code style, readability or construction quality. The review rules only allow reachable failures. The KMP router says "Report only reachable failures with a concrete precondition and observed consequence", and the generic architecture and platform-correctness specialists ignore naming, layout and style preferences. No lane owns maintainability. PostHog telemetry (project SkillBill, `skillbill_review_finished`, about 1,183 triaged findings since 2026-04) confirms this. The `issue_category` taxonomy has no readability or maintainability value. Only about a dozen genuine readability findings exist, mostly from April 2026, before the specialist-lane setup. None concerned state modelling, and some were rejected as competing with real defects.

## Goal

Add a code-quality review lane that runs in ADDITION to the existing review. It judges how the code is written, using the best practices and idioms of the language the project uses.

## Requirements

1. Generic first: the lane lives in the generic layer and is language-agnostic. It works before and independently of a platform pack being selected, and it derives idioms from the detected language and its standard library.
2. It runs in every review composition, whichever platform pack is routed (KMP, Kotlin, iOS, TypeScript, ...). KMP reviews currently flatten the Kotlin baseline and include no generic lanes, so the composition must add this lane explicitly.
3. Platform packs MAY add a language sidecar with concrete idiom examples (for example a Kotlin idioms list). The lane must work without one.
4. Scope of what it reviews:
   - Idiomatic constructs: prefer what the language or stdlib provides over hand-rolled code. Kotlin examples: `orEmpty()` instead of `?: ""`, and `emptyList()`/`orEmpty()` instead of constructing empty collections.
   - Scope/helper functions (Kotlin `let`/`run`/`also`/`apply` and equivalents elsewhere): use them where they improve clarity, and flag them where they obscure it.
   - State modelling: related state is held as one state type (data class or unified state flow) instead of scattered one-off variables. Boolean clusters that describe one lifecycle are modelled as a sealed type or enum. Calibration example, capmo-android PR #3110:
     - `CustomFreeTextViewModel`: `textContent` + `isLoaded` form the editor state, and `isSaved`/`isInDatabase`/`savedText`/`createdAt`/`isDeletingOwnEntry` are always written together in `onSavedEntry`/`persist`.
     - `VisitDetailViewModel`: `hasSeenVisit`/`hasCreatedVisit`/`isDeletingVisit`/`isVisitRemoved` describe one lifecycle.
   - Structure: duplication worth extracting, redundant branching, dead or unused code, functions or classes doing too much, and unclear naming.
5. Project conventions win: the lane reads repository guides when present (for example `docs/code-quality-best-practices.md`, `../../../AGENTS.md`, `CLAUDE.md`, linter/formatter configs) and follows them over generic taste.
6. Own finding discipline (the reachable-failure rule does not apply to this lane):
   - Every finding names a concrete smell, its location and a concrete rewrite.
   - Severity is fixed and low, so it never outranks a defect.
   - Findings are capped per review.
   - It must not repeat what a configured formatter or linter (for example ktfmt or detekt) already enforces.
7. Output: code-quality findings appear in their own section of the risk register, below the failure findings, with their own lane attribution.
8. Telemetry: a new `issue_category` value for these findings, so acceptance and rejection can be measured in `skillbill_review_finished`.

## Design Decisions (settled at plan)

- **D1 Lane home.** The lane is a new generic specialist, `bill-generic-code-review-code-quality` (area slug `code-quality`), at `../../../platform-packs/generic/code-review/bill-generic-code-review-code-quality/content.md`. The generic pack declares it with `lane_conditions.code-quality: { required: true }`. No other pack manifest declares it.
- **D2 Universal inclusion.** A new domain constant `UNIVERSAL_CODE_REVIEW_AREAS = setOf("code-quality")` lives beside `APPROVED_CODE_REVIEW_AREAS`, and `APPROVED_CODE_REVIEW_AREAS` is NOT extended. If it were, the substance audit (`PlatformPackSubstanceAuditPointerCatalog`) would require every maintained pack to cover the area. Lane composition handles a universal area as follows:
  - If the routed graph does not own the area, composition appends it from the manifest-declared fallback owner (`ReviewFallbackResolver.resolveOptional`).
  - If no fallback is installed, or the fallback does not declare the area, composition appends nothing and does not throw.
  - Generic is not made a `baseline_layer` of other packs, because that would union generic's other nine areas into every review and change existing lane sets.
- **D3 Language sidecar.** By convention, the lane reads an optional `code-quality-idioms.md` from the routed pack's code-review baseline skill directory, or from a baseline layer's. The Kotlin pack ships one. No new manifest key is added and no contract version is bumped.
- **D4 Fixed severity and cap.** Every code-quality finding is `Minor`, with at most 5 per review. The rubric says this. The merger enforces it deterministically by normalizing severity to Minor and truncating to 5.
- **D5 Additive carve-outs.** The shared specialist contract and the review-skill structure standard each get one additive exception for the code-quality lane. Existing bullets, lane files and Ignore lists stay byte-identical.
- **D6 Output section.** Code-quality findings are emitted after all failure findings, under a `#### Code Quality (non-blocking)` sub-section of `### 2. Risk Register`. F-ids continue the sequence, and each finding keeps its `specialist=` attribution. The merger partitions code-quality findings from failure findings before fuzzy dedup, so neither group can absorb or alter the other.
- **D7 Telemetry.** `ReviewIssueCategory.CODE_QUALITY("code_quality")` resolves from the lane label through a routed rule placed first, and from the explicit value `code_quality`. The existing `quality` alias keeps mapping to `testing_quality_gate`.
- **Documented limitation.** When no platform pack at all is installed, the runtime falls back to the horizontal base rubric, and this change does not extend that path. Whenever any pack is installed, the generic fallback carries the lane, including when nothing else matches.

## Acceptance Criteria

1. A generic specialist `bill-generic-code-review-code-quality` exists with a structure-conformant `content.md`. The generic pack manifest declares it as a required area, and the generic native-agent manifest has a matching agent entry.
2. The lane rubric covers idiomatic constructs, scope/helper functions, state modelling (including the PR #3110 calibration examples) and structure. It derives idioms from the detected language and stdlib without needing any platform pack.
3. Lane composition includes the code-quality lane for every routed pack whose installation includes the generic fallback, including KMP, Kotlin, iOS, TypeScript and generic itself. Tests assert this for KMP, Kotlin and generic-as-root, and assert that the lane is absent without error when no fallback declares it.
4. The lane reads an optional `code-quality-idioms.md` sidecar when the routed pack or a baseline layer ships one. The Kotlin pack ships one, and the rubric states that the lane works without a sidecar.
5. The lane rubric tells the reviewer to read repository guides and linter/formatter configs, and to follow them over generic taste.
6. The lane rubric requires smell + location + concrete rewrite for every finding, fixed Minor severity, a cap of 5 per review, no repetition of configured formatter/linter rules, and no auto-fixing. The merger enforces Minor severity and the cap.
7. Code-quality findings appear in their own `#### Code Quality (non-blocking)` risk-register sub-section after all failure findings, with `specialist=` lane attribution. The shared report structure in `PLAYBOOK.md` and `specialist-contract.md` describes this identically in both files.
8. `ReviewIssueCategory` has a `code_quality` value that code-quality lane findings resolve to, and `../../../docs/review-telemetry.md` lists it.
9. Additive only: no existing specialist `content.md`, Ignore list, or existing shared-contract bullet changes, and `APPROVED_CODE_REVIEW_AREAS` is unchanged.
10. Regression: tests assert two things. Non-code-quality lanes of a composed plan are identical with and without the code-quality lane available. Merged failure findings are identical with and without code-quality findings in the input.

## Non-Goals

- Changing the scope, finding rules or Ignore lists of any existing specialist lane, the Kotlin baseline lanes, or the KMP baseline router text.
- Scaffolding a code-quality lane into new or existing non-generic packs, or adding `code-quality` to `APPROVED_CODE_REVIEW_AREAS`.
- New manifest keys, a platform-pack `contract_version` bump, or `SHELL_CONTRACT_VERSION` changes.
- Formatting or lint duplication; auto-fixing in report-only standalone review.
- Extending the horizontal no-pack-installed review path.
- Keyword-fallback reclassification of existing findings into the new category.

## Subtasks

Two independent subtasks. Each applies its rule to the tree as it is and has no ordering dependency on the other.

1. `spec_subtask_1_code_quality_lane_and_composition.md`: authors the generic lane, the Kotlin idioms sidecar, the allowlist/validator exceptions and the shared-contract carve-out, plus universal composition in lane planning. This covers criteria 1-6 (rubric side), 9 and 10 (plan side).
2. `spec_subtask_2_code_quality_report_section_and_telemetry.md`: covers the risk-register sub-section (prose contract and merger partition, severity normalization and cap) and the `code_quality` telemetry category. This covers criteria 6 (merger side), 7, 8, 9 and 10 (merger side).

Split condition: the two parts ship separately. Report partitioning and the telemetry category work for any finding attributed to a `-code-quality` lane, whether or not the lane is composed yet. Lane composition works with today's merger. Together they are too broad for one reviewable implement pass: governed rubric authoring under a substance audit, plus runtime planning, plus merger semantics.

## Risks

- R1: Adding `code-quality` to `APPROVED_CODE_REVIEW_AREAS` fails the substance audit for every maintained pack. Use `UNIVERSAL_CODE_REVIEW_AREAS` instead.
- R2: Making generic a baseline layer changes existing lane sets. Do not do it.
- R3: `PLAYBOOK.md` and `specialist-contract.md` have byte-parity sections, so any added text must land identically in both.
- R4: The installed runtime can lag the checkout. Do not bump contract versions.
- R5: The planned-worker native-agent validation requires the generic agent to exist whenever its lane is planned. The append is skipped when the fallback is absent.
- R6: Fuzzy dedup could merge code-quality and failure findings. Partition before clustering.

## Validation Strategy

Only the validate phase runs `./gradlew check`. It covers spotless, detekt, unit tests, repoTests and agent-config validation. Run spotless in a local clone, not a linked worktree. If it reports "JVM-local cache is stale", rerun with `--no-configuration-cache`. Snapshot refreshes (`bill-kmp-code-review.render.txt`, `bill-kotlin-code-review.render.txt`) land in the same commit as the composition change.

## Rollout

There is no data migration, DB schema change or new telemetry event. The only external change is the new `issue_category` value `code_quality` in `skillbill_review_finished`. After merge, reinstall the runtime and skills so installed agents pick up the new generic agent. Then watch `code_quality` acceptance and rejection in PostHog (project SkillBill).

## Next Path

```bash
skill-bill goal SKILL-413
```
