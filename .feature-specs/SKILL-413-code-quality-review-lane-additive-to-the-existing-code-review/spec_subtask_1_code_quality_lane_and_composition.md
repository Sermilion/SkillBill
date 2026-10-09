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

## Implementation Details

This ordered plan replaces the provisional Implementation Steps and Test Obligations with decisions from the upstream preplan digest. AC references below refer to this sub-spec's numbered acceptance criteria. Paths are repository-relative. Implement produces the authored files, Kotlin changes, test cases and snapshot expectations. Validate owns test execution, snapshot verification, formatting, static analysis and the full repository gate. This plan phase executes none of them.

### 1. Accept universal areas without expanding required pack coverage

Serves AC-006 and establishes the vocabulary needed by AC-007 and AC-010.

- Add `UNIVERSAL_CODE_REVIEW_AREAS = setOf("code-quality")` beside the unchanged approved set in `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/scaffold/policy/ScaffoldPolicyConstants.kt`. Use that owner from composition and validation rather than duplicating the vocabulary or introducing a forwarding constant.
- Add `code-quality` to `$defs.codeReviewArea` in `orchestration/contracts/platform-pack-schema.yaml`. Keep contract version `1.8` and every existing enum value. Update the description to distinguish accepted declared areas from the approved coverage set.
- In `runtime-kotlin/runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/scaffold/platformpack/loader/ShellContentLoaderManifestFieldParsing.kt`, change only the membership check in `parseDeclaredAreas` to accept approved plus universal areas. Preserve declaration/file/metadata/lane-condition coherence and typed `INVALID_MANIFEST_SCHEMA` failures for unknown areas.
- Keep `PlatformPackSubstanceAuditPointerCatalog`, `ScaffoldPayloadMapPlatformPackPolicy`, `ScaffoldServicePlanning`, `ScaffoldCatalog` and `ScaffoldContract` on the approved set alone. Universal acceptance does not require or scaffold a new specialist in non-generic packs.
- Update enum parity in `PlatformPackSchemaContractVersionTest` to approved plus universal, retaining its pinned-version assertion. Add one conforming code-quality declaration case in `PlatformPackSchemaViolationsTest`; retain the existing unknown `laravel` rejection and failure-code assertion. The realistic bugs are a valid declaration failing at the loader boundary and a widened check silently accepting arbitrary areas. These are governed contract tests and remain required.

### 2. Add the quality-specific structure rule and shared exception

Serves AC-001, AC-004, AC-007, AC-008, AC-009 and AC-014.

- In `runtime-kotlin/runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/scaffold/validation/review/ReviewSkillStructureValidatorFrontmatterRules.kt`, extend only the longest-suffix area derivation in `declaredAreaForFile` to approved plus universal areas. Preserve declared-file mapping precedence.
- In `runtime-kotlin/runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/scaffold/rendering/ScaffoldContentStarters.kt`, add the code-quality `reviewAreaRule` and make `canonicalSeverityCloser` return the exact quality closer. Add its phrase to `AREA_DESCRIPTION_PHRASES` in sibling `ScaffoldTemplateRendering.kt`. Existing area rules and closers remain unchanged.
- Keep the existing H2/H3, backticked-id, obligation-verb, failure-wording and final-rule checks in `ReviewSkillStructureValidatorContent` and `ReviewSkillStructureSeverityRules`. Do not add a severity legend or bypass validation for the new lane. The quality closer is exactly `- Report every code-quality finding as Minor; never Blocker or Major.`
- Add the Code-Quality Lane subsection to `orchestration/review-orchestrator/review-skill-structure-standard.md`. State fixed Minor severity, the cap of five, exemption from reachable-failure requirements and the exact closer.
- Append this one bullet, byte-identically, to `Shared Contract For Every Specialist` in `orchestration/review-orchestrator/specialist-contract.md` and `orchestration/review-orchestrator/PLAYBOOK.md`: `- The code-quality lane is the only exception to the meaningful-issue, style-nit and Minor-tie rules; report its findings as Minor only, with at most 5 per review.` Preserve every pre-existing bullet and leave `Shared Report Structure` to subtask 2.
- In `ReviewSkillStructureConformanceTest`, add a conforming quality fixture and reject the same area with the old Blocker/Major final closer. The realistic bug is recognizing the area while silently allowing the failure-lane severity contract. Validate also runs `SpecialistContractParityTest` and the existing `ScaffoldReviewStructureAcceptanceTest`; do not broaden scaffolder coverage to universal areas.

### 3. Author and wire the generic specialist

Serves AC-001 through AC-005 and AC-014.

- Create `platform-packs/generic/code-review/bill-generic-code-review-code-quality/content.md` with frontmatter `name`, `description` and `internal-for: skill-bill`. Use the required H2 order, Focus, Ignore, Applicability, Project-Specific Rules. Put backticked rule ids in H3 rule groups and end the rules with the exact quality closer from step 2.
- Cover language/stdlib idioms, scope/helper functions, unified state and lifecycle modelling, and structure. Include `orEmpty()`, `emptyList()` and context-sensitive `let`, `run`, `also` and `apply` examples. Derive recommendations from the detected language and standard library without requiring a selected platform pack.
- Include the supplied PR #3110 calibration verbatim in its relevant identifiers. `CustomFreeTextViewModel` has editor fields `textContent` and `isLoaded`, and the jointly written fields `isSaved`, `isInDatabase`, `savedText`, `createdAt` and `isDeletingOwnEntry` in `onSavedEntry` and `persist`. `VisitDetailViewModel` has lifecycle fields `hasSeenVisit`, `hasCreatedVisit`, `isDeletingVisit` and `isVisitRemoved`. Use these to explain unified state types and sealed types or enums, not to report findings against unchanged code.
- Require concrete smell, `file:line` and concrete rewrite for every finding, Minor only, at most five findings, and no auto-fixing. Exempt only this lane from reachable-failure proof. Exclude unchanged code, formatting, configured ktfmt/detekt/eslint/prettier/swiftformat rules and behavioural defects belonging to other lanes. Read available repository guides and tooling configuration, including `docs/code-quality-best-practices.md`, `AGENTS.md` and `CLAUDE.md`; project conventions win over generic preferences.
- Meet the existing substance audit, at least ten substantive rules and three clusters, with the shared-shingle and corresponding-rubric limits unchanged at 35 and 65 percent. Use recognized H3 rule/check/requirement/failure/correctness headings and concrete state/lifecycle, contract/data and resource/toolchain evidence. Obligation and consequence wording must cover real constraints such as preserving behaviour during a suggested rewrite and avoiding duplicate tooling findings. It must not impose reachable behavioural failure as a condition for a quality finding.
- In `platform-packs/generic/platform.yaml`, append `code-quality` to declared areas; add its declared file, bespoke focus metadata, required lane condition and specialist-contract pointer. Add one routing-table line in `platform-packs/generic/code-review/bill-generic-code-review/content.md`. Preserve all its existing lines.
- Add `bill-generic-code-review-code-quality` to `platform-packs/generic/code-review/bill-generic-code-review/native-agents/agents.yaml`. Match the required description pattern and manifest focus exactly, retaining contract version `0.1`, `compose: governed-content` and the existing review-evidence tool pair. Generated pointers and provider-agent outputs are not authored or committed.
- Narrowly adjust generic physical-area expectations in `PlatformPackSubstanceAuditRepoTest` to approved plus universal areas while retaining coverage and substance assertions. Validate uses existing substance, structure and agent-config checks; do not add prose-string tests that duplicate these governed checks.

### 4. Ship the optional Kotlin sidecar through the existing adapter

Serves AC-003 and AC-013, while preserving AC-014.

- Create `platform-packs/kotlin/code-review/bill-kotlin-code-review/code-quality-idioms.md` with concrete `orEmpty()` versus `?: ""`, `emptyList()`, scope-function clarity guidance and data-class/sealed-state examples. Do not edit the Kotlin baseline or any existing specialist.
- The generic rubric describes lookup in the routed pack's code-review baseline directory or a baseline layer's baseline directory. It uses the sidecar when supplied or accessible through the authorized launch, and derives idioms itself when the file is unavailable. Explicitly document that current launch-provided rubrics do not append optional sidecar bodies and workers cannot perform unrestricted filesystem discovery. Do not add a manifest key, required-companion declaration or broader worker evidence access.
- Resolve the known installation conflict in `runtime-kotlin/runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/install/staging/InternalSidecarTarget.kt`. Allow the exact optional filename `code-quality-idioms.md` to omit an explicit owning-content link only in code-review baseline directories. Keep explicit links mandatory for every other companion. Preserve all existing path, symlink, count, reserved-name and collision checks, including collisions caused by flattening companions from multiple packs into the parent directory.
- Document this narrow source exception in `orchestration/review-orchestrator/review-skill-structure-standard.md` and `docs/skill-source-generation.md`. Do not relocate the file, relax companion rules generally or change the baseline content to link it.
- Extend `runtime-kotlin/runtime-infra/skills/src/test/kotlin/skillbill/infrastructure/skills/install/InternalSkillCompanionInstallApplyTest.kt` with a baseline fixture that stages this sidecar without a content link and a rejection case for an unrelated unlinked companion. The realistic bugs are losing the optional guidance during staging and accepting arbitrary unlinked Markdown. Retain existing deletion-restoration and parent-name collision coverage. These are fixture tests for validate, not an operational installation step.

### 5. Compose universal lanes after the existing routed lanes

Serves AC-010 and AC-011.

- In `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/plan/ReviewLaunchPlanComposition.kt`, use `ReviewFallbackResolver.resolveOptional` to find the manifest-declared fallback owner. `composeReviewLaunchAreas` adds only universal areas that this owner declares. Do not hard-code generic or add generic as a baseline layer.
- Preserve `flattenReviewLaunchPlan`'s empty-selection return and intentional explicit-area narrowing. Build existing routed graph lanes through the current candidate, cycle, compatibility and nearest-owner rules. Resolve universal lanes as a trailing group so alphabetic `code-quality` ordering cannot shift existing lanes, including when generic itself is routed.
- For a selected universal area not owned by the routed graph, append exactly one fallback lane with the declared skill, required status, origin chain `[routedSlug, fallbackSlug]`, depth one beyond the existing maximum, next contiguous index, add-ons from `ReviewAddonSelectionPolicy.select`, preserved path/content signals and an explicit inclusion reason. A graph-owned quality lane keeps its owner, required status and provenance and appears once.
- No fallback, or a fallback without the declaration, is optional absence and produces no appended lane. Preserve the resolver's typed failures for malformed multiple ownership or a fallback lacking its baseline. Do not swallow those failures.
- In `ReviewCrossRootLaneReconciliation.reconcile`, use a trailing universal group only where needed to prevent its depth/pack/area sort from shifting failure-lane indexes. Keep existing non-universal ordering, scope and provenance reconciliation. `ReviewPerAreaFallbackExclusion.partition` continues removing only areas with native ownership; same fallback ownership across two roots must reconcile without a tie.
- The digest confirms that composition consumers already use composed selection and that installation planning already includes the declared fallback. No forwarding-wrapper or installation-policy changes are planned. Keep `ParallelCodeReviewRunnerRubricPlanning`, `FileSystemReviewAttribution`, `ShellContentLoaderComposition` and `FileSystemNativeAgentPlannedWorkerValidation` behaviour, including intentionally narrowed selections.

### 6. Prove composition boundaries with a small regression set

Serves AC-010, AC-011 and AC-012.

- Extend `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/plan/ReviewLaunchPlanPolicyTest.kt`; explicitly set fallback capability in its manifest fixtures. Compare complete non-quality lanes with quality availability removed, including skill, pack, area, order index, required flag, depth, signals, add-ons and origin chains.
- Cover KMP with its Kotlin layer and generic, and Kotlin with generic. The realistic bug is a missing required universal lane or any alteration to existing failure lanes. Assert the appended lane is last and has all required fields.
- Cover generic as root with one trailing quality lane and unchanged failure lanes. This catches duplicate ownership and alphabetic index drift.
- Cover optional absence with no fallback and with a fallback lacking the declaration, using one parameterized boundary case where appropriate. Assert no quality lane and no exception. Preserve existing malformed-fallback failure coverage and empty-selection coverage; add a focused case only if these branches lack coverage. Explicit selection excluding quality must continue omitting it.
- Cover two roots with the same generic fallback, asserting one reconciled quality lane, no ownership tie and unchanged failure lanes. This catches cross-root duplicate ownership and index drift without testing internal call order.
- In `runtime-kotlin/runtime-infra/skills/src/repoTest/kotlin/skillbill/scaffold/ComposedReviewLaunchPlanTest.kt`, use composed selection for the KMP and Kotlin maps and expect `code-quality` to resolve to `bill-generic-code-review-code-quality`. Keep deliberately approved-only selections as coverage that the universal lane is omitted when not selected.
- Update expected authored renders in `runtime-kotlin/runtime-infra/skills/src/test/resources/snapshots/scaffold/bill-kmp-code-review.render.txt` and `bill-kotlin-code-review.render.txt` to include the appended lane. Validate executes deterministic render verification through `AuthoringRenderSnapshotTest`; snapshot changes belong with composition changes.
- Adjust existing enumerated-lane expectations only when required by the additive lane. Do not invent new glue tests or weaken assertions to make them pass. Preserve governed parity and validator-backed tests.

### 7. Check end states and hand validation evidence to its owning phase

Serves all acceptance criteria, especially AC-006, AC-009 and AC-014.

- Implement keeps changes confined to this subtask's authored guidance, declared manifest/native-agent wiring, narrow structure/schema/companion acceptance, pure domain composition and necessary regression fixtures/snapshots. Shared report layout, merger normalization/capping and telemetry remain subtask 2's work. No new module, port, manifest key, contract version, database migration or telemetry event is needed.
- Preserve existing specialist files, Kotlin/KMP baseline content, non-generic manifests, approved-area coverage, existing shared-contract bullets and failure-lane values. Required review and validation repairs may change relevant production wiring, test setup, formatting or lint while preserving these explicit operator constraints. If a repair conflicts with a preserved file or contract, report that concrete conflict rather than weakening the constraint.
- Apply architecture rules A1, A2, A5, A6, A7, A10, A11 and A12 from the digest. Keep composition pure in domain and filesystem exceptions in the adapter. Use narrow helpers and imported simple Kotlin names, with no authored line/block comments, no guard exemptions and no increase in baseline debt. Preserve package and file limits.
- Validate owns the existing full gate stated in Validation Strategy, including unit/repo tests, spotless, detekt, schema parity, specialist parity, structure/substance checks, deterministic snapshots and native-agent configuration. Preserve the local-clone spotless requirement and configuration-cache retry guidance. This phase and implement do not run compilation or tests for proof; buildability proof belongs only to an authorized build phase.
- Audit inspects every criterion against repository end states. History, commit, push, PR, monitoring and installed-runtime synchronization remain with their owning phases or parent runtime. No such operation is part of this child plan.

### Settled assumptions and delivery limits

- Tracker comments are unavailable. The supplied specs and preplan digest are sufficient authority, including the PR #3110 field names; implement must not invent extra calibration facts.
- The sidecar location remains exactly the required Kotlin baseline path. Its narrow companion exception resolves source staging, but it does not make the body automatically available to workers. The rubric documents authorized-launch availability and language-derived fallback; delivery integration is not silently assumed.
- For a graph with no existing selected lanes, use depth zero as the first appended lane's depth, equivalent to an empty maximum of minus one. The digest does not state the current empty-depth convention. Implement confirms this against the existing lane model without changing the empty-selection return or other lane depths.
- Native workers need the newly authored generic agent in the installed runtime before they can execute the new lane. Synchronization is parent-owned and is not a prerequisite for finishing this plan or for authoring the repository end state.

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
