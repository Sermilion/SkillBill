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

The preplan digest records this subtask as already on `feat/SKILL-399-shell-content-error-codes` (history entry `runtime-kotlin/runtime-contracts/agent/history.md#1a51faace563`, decision `runtime-kotlin/runtime-contracts/agent/decisions.md#292a459e575d`). `ManifestShellContentErrors.kt` and `SkillStagingShellContentErrors.kt` already declare only `ManifestFailureCode`, `SkillStagingFailureCode` and message functions. `ShellContentContractFailures.kt` already registers both enums. This plan is therefore verify-only. Implement confirms the landed end state and repairs only a gap it actually finds. It does not redo the conversion. All paths are relative to `runtime-kotlin/`.

1. Confirm the two files hold no class. Serves AC-001.
   - Inspect `runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ManifestShellContentErrors.kt` and `SkillStagingShellContentErrors.kt`. Each must contain only its enum (implementing `skillbill.error.core.RuntimeFailureCode`) and top-level functions returning `SkillBillRuntimeException`. There must be no `class`, `object`, typealias or subclass of `ShellContentContractException`.
   - Confirm that no main source declares a typealias named after any of the 20 deleted classes. Examples are `InvalidManifestSchemaError` and `InternalSkillSidecarCollisionError`.
   - Repair if needed: if a class survives, convert it under the parent spec's "Conversion rules". Use an entry of the owning enum (Manifest family entry: missing validation gate; SkillStaging family entry: native agent link inventory), keep the message byte-identical, and convert its throw and catch sites.

2. Confirm every former failure is coded. Serves AC-002.
   - Search main sources across all modules for the 20 former class names. No reference may remain outside test fixtures that were already converted.
   - Each former producer must construct `SkillBillRuntimeException` with an entry of `ManifestFailureCode` or `SkillStagingFailureCode`, or call the message function beside the enum. The only exception is a `require`/`check`/`error()` defect that the landed subtask already justified.
   - Per decision `#292a459e575d`, input-driven manifest and staging failures stay coded. Do not turn any of them into a defect during verification.

3. Confirm that catches read no typed property. Serves AC-003.
   - Check the known discrimination sites. `ExternalPlatformPackTelemetryPolicy.kt` must check the manifest-schema code through `(error as? SkillBillRuntimeException)?.code`. The manifest-schema catch in `FileSystemExternalAddonOverlayCollisions.kt` must be guarded with `rethrowUnless` on the exact code. The `RepoValidationRuntimeSkillValidation.kt` SKILL.md-shape catch must be guarded the same way.
   - Every reader of a caught Manifest or SkillStaging failure may use only `code`, `message` and `cause`.
   - Confirm that `isShellContentContractFailure()` still lists both enums and still excludes `ScaffoldFailureCode`.

4. Confirm that the baseline is clean and the pinned labels are converted. Serves AC-001 and AC-002, plus the common baseline and message criteria.
   - `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` must contain none of the 20 owned class rows. Remove any stale row by hand, and edit no other row.
   - `ConfigExternalPlatformPackCommandTest` (`ERROR_TYPE`) and `InternalSkillCompanionInstallApplyTest` (`causeClass`) must assert the code labels from `failureCodeLabel()`, not class names.
   - Assumption for implement to confirm: subtask 3 left the goal-planning classes in `InstallShellContentErrors.kt` on purpose. They belong to subtask 4 and are not this subtask's concern.

5. Check the transition condition. This is required by the Shared Rules.
   - The digest lists remaining `ShellContentContractException` subclasses outside this slice. They include `PhaseSlotContractErrors.kt`, `DurableExternalDecodeErrors.kt`, `MalformedJsonTextError.kt`, `ExternalPlatformPackErrors.kt`, `ExternalAddonErrors.kt`, `FailureWireCodeContract.kt`, the execution-plan errors, and `InvalidMcpToolArgumentError.kt`. Shell-content classes owned by subtasks 4–8 also remain.
   - The expected result is that the transition cannot finish. Keep the open `SkillBillRuntimeException`, its codeless secondary constructor, `ShellContentContractException`, `LegacyFailureCode` and the predicate's `is ShellContentContractException` term.

6. Hand off to the owning phases.
   - Test obligations stay empty. The converted assertions already cover wrong-code classification and drift in messages and labels, and no new behaviour is introduced.
   - If verification finds nothing to repair, the implement phase produces no source diff and says so.
   - Validate owns every compile, test, detekt, Spotless (plain clone) and runtime-core repoTest run, including `FailureCodeTotalityArchitectureTest`. This phase and implement run none of them.

Constraints: if a repair is needed, add no module, dependency, typealias, exception property, `@Suppress`, new `runCatching` or `//` comment. `skillbill.error.core` must not import `shellcontent`. Respect detekt limits (ThrowsCount 2, ReturnCount 4, LongMethod 70, CyclomaticComplexMethod 15). Do not let `ArchitectureScanSupport.kt` grow.
