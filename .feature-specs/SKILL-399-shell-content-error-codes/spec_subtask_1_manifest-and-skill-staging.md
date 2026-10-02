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
