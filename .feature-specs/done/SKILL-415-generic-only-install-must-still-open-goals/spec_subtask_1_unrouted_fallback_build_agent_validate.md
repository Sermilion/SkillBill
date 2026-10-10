# Subtask 1: Treat review-fallback-only catalogs as unrouted for build

One implementation pass: interpret review-fallback-only / `Absent("generic")` as unrouted for build, record `agent-validate` (VALIDATION family, null packSlug, null declaration) on GOAL_CHILD BUILD creation for that catalog, keep SKILL-360 and routed pack-build, keep recorded-generic-build-plan resume refusal, and fix selected-path error text so it does not tell the operator to repair generic's build commands.

## Scope

`FeatureTaskRuntimeExecutionPlanResolver.resolveCreation` currently (1) calls `PhaseStrategyLookup.executionPlan` with the request `qualityGate` first, (2) `resolveInputs`, (3) `requireBuildGate`. For GOAL_CHILD, `SkeletonStrategyBindings` maps BUILD to `PackBuildStrategy.ID` (`pack-build`) and VALIDATE to `AgentValidateStrategy.ID` (`agent-validate`). `GoalRunnerQualityGateSelectionResolver` returns VALIDATE only for the last non-skipped subtask, else BUILD. Creation copies `Absent.routedPackSlug` (`"generic"`) as `packSlug`, `declaration` stays null, `commandFamily` is BUILD, `EffectiveGatePolicyInputs.commandArgv` is null, and `requireBuildGate` throws `missingValidationGate` with source `"Selected"` and recovery ` Repair pack routing or its build commands before creating the workflow.`

Keep `GoalRunnerQualityGateSelectionResolver` as-is. Do not change `AgentValidateStrategy`, `PackBuildStrategy`, install staging, or `generic/platform.yaml`. Do not add `BUILD → agent-validate` in the `SkeletonStrategyBindings` ByFact map (`matchesRecordedSelection` would then disagree with recorded VALIDATE plans).

### Chosen interpretation (preferred place from the digest)

Change `ValidationGateResolver.resolution` so that when `dominant.validationGate` is null **and** `dominant.slug` equals `ReviewFallbackResolver.resolveOptional(manifests)?.slug`, return `ValidationGateResolution.Absent(null)` instead of `Absent(dominant.slug)`. Concrete no-gate packs still return `Absent(dominant.slug)`. Empty catalog already returns `Absent(null)`.

`Absent("kotlin")` and `Absent("kmp")` remain selected-but-missing-gate. Do not treat every `Absent` as unrouted (R5).

If existing tests currently assert `Absent("generic")` as the routed pack slug (`ValidationGateRoutingTest` `"a repository without concrete ownership keeps the fallback resolution"` and `FeatureTaskRuntimeValidationGateTest` `"a no-gate fallback pack does not borrow an unrelated catalog gate"`), update only the slug assertion to `null` / unrouted-for-build. Their review meaning (fallback does not borrow kotlin’s gate) must remain.

Equivalent interpretation in `resolveInputs` (not copying a fallback slug into `packSlug`) is a fallback only if emitting `Absent(null)` from `resolution` would change review meaning rather than a slug assertion. Prefer the resolver change.

### Reorder `resolveCreation`

Know routing before strategy selection. When `request.qualityGate` is BUILD **and** the resolution is unrouted for build (`Absent` with `routedPackSlug` null, or fallback-only catalog), pass `FeatureTaskRuntimeQualityGateSelection.VALIDATE` into `PhaseStrategySelectionFacts` so bindings select `AgentValidateStrategy.ID`, and set `EffectiveGatePolicyInputs.commandFamily` to `ValidationGateCommandFamily.VALIDATION` with `packSlug` null and `declaration` null. Then `requireBuildGate` returns immediately because `commandFamily` is not BUILD.

Do not leave `commandFamily` BUILD with a skipped `requireBuildGate`: resume would call `requireBuildGate(recorded = true)` and throw on the new good plans (R3). Do not record pack-build with a null declaration. Do not change `PackBuildStrategy` / `FeatureTaskRuntimeBuildGateCoordinator` / `RuntimeQualityGateCycles` if QUALITY_GATE is `agent-validate` (R2: pack-build must not run for this catalog).

### Resume compatibility (R1, R4, R6)

`existingChildExecutionPlanAdmission` still passes BUILD from `GoalRunnerQualityGateSelectionResolver` while the recorded plan will have `qualityGateSelection` VALIDATE. `requireRequestedSettings` currently `incompatible()`s on `plan.qualityGateSelection != qualityGate`. Extend that check (or the `workflowId` branch of `resolveCreation`) so a BUILD request matches a recorded VALIDATE plan **only** when the recorded effective pack is unrouted (`null` packSlug **and** `null` declaration). Do not accept BUILD vs recorded VALIDATE when a concrete pack declaration is present.

Recorded generic pack-build plans stay BUILD family + `packSlug` `"generic"` + null declaration; `requireRequestedSettings` still matches BUILD, `requireBuildGate` still throws.

`FeatureTaskRuntimeRunEntry.open` uses `goalContinuation?.qualityGateSelection` and is not the incident path. Do not broaden it unless implement proves that path also hits `requireRequestedSettings` with BUILD vs recorded VALIDATE for the same child; if it does, apply the same unrouted compatibility there and nowhere else.

Assumption for implement to confirm: `FeatureTaskRuntimeExecutionPlanValidator` / codec already accepts GOAL_CHILD + VALIDATE + null packSlug + null declaration (STANDALONE already encodes `qualityGate` null; resume-refusal already encodes null declaration). If encode/validate rejects it, fix that in the same pass rather than inventing pack-build-with-empty-argv.

### Error text

Non-recorded `requireBuildGate` path: if it can still fire for generic or unrouted, drop `Repair pack routing or its build commands before creating the workflow.` Name that there is no concrete pack with a build pair and that `agent-validate` should have been selected. Keep the recorded recovery string used by the existing generic resume-refusal test. Keep Selected/Recorded wording for concrete SKILL-360 packs.

Prefer a message change on `missingValidationGate` / the `requireBuildGate` formatter; do not add a new `RuntimeFailureCode` unless a new code is truly required.

## Implementation Details

Land in this order so resume of new plans cannot see a BUILD-family unrouted record:

1. `ValidationGateResolver.resolution(candidates)`: review-fallback slug with null `validationGate` → `Absent(null)`. Concrete no-gate dominant → `Absent(dominant.slug)`. Sealed `when` branches stay exhaustive. Optionally `resolveWithRepositoryFallback` only if it currently forces a generic slug after `resolution` would have returned null.

2. `FeatureTaskRuntimeExecutionPlanResolver.resolveCreation`: resolve inputs/routing first; when BUILD + unrouted-for-build, select VALIDATE facts and VALIDATION family with null packSlug/declaration; then look up the execution plan; then `requireBuildGate`.

3. `requireRequestedSettings`: BUILD request vs recorded VALIDATE is compatible only for unrouted recorded packs (null packSlug and null declaration).

4. `requireBuildGate` non-recorded message: no “repair generic’s build commands”; name the actual gap and that `agent-validate` should have been selected when the path is unrouted/generic. Recorded-path text unchanged.

5. Tests as in Test obligations. Reuse `reviewFallbackPackWithoutGate()`; do not redesign the fixture. `Fixture.resolver` already injects `ValidationGateResolver { packs }` and `WorkflowGitOperations` inventory/tracked; follow that pattern. Current tests use fakes, not mockK. Default fixture timeout `7.minutes` / `420000L`. Diagnostics `NoopRuntimeDiagnostics`. kotlin.test `assertEquals` / `assertFailsWith` / `assertTrue`. Assert `ManifestFailureCode.MANIFEST_FAILURE` rather than exception subclasses. Decode via `fixture.execution.compatibility.requireSupportedExecution(fixture.execution.validator.write(descriptor.artifactValue, …), inputs)` as the first test does. `fixture.packs = listOf(reviewFallbackPackWithoutGate())` **replace, do not append** (current fallback tests do `fixture.packs + reviewFallbackPackWithoutGate()` and still have kotlin).

6. If encode/validate of GOAL_CHILD + VALIDATE + null packSlug + null declaration fails, fix the validator/codec in this same pass.

7. If GOAL_CHILD continuation through `FeatureTaskRuntimeRunEntry.open` hits the same BUILD-vs-VALIDATE mismatch, apply the same unrouted compatibility there only.

Do not invent a second product behavior. Do not skip `requireBuildGate` while leaving BUILD family. Do not treat every `Absent` as unrouted.

### Exact production signatures this task may change

- `FeatureTaskRuntimeExecutionPlanResolver.resolveCreation(request: FeatureTaskRuntimeExecutionPlanCreationRequest): ValidatedFeatureTaskRuntimeExecutionPlan`
- `resolveInputs(repoRoot: Path, qualityGate: FeatureTaskRuntimeQualityGateSelection?, validationDepth: ValidationDepth, timeout: Duration?, workflowId: String? = null): EffectiveGatePolicyInputs`
- `requireBuildGate(inputs: EffectiveGatePolicyInputs, recorded: Boolean)`
- `requireRequestedSettings(plan: ResolvedPhaseExecutionPlan, qualityGate: FeatureTaskRuntimeQualityGateSelection?, validationDepth: ValidationDepth, timeout: Duration?)`
- `ValidationGateResolver.resolution(candidates: List<PlatformManifest>): ValidationGateResolution` and optionally `resolveWithRepositoryFallback(changedPaths: List<String>, trackedPaths: () -> List<String>): ValidationGateResolution`
- `missingValidationGate(message: String, cause: Throwable? = null): SkillBillRuntimeException` in `ManifestFailureCode.kt` (message only unless a new code is required)

Fields used: `FeatureTaskRuntimeExecutionPlanCreationRequest` (`repoRoot`, `definition`, `reviewMode`, `qualityGate`, `validationDepth`, `timeout`, `workflowId`); `EffectiveGatePolicyInputs(commandFamily, packSlug, declaration, gradleWrapper, validationDepth, phaseTimeoutMillis)`; `ValidationGateResolution.Absent(routedPackSlug: String?)`.

`reviewFallbackPackWithoutGate(): PlatformManifest` slug `"generic"`, `fallbackCapabilities` `setOf("code-review")`, `validationGate` null, empty routing signals.

### Intended files

- `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/execution/FeatureTaskRuntimeExecutionPlanResolver.kt`
- `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/validation/ValidationGateResolver.kt`
- `../../../runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/featuretask/slot/FeatureTaskRuntimeExecutionPlanResolverTest.kt`
- `../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ManifestFailureCode.kt` (message only, if the formatter lives there)
- Possibly `ValidationGateRoutingTest.kt` and `FeatureTaskRuntimeValidationGateTest.kt` if `Absent("generic")` becomes `Absent(null)`
- Reuse fixture in `FeatureTaskRuntimeValidationGateTestSupport.kt` (do not redesign)

This list is the intended seam, not a ban on production wiring, test setup, formatting, lint, or encode/validate repairs required to land the recorded VALIDATE + null packSlug plan. Preserve architecture rules and observable behavior. Out of scope unless a follow-on fact appears during implement: `FileSystemInstalledPlatformPackCatalog`, `generic/platform.yaml`, `GoalRunnerPolicy.kt` resolver, `PackBuildStrategy`, `AgentValidateStrategy`, `FeatureTaskRuntimeBuildGateCoordinator`, `RuntimeQualityGateCycles`, install selection mode none, `SkeletonStrategyBindings` ByFact map.

## Acceptance Criteria

1. A named test in `FeatureTaskRuntimeExecutionPlanResolverTest` uses `fixture.packs = listOf(reviewFallbackPackWithoutGate())` only (replace, not append), `inventory` `WorkflowGitNameListResult.Listed` of kotlin sources (e.g. `runtime-kotlin/Main.kt`), `tracked` `WorkflowGitNameListResult.Listed` of kotlin sources, and `resolveCreation` with `SkeletonDefinition.GOAL_CHILD`, `CodeReviewExecutionMode.INLINE`, `FeatureTaskRuntimeQualityGateSelection.BUILD`, `ValidationDepth.FULL`, `7.minutes`. The test asserts creation does not throw `ManifestFailureCode.MANIFEST_FAILURE` / `missingValidationGate`.
2. That same test decodes the plan and asserts `"validate"` is in `plan.selectedStepIds`, `"build"` is not, `inputs.packSlug` is `null` (not `"generic"` as a selected build pack), `inputs.declaration` is `null`, and `commandFamily` is `VALIDATION`.
3. `ValidationGateResolver.resolution` returns `Absent(null)` when the dominant pack is the review fallback with null `validationGate`, and still returns `Absent(dominant.slug)` for a concrete no-gate pack (`kotlin` / `kmp`). Existing tests that prove the fallback does not borrow kotlin’s gate still prove that; they may assert `null` instead of `"generic"` as the routed pack slug.
4. Existing tests remain: `"creation binds routed pack…"` (kotlin `packSlug`, BUILD family, `"build"` in `selectedStepIds`); `"goal child creation before implementation binds the repository's pack instead of the review fallback"` (generic present alongside kotlin still binds kotlin and `listOf("./gradlew", "compileKotlin")`); `"creation refuses unknown routing and missing build commands before implementation"` / `kotlinPackWithoutGate()` still fails `create()` with `ManifestFailureCode.MANIFEST_FAILURE`.
5. `"resume refuses a recorded fallback build plan without replacing it with fresh Kotlin routing"` is unchanged: recorded BUILD family, `packSlug` `"generic"`, `declaration` `null`, throws, message contains `Recorded build gate pack 'generic'` and `reviewed semantic mapping`.
6. `requireRequestedSettings` (or the `workflowId` branch of `resolveCreation`) treats a BUILD request as compatible with a recorded VALIDATE plan only when recorded `packSlug` and `declaration` are both null; a recorded VALIDATE plan with a concrete pack declaration is still incompatible with a BUILD request.
7. Non-recorded `missingValidationGate` text for an unrouted or generic path names that there is no concrete pack with a build pair and that `agent-validate` should have been selected; it does not contain `Repair pack routing or its build commands before creating the workflow.` Recorded-path recovery for the generic resume-refusal test is unchanged. Selected/Recorded wording for concrete SKILL-360 packs stays.
8. `../../../platform-packs/generic/platform.yaml` has no `validation_gate`. `ReviewFallbackResolver`, `ReviewStackRouting`, `SkeletonDefinition.REVIEW` bindings, and generic `fallback_capabilities` are not changed for this subtask.

## Non-Goals

- No generic `validation_gate`, invented pack commands, or install-staging change.
- No change to `GoalRunnerQualityGateSelectionResolver` or `SkeletonStrategyBindings` ByFact (`BUILD` remains `pack-build`).
- No sibling markdown-only creation test covering the same catalog branch as the kotlin-inventory test (path-independent once `resolution` keys off the fallback slug). Add markdown-only only if implement finds path-dependent candidate selection that still treats generic as a build owner for one inventory and not the other.
- No new runtime environment maps unless a new test builds one; if it does, pass an explicit non-empty map. No `relaxed = true` mockK.
- No user-facing feature work. Review, commit, PR, history, and pack validation-gate execution belong to later phases.

## Dependency Notes

Depends on nothing. Parent spec `spec.md` in this directory. Preplan digest is the authority; implement confirms the two recorded assumptions (codec accepts GOAL_CHILD + VALIDATE + null packSlug; `RunEntry.open` does not need the same BUILD↔VALIDATE widening unless proven). Assumption: `base_branch` is `main`.

## Test obligations

1. **New** `FeatureTaskRuntimeExecutionPlanResolverTest` case: catalog = `reviewFallbackPackWithoutGate()` only; kotlin dirty and tracked; GOAL_CHILD BUILD creation succeeds as `agent-validate` with null packSlug/declaration and VALIDATION family. Realistic bug: generic-only install throws `Selected build gate pack 'generic' has no complete build command pair` before the first child workflow exists (incident 2026-10-09). One test; do not add a markdown-only sibling for the same catalog branch.
2. **Keep** (not new): resume refuses recorded fallback build plan; routed kotlin pack-build; kotlin-not-fallback when both present; `kotlinPackWithoutGate()` creation refusal; fallback does not borrow an unrelated catalog gate (update slug assertion only if `Absent(null)`).

No other tests. Glue on the strategy lookup after VALIDATE facts are passed needs no extra test: the creation test already asserts `"validate"` in `selectedStepIds`.

## Validation Strategy

Named tests and production files above are the audit surface. Validate phase runs the pack validation gate; implement and audit do not compile, execute tests, or run check. `tests_executed` stays empty until validate.

## Next Path

```bash
skill-bill goal SKILL-415
```
