# SKILL-389 Subtask 1 - Repair regressed guards and remove composition forwarders

Parent spec: [.feature-specs/SKILL-389-runtime-core-composition-and-guard-regressions/spec.md](./spec.md)
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
