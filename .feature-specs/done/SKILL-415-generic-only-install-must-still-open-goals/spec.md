# SKILL-415: Generic-only install must still open goals

A valid install that selects no language pack (`install-selection` `platform_pack_selection.mode` `none`, review-catalog containing only generic) must still create and run goals.

Incident 2026-10-09: `skill-bill goal SKILL-414` failed before creating the first child workflow with: `Selected build gate pack 'generic' has no complete build command pair. Repair pack routing or its build commands before creating the workflow.`

## Scope

Treat a review-fallback-only catalog (`Absent("generic")` / `reviewFallbackPackWithoutGate()` only) as unrouted for **build**, not as a selected build pack. On that catalog, `FeatureTaskRuntimeExecutionPlanResolver.resolveCreation` for `SkeletonDefinition.GOAL_CHILD` with `qualityGate` BUILD records `agent-validate` and proceeds. Concrete packs that declare a complete BUILD pair keep pack-build. Generic stays a code-review fallback without `validation_gate`. SKILL-360 still fails a concrete dominant pack that lacks `validation_gate`. Resume still refuses a recorded pack-build plan bound to generic without a reviewed mapping.

This is one creation-time interpretation seam plus the resume compatibility that the same recorded plan requires. Independently shipping an unrouted `packSlug` without selecting `agent-validate` still throws in `requireBuildGate`. Independently shipping `agent-validate` without recording a non-BUILD command family makes resume of the new good plans throw the recorded-missing-gate path.

## Acceptance Criteria

1. `FeatureTaskRuntimeExecutionPlanResolver.resolveCreation` for `SkeletonDefinition.GOAL_CHILD` with `qualityGate` BUILD does not throw `ManifestFailureCode.MANIFEST_FAILURE` / `missingValidationGate` when the installed catalog is only `reviewFallbackPackWithoutGate()`, and a named test in `FeatureTaskRuntimeExecutionPlanResolverTest` asserts that creation succeeds for that catalog.
2. The recorded QUALITY_GATE strategy for that creation is `agent-validate`: `"validate"` is in `plan.selectedStepIds` and `"build"` is not; `EffectiveGatePolicyInputs.packSlug` is not a build owner (`null`, not `"generic"`); `declaration` is `null`; `commandFamily` is `ValidationGateCommandFamily.VALIDATION`.
3. When a concrete pack with a complete BUILD pair is routed (existing kotlin fixture with `validationGateTestDeclaration.buildCommand` `[./gradlew, compileKotlin]` and `cacheBypassingBuildCommand`, and kmp with its own gate), pack-build and those argv stay: existing tests still assert `"build"` in `selectedStepIds` and the pack argv.
4. `../../../platform-packs/generic/platform.yaml` still has no `validation_gate`. `kotlinPackWithoutGate()` as the dominant concrete pack still fails `create()` with `ManifestFailureCode.MANIFEST_FAILURE`. `Absent("kotlin")` and `Absent("kmp")` remain selected-but-missing-gate, not unrouted.
5. `ReviewFallbackResolver`, `ReviewStackRouting`, `SkeletonDefinition.REVIEW` bindings, and generic `fallback_capabilities: [code-review]` are unchanged so reviews in a generic-only install still route to generic.
6. `FeatureTaskRuntimeExecutionPlanResolverTest` still contains `resume refuses a recorded fallback build plan without replacing it with fresh Kotlin routing`: recorded BUILD family, `packSlug` `"generic"`, `declaration` `null`, still throws, and the message contains `Recorded build gate pack 'generic'` and `reviewed semantic mapping`.
7. Non-recorded `missingValidationGate` text for an unrouted or generic path names that there is no concrete pack with a build pair and that `agent-validate` should have been selected; it does not tell the operator to repair generic's build commands. The recorded-path recovery string used by the generic resume-refusal test is unchanged. Selected/Recorded wording for concrete SKILL-360 packs (`pack 'kotlin'` / `'kmp'`) stays.

## Non-Goals

- No new user-facing features, CLI flags, or install-selection changes.
- No `validation_gate` on generic.
- No command-discovery that invents `./gradlew check`, `npm test`, or `xcodebuild`.
- No change to install mode `none` staging only generic.
- No requirement that operators install kmp or kotlin to run a goal.
- No change to `FileSystemInstalledPlatformPackCatalog`, `GoalRunnerQualityGateSelectionResolver`, `PackBuildStrategy`, `AgentValidateStrategy`, `FeatureTaskRuntimeBuildGateCoordinator`, `RuntimeQualityGateCycles`, or `SkeletonStrategyBindings` ByFact mappings (`BUILD` stays `pack-build`; `VALIDATE` already maps to `agent-validate`).
- No broadening of `FeatureTaskRuntimeRunEntry.open` unless implement proves the same BUILD-vs-VALIDATE mismatch on GOAL_CHILD continuation.

## Constraints

- Supplied requirements are authoritative; no tracker lookup.
- Mocks use `relaxUnitFun = true`, never `relaxed = true`. Tests that build a runtime environment map pass an explicit non-empty map.
- Authored Kotlin has no `//` or non-KDoc block comments. KDoc only on interfaces and their members.
- Wire keys stay in owning `*Keys` objects.
- Assumption: repository default / `base_branch` is `main` (digest did not name it). Feature branch is `feat/SKILL-415-generic-only-install-must-still-open-goals`.

## Dependency Notes

Single-spec goal. One subtask owns the resolver interpretation, recorded-plan family, resume compatibility, selected-path error text, and tests. Later phases (implement, simplify, audit, review, validate, commit, PR) consume this bundle; this parent does not grant extra sources.

## Validation Strategy

Validate phase (not implement, not audit) runs the pack validation gate (`validation_gate.collect_all_full_gate_command`). Implement and audit inspect the tree and named tests; they do not compile, execute tests, or run check. `tests_executed` stays empty until validate.

## Next Path

```bash
skill-bill goal SKILL-415
```
