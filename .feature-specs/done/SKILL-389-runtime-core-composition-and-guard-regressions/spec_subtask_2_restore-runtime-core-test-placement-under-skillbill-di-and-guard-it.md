# SKILL-389 Subtask 2 - Restore runtime-core test placement under skillbill.di and guard it

Parent spec: [.feature-specs/SKILL-389-runtime-core-composition-and-guard-regressions/spec.md](spec.md)
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

## Implementation Details

Execute the following tasks in order for this subtask only. The upstream preplan supplies the composition boundary and guard requirements. The current clean checkout is on `base/SKILL-380-phase-slot-strategies` and contains 24 runtime-core test Kotlin files. Nine declare packages outside `skillbill.di.*`; two more use `skillbill.di.absent` or `skillbill.di.runtime`. Recheck the files present at implementation time rather than treating that count or the investigation's line numbers as fixed inputs. No dependency work or further decomposition is required.

### 1. Relocate the tests within runtime-core

Serves AC-001 and AC-002. Keep every file in `../../../runtime-kotlin/runtime-core/src/test/kotlin`, with its existing filename, test assertions, fixtures and visibility. Move files and update package declarations according to this mapping. Destination paths use the package's slash-separated directory below that source root.

| Current package and files | Destination package |
| --- | --- |
| `skillbill.application`: `ApplicationPersistencePortTest.kt`, `ApplicationPersistencePortTestSupport.kt`, `ApplicationPersistencePortDecompositionWorkflowTest.kt`, `ApplicationPersistencePortGoalTest.kt`, `ApplicationPersistencePortWorkflowTest.kt` | `skillbill.di.workflow` |
| `skillbill.application`: `DecompositionManifestWriterValidationTest.kt`, `WorkflowServiceRuntimeComponentTest.kt` | `skillbill.di.workflow` |
| `skillbill.application.featurespec`: `FeatureSpecPreparationWriterValidationTest.kt` | `skillbill.di.featurespec` |
| `skillbill.review.review`: `ReviewAccountingDurableRedactionTest.kt` | `skillbill.di.review` |
| `skillbill.di.absent`: `AbsentOptionalPortResolutionTest.kt` | `skillbill.di.core` |
| `skillbill.di.runtime`: `RuntimeDatabasePathCompositionTest.kt` | `skillbill.di.core` |

These destinations reuse existing main composition packages. Keep the persistence test family and its shared support together under workflow composition. Find references to relocated classes and support declarations across runtime-core test and repoTest sources. Add explicit imports where former package-local lookup stops working, and remove redundant imports where a file joins `di.core`. Preserve imported application APIs and generated `skillbill.di.core.create` resolution. Do not replace imports with inline fully qualified references.

Apply the same placement rule to any newly present runtime-core test, including a status concurrency test moved by SKILL-392. Leave already compliant files where they are. Preserve semantic changes made by subtask 1 or another bundle, including optional-port errors, provider calls and invocation-snapshot assertions. This task adds no tests; the moved tests already protect those behaviors.

### 2. Repair the existing suppression path

Serves AC-003 and AC-005. In `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt`, change only the existing `ApplicationPersistencePortTestSupport` suppression row's path to `runtime-core/src/test/kotlin/skillbill/di/workflow/ApplicationPersistencePortTestSupport.kt`. Keep `noopPort`, `UNCHECKED_CAST`, its reason and the number of suppression rows unchanged. If the file has already moved, use its actual compliant location. Confirm the path exists relative to `runtime-kotlin`.

Use the existing `SuppressionBanArchitectureTest` bijection and live-suppression checks as the test obligations for this relocation. They catch the realistic bug where the old path remains allow-listed while the moved suppression becomes unlisted. No additional suppression test is needed. Preserve all unrelated inventory edits from SKILL-388 and SKILL-392.

### 3. Add one placement guard method to the existing composition class

Serves AC-001, AC-002, AC-004 and AC-005. Extend `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeCompositionGuardArchitectureTest.kt` with one test method. Put the small reusable placement scanner in the existing `ArchitectureScanSupport.kt`, and register the rule against `RuntimeCompositionGuardArchitectureTest::class` in `PrincipleEnforcementInventory.enforceableRules`. Keep existing construction rules, fixtures and registrations intact. Add no architecture-test class, module, framework or production declaration.

The method enumerates every Kotlin file beneath `../../../runtime-kotlin/runtime-core/src/test` with `ArchitectureScanSupport.kotlinFilesUnder`, asserts that the resulting file set is nonempty, and checks each source through the same placement-scanner entry point used for synthetic sources. Retain the helper's loud failure for a missing root. Missing or invalid package declarations produce path-bearing violations instead of disappearing from the scan. Accept the exact `skillbill.di` namespace and its dot-separated descendants, reject lookalike prefixes such as `skillbill.diabolical`, and reject the retired `skillbill.di.absent` and `skillbill.di.runtime` packages. Recognize real column-zero Kotlin package statements; indented fixture text must not stand in for a missing declaration. Reuse existing package parsing where possible and keep any required adaptation specific to this placement check. General package/import scanner repairs belong to subtask 1.

The named realistic bug is the SKILL-372 regression that moved composition tests back to application or review packages while architecture checks stayed green. In this one new method, assert no violations for the repository scan and a valid synthetic `skillbill.di.core` source. Assert observable violations for a synthetic application-package file, a missing declaration, a lookalike prefix and each retired package. Route these sources through the real scanner, with diagnostic paths, rather than duplicating its predicate in assertions. Construct fixture strings so real source scans cannot mistake embedded package text for the test class's declaration. These cases protect distinct rejection branches within the single test obligation for AC-004.

The existing `runtime-core/build.gradle.kts` repoTest inputs already include `**/src/**`, so the new scan root requires no build-input change. Satisfy G1 through registration, G2 through the nonempty census, G3 through synthetic rejection, G5 through statement and namespace recognition, G6 through those declared inputs, and G7 through unchanged exemptions and baselines.

### 4. Check the bounded diff and hand off validation

Serves AC-001 through AC-005. Inspect package declarations, source paths, imports, the suppression row, enforcement registration and the new guard. Compare renamed files with rename detection to confirm that existing test logic changed only where imports and package declarations require it. Compare architecture baselines with the implementation-start tree and reject every added row. Do not record or regenerate baselines to accept a violation. Apply the section 5 architecture checklist from `../../../docs/architecture-guidelines.md`, citing A1, A9, A10, A11 and G1 through G7 where these edits affect ownership, placement, visibility and enforcement.

The plan phase performs no compilation, build, test or repository check. The build phase alone owns the pack build command and compile/buildability proof. The validate phase owns test execution, formatting, architecture checks and the full repository gate. The unchanged Validation Strategy's assignment of test execution to build is superseded for execution by this briefing. During validate, run `:runtime-core:test` and `:runtime-core:repoTest`, including the new placement method, `SuppressionBanArchitectureTest` and existing guard-integrity coverage, then complete all required project checks discovered from repository instructions, build configuration and CI. Run these from `runtime-kotlin` with the repository's Gradle wrapper. Keep any `tests_executed` receipt empty until tests actually run in their owning phase.

No new test obligations are needed for mechanical imports, package moves or baseline comparison beyond the existing regression tests and the single placement method. Preserve the three retained borderline tests, current component bindings and accessors, module dependencies, CLI/MCP output, wire contracts and database schema. Do not widen an exemption, add a suppression or baseline row, edit a sibling bundle, or change a parent or sibling spec. Resolve current-tree differences within this subtask's placement scope without waiting for another issue.

## Next Path

skill-bill goal SKILL-389

## Spec Path

.feature-specs/SKILL-389-runtime-core-composition-and-guard-regressions/spec_subtask_2_restore-runtime-core-test-placement-under-skillbill-di-and-guard-it.md
