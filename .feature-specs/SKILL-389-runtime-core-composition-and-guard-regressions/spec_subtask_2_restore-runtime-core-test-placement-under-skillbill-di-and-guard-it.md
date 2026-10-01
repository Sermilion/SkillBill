# SKILL-389 Subtask 2 - Restore runtime-core test placement under skillbill.di and guard it

Parent spec: [.feature-specs/SKILL-389-runtime-core-composition-and-guard-regressions/spec.md](./spec.md)
Issue key: SKILL-389

## Scope

Covers F-001, a mechanical relocation. Move every runtime-core test file that declares a package outside skillbill.di.*. At ae23f4f28 these are the 9 test files in skillbill.application (7), skillbill.application.featurespec (1) and skillbill.review.review (1) into skillbill.di.* packages that name their composition area. Fold di.absent/AbsentOptionalPortResolutionTest and di.runtime/RuntimeDatabasePathCompositionTest into di.core. A file another bundle has added since (for example SKILL-392 moving IdeStatusReadSnapshotConcurrencyTest in) follows the same rule. Update the PrincipleEnforcementInventory suppression allow-list row (:138) to the relocated ApplicationPersistencePortTestSupport path. Add one test method, with a synthetic violating fixture, to an existing runtime-core composition architecture test; it requires every file under runtime-kotlin/runtime-core/src/test to declare a package starting with skillbill.di.

## Acceptance Criteria

1. Every .kt file under runtime-kotlin/runtime-core/src/test/kotlin declares a package starting with skillbill.di.
2. No runtime-core test package is named skillbill.di.absent or skillbill.di.runtime.
3. The PrincipleEnforcementInventory suppression allow-list entry for ApplicationPersistencePortTestSupport points at a path that exists in the tree.
4. An existing runtime-core repoTest architecture test class contains a test method that scans runtime-core/src/test and rejects a synthetic file whose package does not start with skillbill.di. No new architecture-test class is added.
5. No row is added to any architecture baseline file.

## Non-Goals

- Moving tests to other modules; per the 2026-09-24 decision the multi-adapter and composition tests stay in runtime-core.
- Changing test logic beyond package lines, imports and the one inventory path.
- Moving the three borderline tests named in the decision.

## Dependency Notes

Depends on: none. This subtask does not wait for subtask 1 or any other issue; it is a separate subtask only so the mechanical rename does not bury subtask 1's semantic diff in review.
It moves the files present when it runs and changes only package lines and imports, so it composes with subtask 1 in either order. If the suppression-row path in PrincipleEnforcementInventory has already changed, point it at the current location of ApplicationPersistencePortTestSupport. The placement rule mirrors the 2026-09-24 test-placement decision and SKILL-373 AC-8.

## Validation Strategy

Read the package lines under runtime-core/src/test, the inventory row and the new test method. The build phase runs runtime-core test and repoTest.

## Next Path

skill-bill goal SKILL-389

## Spec Path

.feature-specs/SKILL-389-runtime-core-composition-and-guard-regressions/spec_subtask_2_restore-runtime-core-test-placement-under-skillbill-di-and-guard-it.md
