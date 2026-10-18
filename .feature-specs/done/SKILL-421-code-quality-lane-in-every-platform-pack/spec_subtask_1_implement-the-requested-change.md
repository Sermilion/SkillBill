# SKILL-421 Subtask 1 - Implement the requested change

Parent spec: [.feature-specs/SKILL-421-code-quality-lane-in-every-platform-pack/spec.md](spec.md)
Issue key: SKILL-421

## Scope

# SKILL-421 Code-quality lane in every platform pack

## Outcome

Every shipped platform pack owns and declares a code-quality specialist with guidance for its language and frameworks. A routed review uses that specialist rather than relying on the generic fallback to supply code-quality coverage. Composition selects exactly one code-quality specialist, including for KMP's Kotlin baseline composition.

The lane keeps SKILL-413's finding discipline, report section, and telemetry category. Its findings remain non-blocking, Minor severity, and capped at five per review. Existing failure lanes retain their behavior.

## Problem

SKILL-413 landed a generic code-quality lane that composition appends to routed reviews. Only `../../../platform-packs/generic/platform.yaml` declares it. Other packs have no owned specialist, and Kotlin's optional idioms sidecar is not automatically included in every worker launch. Pack authors and operators cannot inspect a platform-specific code-quality lane alongside the pack's other specialists.

The current composition already supports a graph-owned universal area taking precedence over the fallback. This change uses that ownership model and closes authoring, registration, delivery, and validation gaps without building a second review composition mechanism.

## Scope

- Pack-owned code-quality specialists and manifest registrations in every shipped pack.
- Provider-neutral native-agent declarations, launch-provided rubrics, installed sidecars, and provider-native generation for those specialists.
- Composition and cross-root reconciliation that select one code-quality lane with the correct owner.
- Scaffolding and maintained-pack coverage checks that prevent newly authored packs from omitting the lane.
- Documentation, routing fixtures, rendered snapshots, installation, and focused regression coverage.

The shipped packs at specification time are `generic`, `go`, `ios`, `kmp`, `kotlin`, `php`, `python`, `rust`, and `typescript`. This inventory describes current scope; discovery and validation remain manifest-driven rather than embedding this list in runtime routing.

## Pack content

Each pack owns `platform-packs/<slug>/code-review/bill-<slug>-code-review-code-quality/content.md`, declared with `internal-for: skill-bill`. Register `code-quality` in the pack's declared areas, area file mapping, metadata, required lane condition, and any other existing manifest surfaces needed for rubric delivery and native-agent generation. Generic keeps its existing specialist and fallback role.

Every specialist covers idiomatic constructs, helper clarity, cohesive state, duplication, redundant branches, dead code, responsibility size, and concrete naming problems. It follows project conventions over generic preference and ignores matters already enforced by configured linters or formatters. Each finding names a concrete maintainability cost, a location, and a behavior-preserving rewrite. A reachable behavioral failure is not required.

Platform guidance must be substantive. Changing the generic rubric's name is insufficient. Examples should use the pack's language and relevant framework idioms, while avoiding duplication of behavioral findings owned by other specialists:

| Pack | Guidance emphasis |
|---|---|
| Generic | Language-derived idioms and project conventions when no concrete platform owns the lane. |
| Go | Clear error handling, small interfaces, value and pointer choices, explicit ownership, and standard-library operations. |
| iOS | Swift value modelling, optional handling, enums, focused extensions, SwiftUI state clarity, and readable task ownership. |
| KMP | Kotlin idioms in Android and multiplatform code, Compose state cohesion, clear Flow usage, and expect/actual organization. |
| Kotlin | Kotlin/JVM idioms, collection and null handling, sealed lifecycle state, coroutine structure, and backend framework conventions where detected. |
| PHP | PHP type and value handling, focused classes, generators, and detected Symfony or Laravel conventions. |
| Python | Python data modelling, context managers, comprehensions where readable, focused functions, and detected framework conventions. |
| Rust | Ownership clarity, enums, iterators, pattern matching, error composition, and avoiding unnecessary clones or indirection. |
| TypeScript | Discriminated unions, narrowing, async control flow, module structure, and detected UI or server framework conventions. |

KMP owns an authored `bill-kmp-code-review-code-quality` specialist even though it composes Kotlin for other review areas. Its local specialist wins over Kotlin's inherited code-quality lane. Language and framework guidance reaches the actual reviewer through the authorized launch packet; workers do not need unrestricted filesystem reads to discover idiom files. Incorporate relevant Kotlin idioms into the owned rubric or an existing contract-authorized required companion. Do not depend solely on the optional sidecar or introduce an unauthorized sibling guidance file.

## Composition and compatibility

Resolve code-quality ownership through existing manifest composition. The nearest declared owner wins, preserving current ambiguity and missing-layer rules. Append generic code-quality only when the routed composition has no owner and the installed fallback declares it. Never append generic alongside a selected platform-owned code-quality lane.

The lane remains required and additive in every shipped pack. Selection heuristics cannot drop it solely because the diff lacks a particular framework token. A review with no applicable code can report no code-quality findings without inventing work.

Non-code-quality lane identity, ownership, order, requiredness, routing signals, add-on selection, and failure findings remain unchanged. Cross-root reconciliation must preserve one selected code-quality lane without allowing duplicate findings from an inherited or fallback owner. Inline review receives the selected platform guidance; delegated review launches the matching specialist. Standalone review, workflow review, and verify's existing code-review evaluator use the same composition behavior.

Older or external custom packs without a local code-quality specialist continue using an installed manifest-declared fallback when available. Absence of both a local owner and a fallback retains the existing documented behavior rather than causing an undocumented runtime refusal. Shipped-pack coverage and new-pack scaffolding enforce the new authored default without hard-coding runtime slugs or silently rewriting user-owned packs.

Do not extend `APPROVED_CODE_REVIEW_AREAS` merely to force a substance-audit count. Code-quality is already represented by `UNIVERSAL_CODE_REVIEW_AREAS` and accepted by the platform manifest schema. Keep one owner for that vocabulary and enforce local ownership through the appropriate maintained-pack checks. Change canonical schemas and versions only if implementation proves a wire-shape change is required.

## Findings and reporting

Keep the existing separate code-quality risk-register section after failure findings, sequential F-ids, specialist attribution, and `code_quality` telemetry category. Deterministic merging normalizes all platform code-quality findings to Minor and enforces the five-finding cap across the whole review, not five per provider or composed layer.

Failure findings and code-quality findings remain separate during deduplication. Code-quality cannot suppress a behavioral finding or change the review verdict. Report-only standalone review and verify do not auto-fix code-quality findings. Existing workflow repair rules are not broadened by this change.

## Acceptance criteria

1. Every currently shipped platform pack declares and owns a structure-conformant `bill-<slug>-code-review-code-quality/content.md` specialist. Generic remains the fallback owner. KMP has its own authored specialist rather than satisfying coverage only by inheriting Kotlin's.
2. Each non-generic rubric contains substantive language and framework guidance appropriate to its pack, covers the common maintainability concerns, follows repository conventions, and proposes behavior-preserving rewrites without requiring a reachable failure. Maintained-pack substance validation rejects a missing or generic-only renamed rubric.
3. Each pack registers the specialist through the existing manifest-owned declared area, file, metadata, requiredness, and native-agent surfaces. Invalid declarations, missing content, or missing required sections fail through existing typed validation boundaries.
4. Review composition for each shipped pack selects exactly one code-quality lane owned by that pack. KMP selects its own lane over Kotlin's baseline lane; Kotlin selects its own rather than generic. Existing graph ambiguity rules remain enforced.
5. A legacy custom pack with no code-quality owner gets the installed generic fallback when available. A pack with its own owner works without generic installed. The absence of both retains documented compatibility behavior and emits required degradation records.
6. Inline and delegated reviews receive the selected owner's full authorized rubric, including required language guidance. No selected lane relies on an optional file that its launched worker cannot access.
7. Generated provider-native agents and installed internal sidecars include the new specialist for every selected pack. Native-agent preflight verifies the selected owner and does not silently launch the generic agent instead. Removed packs leave no stale managed links.
8. Standalone review, workflow review, and verify's code-review evaluator use the same selected code-quality owner. The dispatcher still exposes only `/skill-bill`; new specialists remain unlisted sidecars and native-agent inputs.
9. Merging platform-owned findings preserves Minor severity, an aggregate cap of five, a separate code-quality report section, `specialist=` attribution, and `code_quality` telemetry. Existing failure findings, verdicts, and the `quality` telemetry alias retain their meanings.
10. Non-code-quality lane sets and ownership are identical before and after this change for every shipped composition. Adding local code-quality ownership does not union generic's other lanes into platform reviews or change existing routing signals.
11. New full-pack scaffolding includes an owned, required code-quality specialist using the existing dynamic authoring model. Authored stubs still require meaningful filling and validation before shipping. Existing custom packs are not rewritten or made invalid solely for lacking the new local lane.
12. Maintained-pack coverage checks discover packs through manifests and reject removal of a shipped pack's local code-quality declaration or source. Tests prove rejection through the actual validator, not a copy of its algorithm. Source generation retains `content.md` authority and commits no generated pointers or provider-specific outputs.
13. Installation, render snapshots, catalogs, and docs describe the new local ownership accurately. Runtime resume retains accepted assignments or uses an explicitly checked compatibility mapping; adding a local lane cannot silently substitute an owner midway through an accepted review.

## Non-goals

- Adding or changing behavioral failure lanes, their Ignore lists, or their severity rules.
- Requiring a code-quality finding on every review or repeating formatter and linter findings.
- Making code-quality findings blocking, raising their cap, or changing review repair policy.
- Making generic a baseline layer of every pack.
- Automatically editing external user-owned packs.
- Extending the horizontal review path when no platform pack is installed.
- A new review orchestrator, transport framework, telemetry event, or database schema without a demonstrated requirement.

## Constraints and settled decisions

This spec supersedes SKILL-413's generic-only lane ownership and its non-goal of adding specialists to non-generic packs. It preserves SKILL-413's additive behavior and finding discipline. Implementation records that supersession in the owning `../../../agent/decisions.md` and verifies retained SKILL-413 criteria after the change.

Use existing nearest-owner composition and universal fallback handling. Delete obsolete generic-only assumptions in tests or docs instead of keeping contradictory assertions. Do not widen architecture exceptions, substance-audit exemptions, or source-generation permissions to ship the new rubrics.

Apply `../../../docs/runtime-command-guidance.md`, `docs/skill-source-generation.md`, `docs/architecture-guidelines.md` A1 through A12 and G1 through G7, `runtime-kotlin/ARCHITECTURE.md` Design Principles, `docs/code-principles.md`, and `docs/observability-policy.md`. Pack behavior stays manifest-owned. Runtime declarations and wire vocabulary keep one owner. Kotlin comments follow repository restrictions. Required schema changes land in canonical contracts first and fail loudly at parse seams.

Base branch is `main`, which contains the SKILL-380 and SKILL-413 merges. This request creates requirements only and does not start implementation or create a decomposition manifest. Planning preserves current SKILL-418 work and the SKILL-419/SKILL-420 QA specs.

## Validation strategy

Name the realistic regression before each new test. High-value checks cover local-owner precedence over generic, KMP precedence over Kotlin, exactly-one-lane reconciliation, pack-owned operation without generic, legacy custom-pack fallback, full rubric delivery to inline and delegated reviewers, required native-agent integrity, severity and aggregate cap, unchanged failure findings, and rejection of a shipped pack missing its specialist.

Reuse current composition, structure-conformance, substance-audit, merger, scaffold, native-agent, and installation tests where they already own these boundaries. Update snapshots only for intended code-quality additions. Use a manifest-discovered pack matrix rather than a runtime slug list. Run the repository's required maintainer checks and `./install.sh` for authored skill and generated-install changes. Verify installed specialist identity and content, rather than treating source presence as proof of launch availability.


Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection. Only an explicit unresolved tracker reference without requirements needs lookup through its connected tracker before planning. Use the returned requirements, not the URL title. If that lookup fails, block with the returned reason before implementation; never infer or substitute requirements.

## Acceptance Criteria

1. Every currently shipped platform pack declares and owns a structure-conformant `bill-<slug>-code-review-code-quality/content.md` specialist. Generic remains the fallback owner. KMP has its own authored specialist rather than satisfying coverage only by inheriting Kotlin's.
2. Each non-generic rubric contains substantive language and framework guidance appropriate to its pack, covers the common maintainability concerns, follows repository conventions, and proposes behavior-preserving rewrites without requiring a reachable failure. Maintained-pack substance validation rejects a missing or generic-only renamed rubric.
3. Each pack registers the specialist through the existing manifest-owned declared area, file, metadata, requiredness, and native-agent surfaces. Invalid declarations, missing content, or missing required sections fail through existing typed validation boundaries.
4. Review composition for each shipped pack selects exactly one code-quality lane owned by that pack. KMP selects its own lane over Kotlin's baseline lane; Kotlin selects its own rather than generic. Existing graph ambiguity rules remain enforced.
5. A legacy custom pack with no code-quality owner gets the installed generic fallback when available. A pack with its own owner works without generic installed. The absence of both retains documented compatibility behavior and emits required degradation records.
6. Inline and delegated reviews receive the selected owner's full authorized rubric, including required language guidance. No selected lane relies on an optional file that its launched worker cannot access.
7. Generated provider-native agents and installed internal sidecars include the new specialist for every selected pack. Native-agent preflight verifies the selected owner and does not silently launch the generic agent instead. Removed packs leave no stale managed links.
8. Standalone review, workflow review, and verify's code-review evaluator use the same selected code-quality owner. The dispatcher still exposes only `/skill-bill`; new specialists remain unlisted sidecars and native-agent inputs.
9. Merging platform-owned findings preserves Minor severity, an aggregate cap of five, a separate code-quality report section, `specialist=` attribution, and `code_quality` telemetry. Existing failure findings, verdicts, and the `quality` telemetry alias retain their meanings.
10. Non-code-quality lane sets and ownership are identical before and after this change for every shipped composition. Adding local code-quality ownership does not union generic's other lanes into platform reviews or change existing routing signals.
11. New full-pack scaffolding includes an owned, required code-quality specialist using the existing dynamic authoring model. Authored stubs still require meaningful filling and validation before shipping. Existing custom packs are not rewritten or made invalid solely for lacking the new local lane.
12. Maintained-pack coverage checks discover packs through manifests and reject removal of a shipped pack's local code-quality declaration or source. Tests prove rejection through the actual validator, not a copy of its algorithm. Source generation retains `content.md` authority and commits no generated pointers or provider-specific outputs.
13. Installation, render snapshots, catalogs, and docs describe the new local ownership accurately. Runtime resume retains accepted assignments or uses an explicitly checked compatibility mapping; adding a local lane cannot silently substitute an owner midway through an accepted review.

## Non-Goals

- None

## Dependency Notes

Depends on: none
The full goal owns planning and execution of the supplied requirements.

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.

## Next Path

Complete the goal and prepare its pull request.

## Spec Path

.feature-specs/SKILL-421-code-quality-lane-in-every-platform-pack/spec_subtask_1_implement-the-requested-change.md

## Implementation Details

This plan covers the existing single subtask. AC-001 through AC-013 refer to the numbered acceptance criteria above. The upstream preplan digest is the only discovery evidence used here. Implement confirms the explicit assumptions below at the affected boundary; planning does not repeat discovery. Ship authoring, registration, coverage enforcement, rubric delivery, resume protection, and generation support together.

### 1. Preserve accepted review ownership before resolving replacement assignments

Serves AC-004, AC-006, AC-008, and AC-013.

Update `ParallelCodeReviewRunnerPlanning.prepare`, `ParallelCodeReviewRunnerLanePlanRecording.selectLaunchesForResume`, and `recordPlannedLanes` under `../../../runtime-kotlin/runtime-application`. Read recorded lane ownership before compiling replacement assignments. For an accepted review, preserve the recorded code-quality owner for both completed and incomplete lanes, resolve that exact owner's authorized rubric, and prevent a newly declared local lane from launching alongside it. New reviews use current manifest ownership. Keep revision, packet digest, selected-lane coverage, path, hunk, and lane-decision checks in `ReviewPreparationService.validateAgainstPacket`.

Use existing runtime-owned persistence and ports. Apply the same ownership rule to the durable inline rubric projection introduced in step 2. An unavailable recorded owner must fail through a checked typed boundary rather than substitute generic or a new local owner. No compatibility mapping is supported by the digest, so this plan chooses preservation.

Assumption for implement to confirm: existing recorded lane identity and runtime persistence can carry the inline selected-owner projection without a new wire shape. The digest does not name the inline persistence API or an unavailable-owner failure code. Select those at their existing owners. If a shape change proves necessary, change its canonical contract first and include contract-version parity and typed parse-failure coverage. Do not create an independent state store or silently accept legacy state that cannot establish ownership.

Test obligations: extend `ParallelCodeReviewStageResumeTest.kt` with an accepted generic-quality review upgraded to local ownership while sibling work remains unfinished, and with an incomplete accepted quality lane. The realistic bug is a duplicate local launch or substitution of the recorded generic owner on resume. Assert observable launched identities and retained accepted ownership. Cover unavailable recorded content failing explicitly, and durable inline resume retaining its owner, at the owning resume boundary. Reuse existing packet-integrity coverage rather than duplicate it.

### 2. Share selected-owner rubric preparation across delegated and inline entrypoints

Serves AC-004, AC-005, AC-006, AC-008, AC-010, and AC-013.

Factor an application-owned projection from the existing preparation path in `ParallelCodeReviewRunnerRubricPlanning.resolvePlannedRubrics`. Reuse routing, `ReviewLaunchPlanPolicy`, `ReviewCrossRootLaneReconciliation`, and rubric resolution without executing delegated workers. Resolve selected owners after accepted-owner preservation. Required lanes receive all authorized root-owned paths; retain empty-scope dropping, per-area fallback exclusion, cross-root ambiguity rejection, evidence boundaries, and horizontal behavior when no pack is routed.

Use `FileSystemReviewRubricResolver` through its existing port. Deliver the complete declared `content.md` plus only selected governed add-ons. Preserve containment checks and the 256 KiB bound. Inline and delegated delivery must identify the same surviving owner and include embedded language guidance. Keep delegated compiler identity checks and the full bodies in `ParallelCodeReviewRunnerParentPrompt`. Do not invoke `ParallelCodeReviewRunner`'s delegated execution path for inline review; its inline rejection remains valid.

Feed the projection into `InlineReviewStrategy.kt` and `InlineReviewDirective.compose`, `StandaloneReviewStrategies.kt`'s `standaloneDirective` and `launchInline`, and `VerifyCodeReviewStep.run` with `VerifyStepSequence.gatherDiff`. Wire explicit dependencies in `RuntimeFeatureTaskSlotProvides.kt` and `RuntimeOperationProvides.kt` under `../../../runtime-kotlin/runtime-core`. Preserve Opus strategy parity, one inline traversal, workflow mutation policy, standalone report-only behavior, and verify's read-only evaluator isolation.

Verify's diff artifact supplies checkpoint, comparison scope, and changed paths, not a raw patch. Obtain authorized changed-content evidence through the existing evidence ports before projecting rubrics. Assumption for implement to confirm: those ports can serve all three inline callers with their existing comparison scopes. Do not parse the path artifact as diff text or grant workers unrestricted filesystem discovery.

Record legacy generic-quality fallback and compatibility omission when neither graph nor installed fallback owns quality at this shared application preparation boundary. Preserve the current compatibility result, including omission rather than an invented runtime refusal. Use existing observability vocabulary and sinks; no new telemetry event is indicated.

Test obligations: extend `InlineReviewLaunchParityTest.kt`, `InlineReviewDirectiveTest.kt`, and `VerifyOperationTest.kt` to catch launches that claim platform ownership but omit its full language rubric, choose different owners across entrypoints, or treat verify's path projection as a patch. Assert launch-provided owner and content, report-only/read-only behavior, and resume ownership. Extend `FileSystemReviewRubricResolverTest.kt` for full local content delivery and retain containment/add-on coverage. At the shared preparation boundary, assert fallback and missing-fallback outcomes emit degradation records. Delegated outcome coverage must assert the matching launch identity and full body, not only a mock call.

### 3. Author and register each shipped pack's local specialist

Serves AC-001, AC-002, AC-003, AC-006, AC-007, AC-008, and AC-010.

Create `platform-packs/<slug>/code-review/bill-<slug>-code-review-code-quality/content.md` for `go`, `ios`, `kmp`, `kotlin`, `php`, `python`, `rust`, and `typescript`. Retain generic's existing owner and fallback role. This inventory defines authored work only; runtime and maintained coverage discovery remain manifest-driven.

Each source has matching `name` and `internal-for: skill-bill`. Follow `review-skill-structure-standard.md` with ordered H2 sections `Focus`, `Ignore`, `Applicability`, and `Project-Specific Rules`, H3 rule groups, and only the permitted optional trailing `Repo-Local Knowledge` section. Use the exact closer `- Report every code-quality finding as Minor; never Blocker or Major.` Cover idioms, helper clarity, cohesive state, duplication, redundant branches, dead code, responsibility size, and naming. Require changed-code locations, a concrete maintenance cost, and behavior-preserving rewrites. Follow repository conventions, defer behavioral defects to failure lanes, omit formatter/linter findings, and permit zero findings without requiring a reachable failure.

Write independent platform guidance rather than rename generic text. Go covers error-flow clarity, small interfaces, values/pointers, explicit ownership, and standard-library operations. iOS covers Swift values, optionals, enums, focused extensions, SwiftUI state, and task ownership. KMP covers Android and multiplatform Kotlin, Compose state cohesion, readable Flow operations, and `expect`/`actual` organization. Kotlin covers null/collection operations, sealed lifecycle state, coroutine structure, and detected JVM backend conventions. PHP covers types/values, focused classes, generators, and detected Symfony/Laravel conventions. Python covers data models, context managers, readable comprehensions, focused functions, and detected frameworks. Rust covers ownership, enums, iterators, matching, error composition, and unnecessary clones/indirection. TypeScript covers discriminated unions, narrowing, async flow, modules, and detected UI/server frameworks.

Incorporate relevant examples from Kotlin's existing `code-quality-idioms.md` into the Kotlin and KMP owned rubrics, including nullable-string `orEmpty()`, immutable `emptyList()`, appropriate scope functions, cohesive data classes, and sealed lifecycle state. State preservation of validation, errors, resource closing, and serialized values. Do not create a companion-delivery mechanism or rely on an optional sidecar.

Update each pack's `platform.yaml` through `declared_code_review_areas`, `declared_files.areas`, `area_metadata.focus`, required `lane_conditions.code-quality`, and its specialist pointer to `../../../orchestration/review-orchestrator/specialist-contract.md`. Add matching entries in `code-review/bill-<slug>-code-review/native-agents/agents.yaml`, retaining bundle version `0.1`, `compose: governed-content`, and both review evidence tools. Match manifest-derived native descriptions exactly, including contract-required separator punctuation.

Update baseline `code-review/bill-<slug>-code-review/content.md` routing prose to describe local required quality separately from reachable-failure lanes. Preserve existing failure-lane selection bounds and routing signals. Ordinary full packs have ten failure areas plus quality. KMP retains seven local failure areas and three inherited Kotlin failure areas, with its own quality owner. Specialists stay unlisted sidecars and native-agent inputs; the dispatcher remains `/skill-bill` only.

Test obligations: reuse `ReviewSkillStructureConformanceTest.kt`, `ShellContentLoaderParityTest.kt`, and `PlatformPackSchemaViolationsTest.kt` to catch malformed declarations, missing content/sections, incorrect internal parent, or native description/coverage mismatch through actual typed boundaries. Do not add prose-by-prose tests. Maintained substance rejection belongs to step 4.

### 4. Enforce maintained local ownership and substance without invalidating custom packs

Serves AC-001, AC-002, AC-003, AC-005, AC-011, and AC-012.

Add a distinct local universal-area ownership check in `PlatformPackSubstanceAuditPointerCatalog.auditPackAreaCoverageViolations`. Use manifest-discovered packs from `PlatformPackSubstanceAuditCoreFns`; inherited Kotlin quality cannot satisfy KMP. Keep `ShellContentLoader.validatePlatformPack`, `ShellContentLoaderPackBuild`, fields/parsing helpers, and `ReviewSkillStructureValidator` as the structural and typed validation owners. Do not require local quality in the general loader, rewrite custom packs, or extend `APPROVED_CODE_REVIEW_AREAS`.

Retain manifest schema `1.8` and the existing universal-area vocabulary in `ScaffoldPolicyConstants`. No examined requirement needs a schema bump. Use `PlatformPackSubstanceAuditManifestCoherence` and `PlatformPackSubstanceAuditPatterns` to support concrete maintenance consequences and preservation obligations without requiring a demonstrated behavioral defect. Retain ten substantive rules, three concern clusters, placeholder rejection, the 35 percent shared-shingle limit, the 65 percent corresponding-rubric similarity limit, and all failure-lane checks. Adjust quality-specific interpretation only if current failure-oriented patterns reject meaningful maintenance rules. Do not lower thresholds or add exemptions.

Strengthen `PlatformPackSubstanceAuditRepoTest.kt` to assert empty `auditErrors` as well as empty `violations`, and replace generic-only universal coverage and KMP physical-area assumptions with manifest-discovered local-quality assertions.

Test obligations: extend `PlatformPackSubstanceAuditTest.kt` with mutations invoking the production audit/validator. Named bugs are an undeclared shipped quality source, declared missing content escaping an errors-only result, coordinated deletion of source and all registrations passing structural validation, inherited-only KMP coverage passing local ownership checks, and generic text passing after renaming. Assert the relevant rejection boundary without copying its algorithm. Retain a legacy custom-pack loader case proving absence of local quality alone is valid.

### 5. Include required quality in dynamic full-pack scaffolding

Serves AC-003, AC-007, AC-011, and AC-012.

Update `ScaffoldPayloadMapPlatformPackPolicy.resolvePlatformPackSelection` to add universal areas to full-pack defaults using their existing owner. Keep approved failure areas distinct. Pass selections through `ScaffoldServicePlanningPayloadMerge` and `ScaffoldServicePlatformPackPlanningManifest.platformPackSpecialistPlan`, which already derive names, paths, metadata, and native descriptions.

Update `PlatformPackManifestPolicy.renderPlatformPackManifest` and `renderPlatformPackManifestContent` to emit required quality ownership. A manifest with `lane_conditions` must include conditions for every declared area and explicit `routing_signals.path` for a non-fallback pack. Emit the complete condition map while preserving effective path signals and failure-area behavior.

Give `ScaffoldContentStarters.areaReviewContent` a quality-specific starter and update `baselineReviewContent`. Preserve `canonicalSeverityCloser`'s quality behavior and existing dynamic template/native rendering. The starter covers maintenance concerns, required language/framework filling, behavior preservation, and the aggregate five-finding limit. It remains a stub that cannot pass meaningful substance validation until authored.

Test obligations: extend `ScaffoldPayloadMapPolicyTest.kt` and `ScaffoldServiceParityTest.kt` to catch a full pack generated with only ten areas, invalid condition parity/path signals, an optional quality lane, wrong sidecar parent, or missing native declaration. Assert generated manifests and sources through actual loader/validator boundaries. Prove placeholder promotion rejection through the maintained audit rather than a copied text predicate. Reuse `ScaffoldPlatformPackInstallTest.kt` for the isolated installation boundary.

### 6. Prove nearest-owner composition and unchanged failure lanes

Serves AC-004, AC-005, AC-010, and AC-013.

Retain `ReviewLaunchPlanPolicy.composedAreas` and `flatten`, `ReviewLaunchPlanComposition.kt`, and `ReviewCrossRootLaneReconciliation` as the only composition path. New declarations should select the nearest graph owner without an algorithm replacement. Keep failure lanes first, universal lanes last, graph ambiguity failures, effective-depth reconciliation, scope union, and requiredness. Generic supplies only an unowned universal area, never its other lanes to routed platform reviews.

Test obligations: extend `ReviewLaunchPlanPolicyTest.kt` and `ReviewCrossRootLaneReconciliationTest.kt` for local-over-generic, KMP-over-Kotlin, exactly one quality lane, local ownership without generic installed, legacy fallback, compatibility omission without fallback, and unchanged equal-depth ambiguity. These catch duplicate owners, incorrect fallback selection, or silent ambiguity resolution. Use a manifest-discovered shipped matrix comparing non-quality lane identity, owner, order, requiredness, signals, and add-ons with local quality declarations present versus removed in fixtures. The realistic regression is quality adoption changing an existing failure review's composition. Do not create a hard-coded routing slug list or duplicate the composition algorithm in assertions.

### 7. Preserve finding discipline and carry attribution through verify and inline output

Serves AC-008 and AC-009.

Keep `CodeQualityFindingIdentity`, `ParallelReviewMerger.merge`, and `ReviewIssueCategoryNormalization` owner-independent. Preserve Minor normalization, an aggregate cap of five, quality/failure deduplication separation, the quality section after failures, sequential F-ids, `specialist=` attribution, `code_quality` telemetry, and the `quality` alias for `testing_quality_gate`. Quality cannot change verdicts or suppress failure findings.

Update `VerifyStepSequence.registerLine` and verify receipt/telemetry import serialization to carry `specialistSkillNames`. Update inline visible-output handling where raw agent prose currently bypasses structured merged formatting. Preserve failure findings and existing repair policies. Standalone and verify remain report-only.

Test obligations: extend `ParallelReviewMergerTest.kt` and `ReviewIssueCategoryTest.kt` using local specialist names to catch a platform quality finding treated as blocking, a per-owner cap exceeding five overall, a quality finding suppressing a failure, or changed alias semantics. Extend `VerifyOperationTest.kt` to catch attribution lost between a merged finding, receipt, and telemetry import. At inline result boundaries, assert visible quality section, cap, attribution, and unchanged verdict as well as structured outcomes. Reuse current policies rather than replace them to satisfy tests.

### 8. Verify generated and installed owner integrity through existing boundaries

Serves AC-006, AC-007, AC-008, AC-012, and AC-013.

Use `NativeAgentComposition.kt` and `NativeAgentCompositionTargetResolution.kt` to compose the newly declared sources. Retain `FileSystemNativeAgentPlannedWorkerValidation`'s exact planned-worker coverage and `FileSystemReviewNativeAgentPreflight.verify`'s owner, inventory, provider path, target, embedded name, and digest checks. Missing exact owners retain the existing typed preflight failure; generic substitution is forbidden. Preserve `NativeAgentLinkInventoryReconcile` removal of obsolete managed links.

Test obligations: extend `FileSystemReviewNativeAgentPreflightTest.kt` using its isolated-home/source-removal pattern to prove installed local sidecars contain full language guidance without checkout access. Catch a missing local native owner silently launching generic, provider output with the wrong embedded identity/body, and stale links surviving pack removal. Reuse `NativeAgentRoundTripTest.kt`, `NativeAgentCompositionValidatesExistingBundlesTest.kt`, and isolated scaffold installation tests for dynamic bundle/provider generation. Assert installed outcomes, not source presence. Production installation refresh and its evidence belong to the parent runtime; this child plan prescribes no installer, uninstall, or install-sync command.

### 9. Update authored documentation and intended render snapshots

Serves AC-001, AC-002, AC-005, AC-008, AC-011, AC-012, and AC-013.

Update `../../../README.md`, `docs/capabilities.md`, `docs/external-platform-packs.md`, `orchestration/review-orchestrator/review-skill-structure-standard.md`, `platform-pack-substance-standard.md` in the same directory, and `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Describe ten failure areas plus required local quality, KMP's local override, legacy optional fallback and omission, full launch-provided language guidance, and unlisted installation. Replace stale generic-only statements. Align the touched substance-standard description with the audit rather than retain its obsolete quality-check-source requirement.

Update expected resources owned by `AuthoringRenderSnapshotTest.kt`: `snapshots/scaffold/bill-kotlin-code-review.render.txt`, `bill-kmp-code-review.render.txt`, and `snapshots/native-agents/bill-kmp-code-review.agents.yaml` under the skills module's test resources. Change the KMP UI snapshot only if its output actually changes. Provider-specific outputs, generated pointers, staging files, and generated `SKILL.md` wrappers remain uncommitted; `content.md` remains authoritative.

Test obligations: the existing render snapshot and bundle parity tests catch missing registration in generated authorized outputs. Documentation-only prose adds no new test. The write_history phase records SKILL-421's supersession of generic-only ownership in `../../../orchestration/review-orchestrator/agent/decisions.md`; implement supplies the outcome for that phase without doing its history work.

### 10. Hand off execution and evidence to the owning later phases

Serves all acceptance criteria, with installation evidence for AC-007 and AC-013 owned by the parent runtime.

Implement produces the planned repository end states and focused regression tests. Before authoring each test, state its named realistic bug from the obligations above. Extend existing boundary tests; avoid sibling literal variants, implementation-structure assertions, and tests for trivial glue. Preserve real past-bug regressions and governed parity coverage. Review and validation may repair production wiring, test setup, formatting, and lint as required; this plan creates no restriction that prevents those repairs.

Validate executes applicable focused tests from steps 1 through 9, then the required runtime full `./gradlew check`, `npx --yes agnix --strict .`, and `../../../scripts/validate_agent_configs` through its governed gate. Planning and implement do not execute tests, compile/build proof, or full checks under this briefing's phase ownership. Build proof stays with the build owner if scheduled by the runtime. The parent runtime owns installation refresh and inspection of exact installed owner/content, native preflight, and stale-link removal. No child installation command is planned.

Audit performs full per-criterion inspection. Review uses branch-diff scope and applies architecture rule IDs A1 through A12 and G1 through G7 alongside Design Principles, Code Principles, observability, and source-generation contracts. Keep pack behavior manifest-owned, vocabulary single-owned, adapters behind ports, and persistence runtime-owned. Delete obsolete assumptions. Do not loosen guards, baselines, thresholds, exemptions, or file limits. Add no Kotlin line/block comments or KDoc outside interfaces and their members.

The digest settles the branch-condition question: `main` contains the SKILL-380 and SKILL-413 merges, so the old conditional fix-branch restriction does not apply. Branch, history, commit, push, PR, monitor, update checks, and launch confirmation remain with their runtime owners. Preserve unrelated work. Tracker comments were unavailable, but the supplied immutable requirements are sufficient and require no further tracker lookup. There are no subtask dependencies or additional dependency requirements indicated by the digest.

This phase only adds this implementation plan. It executes no tests, builds, compilation, validation, installation, history, or workflow commands. The existing validation strategy's installation requirement remains unchanged; the governing child-phase constraint assigns its execution to the parent runtime. No repository discovery or verification beyond reading this assigned sub-spec is part of planning.
