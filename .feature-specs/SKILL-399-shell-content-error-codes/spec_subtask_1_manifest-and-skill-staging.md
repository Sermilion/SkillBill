# SKILL-399 Subtask 1 - manifest-and-skill-staging

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

Convert every class in `ManifestShellContentErrors.kt` (8 classes) and `SkillStagingShellContentErrors.kt` (12 classes) to coded failures.

- `ManifestFailureCode`: missing manifest, manifest schema, validation gate declaration, composition cycle, ambiguous lane ownership, incompatible composition contract, missing composition layer. Family entry: missing validation gate.
- `SkillStagingFailureCode`: sidecar collision, authored sidecar, review skill structure, missing content file, composed budget exceeded, missing required section, SKILL.md shape, missing installed native agent, internal skill classification, missing baseline platform selection, fallback capability. Family entry: native agent link inventory.
- Known `is` site: `ExternalPlatformPackTelemetryPolicy.kt:23`. Its family branch checks the manifest-schema code.
- Pinned tests whose expected value becomes the code label (`failureCodeLabel()`):
  - `ConfigExternalPlatformPackCommandTest:110` asserts `ERROR_TYPE` = `InvalidManifestSchemaError`;
  - `InternalSkillCompanionInstallApplyTest:70` asserts `causeClass` = the qualified `InternalSkillSidecarCollisionError`.

## Acceptance Criteria

1. The two files declare no class; they hold only `ManifestFailureCode`, `SkillStagingFailureCode` and message functions.
2. Each former failure throws `SkillBillRuntimeException` with an entry of one of the two enums, or is a `require`/`check`/`error()` defect.
3. No main code reads a typed property from a caught failure of these areas.

## Non-Goals

The other shell-content areas, and classes outside `skillbill.error.shellcontent`.

## Test obligations

None beyond the converted assertions.

## Shared Rules

Apply `spec.md` "Conversion rules", "Shared transition pieces" and "Execution Rule". If a shared transition piece is missing, add it as written there. If any main `catch`, `is` or `as?` on `ShellContentContractException` lacks the `isShellContentContractFailure()` guard, add the guard. Add each area enum this subtask creates to `isShellContentContractFailure()`, unless it is `ScaffoldFailureCode`.

After this subtask's edits, check whether any class in main still extends `SkillBillRuntimeException` or `ShellContentContractException`. If none does, finish the transition as `spec.md` "Target failure model" describes.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch.

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_1_manifest-and-skill-staging.md


## Implementation Details

This plan uses only the upstream preplan digest and this sub-spec. Discovery at `8527efaee41cfc2beb316be9b6eec4be017f9deb` found eight Manifest classes and twelve SkillStaging classes. This subtask remains independent of the other seven slices. Implement applies the plan to the current symbols without changing another slice's ownership.

1. Replace the two owned class declarations with owner codes and message functions. Serves AC-001 and AC-002.

   Work in `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ManifestShellContentErrors.kt` and `SkillStagingShellContentErrors.kt`. Both enums implement the existing `RuntimeFailureCode` marker. Manifest gets seven distinct entries for missing manifest, manifest schema, invalid validation-gate declaration, composition cycle, ambiguous lane ownership, incompatible composition contract and missing composition layer. Missing validation gate uses the Manifest family entry. SkillStaging gets eleven distinct entries for sidecar collision, authored sidecar, review skill structure, missing content file, composed budget exceeded, missing required section, SKILL.md shape, missing installed native agent, internal skill classification, missing baseline platform selection and fallback capability. Native-agent link inventory uses its family entry.

   All Manifest conditions remain coded failures because manifests, routing and selected composition can trigger them. Keep SkillStaging failures coded too. The digest identifies input-driven staging failures and provides no evidence that any is exclusively a code defect. Do not introduce defect checks merely to reduce enum entries.

   Message-only constructors become direct `SkillBillRuntimeException(code, message, cause)` constructions at their producers. Keep optional causes on the first four Manifest failures. Structured constructors used at multiple sites become functions beside their enum, returning `SkillBillRuntimeException` with the original parameters and cause where supported. Preserve the collision's parent, internal skill and sidecar path, native-agent preflight's logical name, provider, expected path, reason and repair command, and baseline selection's selecting slug, required baseline slug and declaring manifest path. Copy every message expression unchanged, including blank substitutions and punctuation. No removed fields become exception properties or message-parsing inputs.

   Planning assumption for implement to confirm: enum token spelling and message-function names follow the existing AgentAddon and GovernedReview convention. The digest supplies semantic entries rather than exact token spellings. That naming choice must not change the mapping or observable messages.

2. Convert Manifest producers and the two discrimination boundaries. Serves AC-002 and AC-003.

   Update producers under `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/scaffold/platformpack/loader/`, its `skillclass/` package, and `platformpack/manifest/PlatformPackSchemaValidator.kt`. Keep the loader helpers in `ShellContentLoaderErrors.kt` returning `Nothing`. Update `runtime-domain/src/main/kotlin/skillbill/review/plan/ReviewLaunchPlanComposition.kt`, `ReviewCrossRootLaneReconciliation.kt`, and engine build-gate and execution-plan resolution producers. The digest does not name the latter files. Implement resolves their current references while preserving their existing owners and control flow.

   In `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/externaladdon/FileSystemExternalAddonOverlayCollisions.kt`, replace the manifest-schema catch with a `SkillBillRuntimeException` catch guarded by the exact manifest-schema code and `rethrowUnless`. In `runtime-domain/src/main/kotlin/skillbill/scaffold/policy/platformpack/ExternalPlatformPackTelemetryPolicy.kt`, discriminate that same code and preserve `invalid_external_platform_pack_manifest`. Retain its existing `failureCodeLabel` rendering of `ERROR_TYPE`. Unrelated coded failures, database failures, cancellation and interruption must continue to propagate.

   Convert existing Manifest exception assertions in `PlatformPackSchemaViolationsTest.kt`, `ShellContentLoaderValidationGateTest.kt`, `PointerManifestParsingTest.kt`, `PlatformPackFallbackTest.kt` and composition/loader parity repository tests to the runtime exception plus exact owner-code assertions. Preserve messages, inputs, fallback results and parity assertions.

3. Convert SkillStaging producers and the shape-validation catch. Serves AC-002 and AC-003.

   Update the producers under `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/`, including `install/staging/InternalSidecarTarget.kt`, `install/plan/InstallPrimitives.kt`, `install/nativeagent/inventory/NativeAgentLinkInventory.kt`, `install/nativeagent/link/InstallNativeAgentOperationsLink.kt`, native-agent rendering, review-skill validation, shape validation and internal-skill declaration. Update `runtime-infra/workflow/src/main/kotlin/skillbill/infrastructure/workflow/review/specialists/FileSystemReviewNativeAgentPreflight.kt` to use the native-agent factory or coded construction.

   In `scaffold/runtime/validation/RepoValidationRuntimeSkillValidation.kt`, replace the `InvalidSkillMdShapeError` catch with a runtime-exception catch guarded by the exact shape code. Preserve its existing handled result and rethrow every other code. Change any producer callback or return type that names a removed class to `SkillBillRuntimeException`. Readers use only `code`, `message` and `cause`; preserve context at the producer instead of extracting it from a caught failure.

   Convert assertions in `SkillMdShapeValidatorTest.kt` and the existing `install/InternalSkillStaging*Test.kt` family. Keep the native-agent repair guidance and staging outcomes unchanged.

4. Integrate classification and preserve output boundaries. Serves AC-002, AC-003 and the common message/payload criteria.

   Add both new enums to `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ShellContentContractFailures.kt`. Preserve its existing `FailureWireCode`, AgentAddon, GovernedReview and `GoalTelemetryRowFailureCode` classifications. Keep Scaffold excluded. The digest confirms `rethrowUnless`, `failureCodeLabel` and the transitional exception already exist in `error/core/RuntimeExceptionBases.kt`; reuse them. Core must never import shellcontent.

   Retain existing `failureCodeLabel() ?: existingExpression` rendering and every guarded shell-content catch. Where a shared catch needs adjustment to accept a new enum, preserve its handled set and rethrow. No unrelated legacy failure's label, payload or message changes. Update `runtime-cli/src/test/kotlin/skillbill/cli/config/ConfigExternalPlatformPackCommandTest.kt` only so its converted `ERROR_TYPE` is the manifest-schema code label. Update `runtime-infra/skills/src/test/kotlin/skillbill/infrastructure/skills/install/InternalSkillCompanionInstallApplyTest.kt` only so `causeClass` is the sidecar-collision code label. Preserve all other telemetry and failure-payload assertions.

5. Remove obsolete baseline rows and inspect the transition condition. Serves AC-001 through AC-003 and the common baseline criterion.

   Remove only the twenty whole rows for the deleted owned classes from `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Do not alter another row or weaken `FailureCodeTotalityArchitectureTest.kt`, its synthetic fixtures, or `ArchitectureScanSupport.kt`.

   After implementation, check for remaining main subclasses of `SkillBillRuntimeException` and `ShellContentContractException` and for codeless constructions. Preplan found external uses in core, featuretask and MCP, so the expected result is to retain the open exception, legacy base and secondary constructor. Existing external uses are outside this slice. If intervening work removed every use, finish the shared transition under the supplied parent rule, remove the legacy classifier term, and preserve coded classification and guarded rethrows. Do not remove transition support while any use remains.

6. Hand off end-state inspection and validation to their owning phases. Serves all acceptance criteria and the unchanged Validation Strategy.

   Audit inspects both files for absence of classes, verifies each former producer's code or justified defect outcome, checks that catches read only `code`, `message` and `cause`, and checks byte-identical messages and unchanged handled sets. Review applies A1 through A12 and G1 through G7, especially A7 handled sets, A6 wire ownership, A9 package cycles and A10 test placement. Keep ports declaration-only under A4. Preserve phase-generic SKILL-380 attempt handling and accepted-step authority.

   Test obligations remain empty. Existing assertions cover this slice, and preplan prescribes no new behavior test. Convert exception assertions without removing regression or governed parity coverage. The realistic regressions those existing checks must continue to catch are a manifest failure receiving the wrong classification, altered user guidance or telemetry payload, and a staging failure losing its specific code or output.

   Build proof belongs only to the build owner. Validate runs the existing unit tests named above, detekt, formatting and runtime-core repository architecture suites, including `FailureCodeTotalityArchitectureTest`, ports declarations, typed parse boundaries, package cycles, wire vocabulary and comment guards. The full pack gate is `./gradlew check --continue --parallel -q --warning-mode none`; only validate runs it. Spotless runs in a plain clone. Required production-wiring, test-setup, formatting and lint repairs remain authorized in their owning later phases.

Throughout implementation, preserve the current package and module ownership. Add no module, dependency, deleted-class alias, exception field, family metadata, raw-map public result, locator accessor, new `runCatching` or suppression. Respect the supplied detekt limits, add no authored Kotlin line comments or non-interface KDoc, and keep existing test source sets and persisted resource paths. No persisted schema, payload, dependency, feature flag, authored skill or generated artifact change is needed. History, commit and PR work remain with their owning phases. This plan phase edits only this section and runs no builds, tests or repository checks.
