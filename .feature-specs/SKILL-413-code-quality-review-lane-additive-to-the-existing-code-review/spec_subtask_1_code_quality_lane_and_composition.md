# SKILL-413 Subtask 1 - Code-quality lane and universal composition

Parent spec: `.feature-specs/SKILL-413-code-quality-review-lane-additive-to-the-existing-code-review/spec.md`

## Scope

This subtask authors the generic code-quality specialist and makes lane planning include it in every review composition, whichever pack is routed. It also adds the narrow allowlist, validator and shared-contract exceptions the lane needs, and ships the optional Kotlin idioms sidecar. It does not change how findings are merged or categorized; subtask 2 owns that. Apply every rule to the tree as it is when this subtask runs.

## Acceptance Criteria

1. `platform-packs/generic/code-review/bill-generic-code-review-code-quality/content.md` exists. Its H2 sections are Focus, Ignore, Applicability and Project-Specific Rules, in that order, as `review-skill-structure-standard.md` requires. Its Project-Specific Rules have H3 groups, backticked rule ids and the required verbs and failure-mode wording. Its final rule is the code-quality closer defined in criterion 7.
2. The Focus section of that `content.md` covers four families:
   - idiomatic constructs (prefer the language/stdlib over hand-rolled code, with the Kotlin `orEmpty()` and `emptyList()` examples)
   - scope/helper functions (use where clearer, flag where obscuring)
   - state modelling: one state type instead of scattered variables, and lifecycle boolean clusters as a sealed type or enum, including both PR #3110 calibration examples (`CustomFreeTextViewModel`, `VisitDetailViewModel`) with their field names
   - structure (duplication, redundant branching, dead/unused code, oversized units, unclear naming)
3. The rubric tells the reviewer to derive idioms from the detected language and its standard library. It also tells them to read repository guides when present (`docs/code-quality-best-practices.md`, `AGENTS.md`, `CLAUDE.md`, linter/formatter configs) and to follow them over generic taste.
4. The rubric requires every finding to name a concrete smell, its `file:line` location and a concrete rewrite. It fixes severity at Minor, caps findings at 5 per review, and states that the reachable-failure rule of other lanes does not apply to this lane. Its Ignore section excludes anything a configured formatter or linter enforces (ktfmt, detekt, eslint, prettier, swiftformat), formatting, behavioural failures owned by other lanes, and unchanged code. The rubric forbids auto-fixing.
5. The generic manifest wires the lane, and the generic baseline routes to it:
   - `platform-packs/generic/platform.yaml` declares `code-quality` in `declared_code_review_areas` and wires `declared_files.areas.code-quality`, `area_metadata.code-quality.focus` and `lane_conditions.code-quality: { required: true }`.
   - It also has a `specialist-contract.md` pointers entry for the new skill directory.
   - `code-review/bill-generic-code-review/native-agents/agents.yaml` has a `bill-generic-code-review-code-quality` entry whose description follows the `Generic code quality specialist — <focus>.` pattern.
   - The generic baseline `content.md` routing table has one added code-quality line, and its existing lines are unchanged.
6. `UNIVERSAL_CODE_REVIEW_AREAS = setOf("code-quality")` is defined beside `APPROVED_CODE_REVIEW_AREAS`, and `APPROVED_CODE_REVIEW_AREAS` is unchanged. The manifest area check in `ShellContentLoaderManifestFieldParsing` accepts approved ∪ universal areas, and a test asserts `code-quality` is accepted while an unknown area is still rejected. The `codeReviewArea` enum in `orchestration/contracts/platform-pack-schema.yaml` includes `code-quality`, with no `contract_version` change. The following stay on `APPROVED_CODE_REVIEW_AREAS` only, so no new or existing non-generic pack is required or scaffolded to cover `code-quality`:
   - `PlatformPackSubstanceAuditPointerCatalog`
   - the new-pack scaffolder selection (`ScaffoldPayloadMapPlatformPackPolicy`, `ScaffoldServicePlanning`, `ScaffoldCatalog`, `ScaffoldContract`)
7. The review-skill structure validation resolves the `code-quality` area. That means skill-name area derivation in `ReviewSkillStructureValidatorFrontmatterRules`, plus `reviewAreaRule`/`canonicalSeverityCloser` in `ScaffoldContentStarters` and the area label map in `ScaffoldTemplateRendering`. For `code-quality`, the required closer is `- Report every code-quality finding as Minor; never Blocker or Major.`. A test asserts that a code-quality specialist whose final rule is the Blocker/Major closer is rejected.
8. `orchestration/review-orchestrator/review-skill-structure-standard.md` has a Code-Quality Lane subsection. It states fixed Minor severity, the cap of 5, exemption from the reachable-failure rule, and the code-quality closer.
9. The "Shared Contract For Every Specialist" section in `orchestration/review-orchestrator/specialist-contract.md` and in `orchestration/review-orchestrator/PLAYBOOK.md` contains one added, byte-identical bullet. It names the code-quality lane as the only exception to the meaningful-issue, style-nit and Minor-tie rules, and states the Minor-only and cap-of-5 discipline. Every pre-existing bullet in both files is byte-identical to before.
10. Lane composition in `ReviewLaunchPlanComposition.kt` behaves as follows:
    - `composeReviewLaunchAreas` adds each universal area that the fallback owner declares.
    - `flattenReviewLaunchPlan` keeps every lane the routed graph owns exactly as before (same skill, pack, area, order index, required flag and origin chain). For each selected universal area the graph does not own, it appends one lane from the fallback owner:
      - skill name `bill-<fallback>-code-review-code-quality`
      - `required = true`
      - origin chain `[routedSlug, fallbackSlug]`
      - depth = existing max depth + 1
      - add-ons from `ReviewAddonSelectionPolicy.select`
      - order index continuing after existing lanes
    - When no fallback owner exists, or the fallback does not declare the area, it appends nothing and does not throw.
11. `ReviewLaunchPlanPolicyTest` (runtime-domain) has tests for four cases:
    - KMP routed with the Kotlin layer and generic installed: the plan contains the generic code-quality lane as the last, required lane, and every other lane equals the plan built from the same manifests with generic not declaring `code-quality`. This is the regression criterion.
    - Generic routed: exactly one code-quality lane.
    - No fallback installed: no code-quality lane and no exception.
    - Two routed roots: one reconciled generic code-quality lane, with no ownership tie.
12. `ComposedReviewLaunchPlanTest` (repoTest) expects `code-quality` → `bill-generic-code-review-code-quality` in the KMP and Kotlin area maps. The authoring render snapshots `bill-kmp-code-review.render.txt` and `bill-kotlin-code-review.render.txt` include the appended lane.
13. `platform-packs/kotlin/code-review/bill-kotlin-code-review/code-quality-idioms.md` exists with concrete Kotlin idiom examples: `orEmpty()` vs `?: ""`, `emptyList()`, `let`/`run`/`also`/`apply` guidance, and data-class/sealed state. The Kotlin baseline `content.md` and the Kotlin specialist files are unchanged. The generic lane rubric states that it reads `code-quality-idioms.md` from the routed pack's or a baseline layer's code-review baseline directory when present, and works without it.
14. No existing specialist `content.md` under `platform-packs/` changes.

## Implementation Steps

1. **Constants.** Add `UNIVERSAL_CODE_REVIEW_AREAS` in `runtime-domain/.../scaffold/policy/ScaffoldPolicyConstants.kt`.
   - Assumption to confirm: `review/plan` may import `scaffold/policy`. If a package rule forbids it, define the constant in the `review/plan` (or shared review model) package and reference it from scaffold policy instead. Do not duplicate the literal.
2. **Manifest acceptance and validator exceptions.** Edit these:
   - the schema enum
   - `ShellContentLoaderManifestFieldParsing.kt` (~line 93)
   - `ReviewSkillStructureValidatorFrontmatterRules.kt` (~line 13, longest-suffix derivation over approved ∪ universal)
   - `ScaffoldContentStarters.kt`: the `reviewAreaRule` `when` (~line 152), `canonicalSeverityCloser` (~line 149) and the starter text at ~:89/:208, but only where a `when` must be exhaustive
   - `ScaffoldTemplateRendering.kt` (~line 21, label "code quality")

   Keep every other `APPROVED_CODE_REVIEW_AREAS` call site unchanged.
   - Assumption to confirm: if the substance audit, `ReviewSkillStructureValidatorContent`, install planning or any other validator rejects a declared area outside the approved set, extend only that check to accept universal areas. Do not loosen checks on approved areas.
3. **Lane rubric.** Author the `content.md` per criteria 1-4. Read `PlatformPackSubstanceAuditPolicy`/`PolicyCatalog` thresholds and meet them for the generic pack. Rule ids and exact wording are implement's choice, and rule groups can follow the four focus families plus Conventions and Finding Discipline. Calibrate the state-modelling rule with the PR #3110 examples. Wire the generic `platform.yaml`, `agents.yaml` and baseline routing line, appending `code-quality` last in `declared_code_review_areas` so existing generic lanes keep their order indexes.
4. **Shared contract and standard.** Add the carve-out bullet at the end of "Shared Contract For Every Specialist", identically in `specialist-contract.md` and `PLAYBOOK.md`. Then add the Code-Quality Lane subsection to `review-skill-structure-standard.md`.
5. **Composition.** Implement criterion 10 in `ReviewLaunchPlanComposition.kt`.
   - Keep the early return on empty `selectedAreas`.
   - Assumption to confirm: every caller (`ParallelCodeReviewRunnerRubricPlanning.resolveWithRoutedManifests`, `FileSystemReviewAttribution`, `ShellContentLoaderComposition`, `FileSystemNativeAgentPlannedWorkerValidation`, and the `ReviewLaunchPlanPolicy` wrappers) derives `selectedAreas` from `composedAreas`, so the lane appears without further caller edits. If a caller narrows areas intentionally, such as an explicit area selection or a rerun, keep that narrowing; the lane only joins when `code-quality` is selected. Record any caller that cannot include it.
   - Confirm `ReviewPerAreaFallbackExclusion` and `ReviewCrossRootLaneReconciliation` keep the appended lane: there is no native code-quality lane, and the same owner across roots is not a tie.
   - Assumption to confirm: install planning always installs the generic pack alongside other packs (`InstallPlanBuilder`/`InstallPlatformPackDiscoverySnapshots`). If not, the append is simply skipped.
6. **Sidecar.** Add `code-quality-idioms.md` to the Kotlin baseline directory.
   - Assumption to confirm: validators and install accept an extra `.md` in a baseline skill directory (`authoredSidecarViolations` skips baseline dirs), and the reviewer can read the installed file. If a validator rejects it, move the file to a location validators accept and update the rubric's lookup sentence. If the launch cannot surface the file to the reviewer, keep the file and convention and note the limitation in the rubric; the lane must work without it either way.
7. **Tests and snapshots.**
   - Add the `ReviewLaunchPlanPolicyTest` cases (criterion 11).
   - Update the `ComposedReviewLaunchPlanTest` expectations, and refresh the two `AuthoringRender` snapshots plus any other render or install snapshot that lists generic or composed lanes.
   - Add the loader acceptance/rejection test (criterion 6) and the closer rejection test (criterion 7).
   - Update `ReviewSkillStructureConformanceTest`, `PlatformPackSubstanceAuditRepoTest`, `ScaffoldReviewStructureAcceptanceTest`, `ScaffoldServiceParityTest`, `InstallPlanBuilderTest`, `FileSystemReviewAttributionTest`, `ParallelCodeReviewRunnerTest` and `ParallelReviewLaneDispositionTest` only where they enumerate generic areas or lane sets. Those updates are additive expectation changes, not new tests.

## Test Obligations

- `ReviewLaunchPlanPolicyTest`, KMP + generic: catches the lane missing from KMP reviews and any change to existing lanes (regression criterion).
- `ReviewLaunchPlanPolicyTest`, generic routed: catches a duplicate `(packSlug, area)` that would violate the plan uniqueness invariant.
- `ReviewLaunchPlanPolicyTest`, no fallback: catches a throw or an orphan lane when generic is not installed.
- `ReviewLaunchPlanPolicyTest`, two roots: catches an `ambiguousLaneOwnership`/cross-root tie on the shared generic lane.
- Manifest loader test: catches the area check being loosened to accept any area.
- Closer rejection test: catches the code-quality exception skipping the severity-closer check entirely.

## Non-Goals

- Merger, risk-register section output and telemetry category; subtask 2 owns these.
- Editing any existing specialist rubric, the Kotlin/KMP baseline `content.md`, or any non-generic `platform.yaml`.
- Adding `code-quality` to `APPROVED_CODE_REVIEW_AREAS`, scaffolding it into new packs, adding manifest keys, or bumping contract versions.
- The horizontal no-pack-installed review path (documented limitation in the parent spec).

## Dependency Notes

None. This subtask is independent of subtask 2. Today's merger already accepts findings from any lane, so the new lane's findings parse and merge before subtask 2 lands. Apply every rule to the tree as present.

## Validation Strategy

The validate phase runs `./gradlew check`. That covers spotless, detekt, unit tests, repoTests including `ReviewSkillStructureConformanceTest` and the substance audit, and agent-config validation. Run spotless in a local clone, not a linked worktree. Implement and audit inspect the tree only.

## Next Path

```bash
skill-bill goal SKILL-413
```
