# SKILL-390 Subtask 3 - engine-test-packages-mirror-main

Parent spec: [.feature-specs/SKILL-390-runtime-engine-architecture/spec.md](./spec.md)
Issue key: SKILL-390

## Scope

Covers F-009 from the parent overview. This is a mechanical move inside runtime-engine/src/test, plus the PrincipleEnforcementInventory path pins that name engine test files (PrincipleEnforcementInventory.kt:144,150 pin FeatureTaskRuntimeRunnerTestSupport.kt).

Move each file in the five orphan packages to the main package whose code it exercises:
- `skillbill.engine`: 70 files;
- `skillbill.engine.featuretask.slotbaseline`: 17;
- `skillbill.engine.operation`: 3;
- `skillbill.application`: 1 (DecompositionManifestCommitProjectionTest);
- `skillbill.engine.featuretask.lifecycle`: 1 (FeatureTaskRuntimeCompletedUpstreamRepairArtifactBytesTest).

Update the package and import lines, and move any test resources that are looked up by package-relative path together with their tests.

## Acceptance Criteria

1. Every package declared by a Kotlin file under runtime-kotlin/runtime-engine/src/test/kotlin is also declared by a Kotlin file under runtime-kotlin/runtime-engine/src/main/kotlin, unless its last segment is `testsupport` or `testing`.
2. No Kotlin file under runtime-kotlin/runtime-engine/src/test/kotlin declares `package skillbill.engine`, `skillbill.engine.featuretask.slotbaseline`, `skillbill.engine.operation`, `skillbill.application` or `skillbill.engine.featuretask.lifecycle`.
3. Every engine test file path in PrincipleEnforcementInventory names a file that exists.
4. The subtask changes no file under runtime-engine/src/main. Moved test files differ from their originals only in their path, package line and import lines.

## Non-Goals

- Adding, deleting, splitting or rewriting tests.
- Adding an architecture guard for orphan test packages. The 2026-09-25 decision rejected one.
- Moving tests of other modules.

## Dependency Notes

Depends on: 2
Depends on subtask 2, which edits FeatureTaskRuntimeRunnerTestSupport and the gate-building tests that this subtask moves. It waits for no other issue: move the test files present when it runs. Whichever bundle lands second rewrites package and import lines in the files present, including the six engine/operation tests whose imports SKILL-391 points at `skillbill.engine.operation.core`. Before moving, check the slotbaseline capture and golden lookups for package-relative resource paths.

## Validation Strategy

Build compiles the runtime-engine test and testFixtures source sets and runtime-core repoTest. Validate runs the full check. The engine test count must match the count after subtask 2, and the slotbaseline captures must find their goldens at the moved paths. runtime-core repoTest guards that read PrincipleEnforcementInventory paths must still resolve files. detekt and spotless also run.

## Next Path

skill-bill goal SKILL-390

## Spec Path

.feature-specs/SKILL-390-runtime-engine-architecture/spec_subtask_3_engine-test-packages-mirror-main.md
