# SKILL-389 Subtask 1 - Repair regressed guards and remove composition forwarders

Parent spec: [.feature-specs/SKILL-389-runtime-core-composition-and-guard-regressions/spec.md](spec.md)
Issue key: SKILL-389

## Scope

Covers F-002, F-003, F-004 and F-005 in runtime-core main and repoTest. (1) Delete three methods from RuntimeRawMapArchitectureTest: the ARCHITECTURE.md prose read (:11-22), the whole-repo 'allow-list machinery is absent' walk (:24-54) and the retired open-boundary annotation guard (:56-64). (2) Anchor ArchitectureScanSupport PACKAGE_PATTERN and IMPORT_PATTERN (:508-509) at column 0, as RuntimeArchitectureTestSupport:933-934 already does. Then restore the fixture lines the named behaviours depend on: the WireVocabulary alias import, the RuntimeContractModuleImportRules Domain allowed-neighbour package and imports, the InlineFqn keep-list import and the RuntimeEnforcementHardening clean-fixture import. (3) Inline RuntimeBootstrapBindings.repositoryEnclosingRootPort, remoteTransportPort and databaseSessionFactory into their RuntimeComponent providers. The databaseSessionFactory provider takes the bound RuntimeVersion and passes its value; RuntimeBootstrapBindings keeps only runtimeContext, which references CanonicalRepositoryRoot directly. The runtime-core test that calls RuntimeBootstrapBindings.remoteTransportPort (AbsentOptionalPortResolutionTest, in skillbill.di.absent or wherever it now lives) calls the RuntimeComponent remoteTransportPort provider instead and keeps its typed-error assertion. (4) Remove the goalRunnerManifestStore, goalRunnerWorkflowOutcomeStore and telemetryConfigStorePort accessors and their runtimeComponentInboundApi rows. The runtime-core tests stop reading them: the snapshot test calls the provider function, and the resolve-only store test is deleted.

## Acceptance Criteria

1. runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeRawMapArchitectureTest.kt contains no reference to ARCHITECTURE.md, no directory walk rooted at the repository root, and no test about the retired open-boundary annotation. Its inner-layer raw-map rule over application, domain and ports and that rule's synthetic fixtures remain.
2. The package and import patterns in ArchitectureScanSupport.kt match only statements starting at column 0 (no leading-whitespace allowance).
3. The WireVocabularyArchitectureTest fixture that tests owner references contains an aliased import of the owner type and asserts that the alias is accepted.
4. The RuntimeContractModuleImportRulesTest fixture for Domain declares a package and imports at least one allowed neighbour, and the test asserts that no violation is reported for those imports.
5. The InlineFqnArchitectureTest keep-list fixture and the RuntimeEnforcementHardeningArchitectureTest clean fixture each contain the import line their assertion depends on.
6. RuntimeBootstrapBindings.kt declares only runtimeContext. RuntimeComponent constructs CanonicalRepositoryRoot, the requester-or-UnresolvedRemoteTransportPortError check and SQLiteDatabaseSessionFactory itself, and passes the bound RuntimeVersion's value rather than reading SkillBillVersion.VALUE for the session factory.
7. RuntimeComponent and PrincipleEnforcementInventory.runtimeComponentInboundApi no longer contain goalRunnerManifestStore, goalRunnerWorkflowOutcomeStore or telemetryConfigStorePort. The exception: an accessor kept because a generated CLI/MCP child component needs it stays, and runtime-kotlin/agent/decisions.md names the generated reader for it.
8. No new module, dependency bag, framework or architecture-test class is added, and no row is added to any file under runtime-kotlin/*/src/repoTest/resources or config baselines.

## Non-Goals

- Relocating runtime-core tests (subtask 2).
- Removing the agentRunService accessor; it is retained for the SKILL-350 executable-lookup test.
- Changing the SQLiteDatabaseSessionFactory constructor or any infra module.
- Wrapping CLI/MCP driven-port use (SKILL-231).
- Editing validator providers or scanner inventories owned by SKILL-387 and SKILL-388.
- Changing any wire output.

## Dependency Notes

Depends on: none. This subtask does not wait for subtask 2 or any other issue.
Apply the changes to the PrincipleEnforcementInventory rows and RuntimeComponent providers present when it runs. If another bundle (SKILL-393) has moved the goal-runner store types into the engine, the accessor removal is unchanged. If a test named here has moved package (subtask 2 or SKILL-392), edit it where it is. The PrincipleEnforcementInventory:166-170 ambient exemption for RuntimeBootstrapBindings.kt stays because runtimeContext keeps the System.getProperty and System.getenv reads.

## Validation Strategy

Read the edited files against each criterion. The build phase compiles runtime-core, runtime-cli and runtime-mcp (the KSP child components prove the accessor removals) and runs the runtime-core repoTest suite.

## Next Path

skill-bill goal SKILL-389

## Spec Path

.feature-specs/SKILL-389-runtime-core-composition-and-guard-regressions/spec_subtask_1_repair-regressed-guards-and-remove-composition-forwarders.md

## Implementation Details

This plan implements subtask 1 only, using the upstream preplan and the current symbols. The planning checkout is on `base/SKILL-380-phase-slot-strategies` at `dced86fd9` and has no tracked dirty changes. The preplan's older revision, line numbers and dirty-file observations are context, not edit targets. Re-read affected files before implementation and preserve changes from concurrent bundles. Test relocation and its placement guard belong to subtask 2.

### Ordered tasks

1. Remove the regressed raw-map guard methods. Serves AC-001 and AC-008.

   In `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeRawMapArchitectureTest.kt`, delete the architecture-prose assertion, the whole-repository retired-machinery walk and the retired open-boundary annotation assertion. Remove imports that become unused. Keep the domain artifact-key rule, the application/domain/ports public raw-map rule and all of that rule's synthetic rejection fixtures. Keep existing scanner and module coverage unchanged. Do not edit the sibling-owned raw-map accessor inventory or recreate a retirement guard elsewhere. This repairs G6 and preserves A12 and G3. No new test is needed for deleting these incidental assertions; validate must run the surviving class.

2. Anchor shared statement scans and restore the four fixtures. Serves AC-002 through AC-005 and AC-008.

   In `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/ArchitectureScanSupport.kt`, change `PACKAGE_PATTERN` and `IMPORT_PATTERN` to begin with `^package` and `^import`, retaining multiline matching and the existing capture vocabulary. Remove the leading-whitespace allowance without broadening another pattern or exemption.

   Restore `import fixture.Owner as AliasOwner` in the owner-reference fixture in `WireVocabularyArchitectureTest.kt`, together with the matching `package fixture` declarations needed to resolve the owner. Keep the `AliasOwner` uses and assert that the scan reports no violations. Preserve SKILL-388's current wire-vocabulary fixtures and inventories.

   Restore `package skillbill.model`, `import java.time.Instant` and `import kotlin.text.Regex` in the Domain fixture in `RuntimeContractModuleImportRulesTest.kt`. Keep the existing forbidden-import rejection assertions for Domain and Ports. Add an explicit empty-violations assertion for an allowed-only Domain source through `forbiddenImportsIn`; the test must prove that allowed imports pass rather than merely omit them from a forbidden result.

   Restore `import java.time.Instant` in the keep-list fixture in `InlineFqnArchitectureTest.kt` and `import skillbill.infrastructure.skills.Foo` in the clean fixture in `RuntimeEnforcementHardeningArchitectureTest.kt`. Keep their existing allowed and forbidden outcome assertions. These restored imports remain inside fixture strings; do not add them as imports of the architecture-test files themselves.

   Extend `RuntimeContractModuleImportRulesTest` with one shared-scanner regression method. The realistic bug is a scanner treating indented package/import text inside a fixture literal as an actual Kotlin statement, producing false architecture violations or the wrong package. Exercise `ArchitectureScanSupport.declaredPackage` and `declaredImports` on source containing real column-zero statements and indented fixture statements. Assert the real package and imports only, and assert no package for an indentation-only control. Call the shared scanner directly rather than testing a copied regex. This protects AC-002 and G5 without adding a test class. Validate must run these four fixture classes and the shared-scanner consumers through runtime-core repoTest.

3. Put construction in the existing component providers. Serves AC-006 and AC-008.

   In `../../../runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt`, return `CanonicalRepositoryRoot` from `repositoryEnclosingRootPort()`, implement the requester-or-`UnresolvedRemoteTransportPortError` check in `remoteTransportPort(ctx)`, and construct `SQLiteDatabaseSessionFactory` in `databaseSessionFactory`. Add the bound `RuntimeVersion` parameter and pass its `.value` to the unchanged adapter constructor. Keep `@Provides @RuntimeSingleton` on the database provider and keep `runtimeVersion()` as the binding that reads `SkillBillVersion.VALUE`.

   In `RuntimeBootstrapBindings.kt`, delete the three forwarding methods and their unused imports. Keep only `runtimeContext`; use `CanonicalRepositoryRoot` directly there. Preserve environment resolution, repository normalization, requester selection and the existing ambient-read exemption. Keep context-reading providers in `di.core` to preserve the existing package-cycle repair. Do not move forwarders into another mixin or create a new helper. Apply A3, A5, A8 and P3.

   Update `AbsentOptionalPortResolutionTest` at its current path, presently `src/test/kotlin/skillbill/di/absent/AbsentOptionalPortResolutionTest.kt`, to call the component's transport provider with an unresolved `TransportContext`. Keep the `assertFailsWith<UnresolvedRemoteTransportPortError>` outcome and the existing bootstrap requester tests. Update all direct database-provider calls, currently four in `RuntimeComponentInvocationSnapshotTest`, to supply the component's bound version. Update the synthetic provider body in `RuntimeComponentInboundApiArchitectureTest` if it still references the deleted bootstrap method, retaining its public-callable assertion. No new forwarding or constructor-structure test is warranted; validate runs the existing behavior and architecture tests.

4. Remove unused exports while preserving useful component evidence. Serves AC-007 and AC-008.

   Remove `goalRunnerManifestStore`, `goalRunnerWorkflowOutcomeStore` and `telemetryConfigStorePort` from `RuntimeComponent` and remove their matching entries from `PrincipleEnforcementInventory.runtimeComponentInboundApi`. Remove imports only when unused. Leave the actual store providers and infrastructure implementations intact. Apply edits to the current inventory without changing SKILL-388's enforcement rows or subtask 2's suppression-support path.

   In `RuntimeComponentInvocationSnapshotTest.kt`, replace telemetry accessor reads with `component.telemetryConfigStore(FileTelemetryConfigStore(resolvedEnvironment))`, using the component-resolved environment before and after the home mutation. Keep assertions that an already-resolved component retains database and telemetry paths after `user.home` changes, and that a fresh component sees the new home. Keep separate-component isolation coverage. In `RuntimeComponentScopedIdentityTest.kt`, delete only the resolve-only store method and its unused imports; preserve the worker coordinator's scoped-identity test. Keep `agentRunService` and its controlled-executable regression coverage.

   Inspect current handwritten CLI/MCP readers and generated `InjectCliComponent` and `InjectMcpComponent` sources as supporting evidence. Fresh build-phase KSP generation must determine whether a removed export is required by a generated child. If provider expansion fails because the child cannot access an infrastructure type, retain only the required accessor and its matching inventory pin, and add a dated entry in `../../../runtime-kotlin/agent/decisions.md` naming the exact generated child reader and type. Stale generated parent exports alone do not justify retention. Do not add adapter dependencies, another component or a dependency bag to force removal. This conditional outcome is permitted by AC-007 and does not block planning.

5. Prepare criterion evidence and hand off validation to its owning phases. Serves AC-001 through AC-008.

   During implementation and audit, inspect the final source and diff against each criterion. Confirm bootstrap has only `runtimeContext`, the factory uses the bound version, the three deleted guard methods are absent, fixture imports and allowed outcomes are present, and export removals match inventory pins or documented generated-reader exceptions. Check that no baseline row, module, dependency bag, framework or architecture-test class was added. Apply the section 5 checklist in `../../../docs/architecture-guidelines.md` with rule IDs, including A1 through A12 and G1 through G7, and preserve other bundles' landed criteria on shared files.

   The existing Validation Strategy remains unchanged, but this phase briefing controls execution ownership. Build is compile/buildability proof only and owns the selected pack build command, fresh core/CLI/MCP KSP output and its cache-bypassing confirmation. Validate owns all tests and full repository checks, including runtime-core repoTest, required formatting checks and the project validation suite. Plan, implement, audit and review must not use test execution or compilation as substitute evidence. Generated outputs remain uncommitted.

### Test obligations and constraints

The test obligations are bounded to real regressions. AC-001 retains public raw-map rejection coverage. AC-002 adds one shared-scanner fixture that catches indented literal statements being mistaken for actual statements. AC-003 restores alias-owner acceptance. AC-004 proves allowed Domain imports pass while filesystem imports still fail. AC-005 restores import-line keep-list controls beside the existing inline-FQN rejection cases. AC-006 and AC-007 preserve missing-requester typed failure, invocation path snapshots, component isolation, scoped coordinator identity and controlled-executable refusal. Validate runs the affected classes and the required repository suites; this plan phase executes none of them.

Do not add tests that merely count provider calls or duplicate constructor wiring. Store persistence behavior stays covered in its owning adapter module. Conditional accessor retention requires generated-reader evidence and a decision entry, not another test-only export.

Keep module dependency edges, kotlin-inject, the single composition root, provider mixins and the typed version binding. Keep CLI/MCP rendering, every wire contract and the database schema unchanged. Add no baseline rows, exemptions, allow-lists, suppressions, source comments or speculative abstraction. Do not edit sibling specs, sibling bundles, validator providers, scanner inventories owned by other bundles or infrastructure implementation files. A moved named test is edited in place without performing its relocation. No migration or feature flag is required.
