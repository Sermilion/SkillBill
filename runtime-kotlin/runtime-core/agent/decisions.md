## [2026-10-01] Extend the existing composition guard for test placement
Context: SKILL-372 moved composition tests back into application and review packages while architecture checks stayed green.
Decision: Add the placement check to RuntimeCompositionGuardArchitectureTest, using a reusable ArchitectureScanSupport scanner and an enforcement-inventory registration. Require a nonempty repository scan and exercise synthetic acceptance and rejection through that scanner.
Reason: The spec requires lasting enforcement without another architecture-test class or framework. G1, G2, G3 and G5 require a registered rule, real scan coverage and fixtures that can expose malformed declarations or namespace escapes. Existing repoTest inputs already cover src/test under G6.

## [2026-10-01] Keep composition tests in existing skillbill.di packages
Context: Runtime-core composition and multi-adapter tests had drifted into application and review packages, with isolated di.absent and di.runtime packages.
Decision: Keep the tests in runtime-core and group them under existing di.workflow, di.featurespec, di.review and di.core packages. Keep the persistence family and its support together under di.workflow.
Reason: The 2026-09-24 placement decision assigns composition and multi-adapter tests to runtime-core under skillbill.di. Matching their composition areas restores A9 and A10 without moving tests across modules or changing their behavior.

## [2026-10-01] Remove component exports without production readers
Context: Three store exports remained on RuntimeComponent, with tests reading them directly or merely checking resolution.
Decision: Remove goalRunnerManifestStore, goalRunnerWorkflowOutcomeStore and telemetryConfigStorePort and their inbound API pins. Use providers or existing test fixtures for test needs.
Reason: Tests alone do not justify production exports. The plan permits retention only for a required generated CLI/MCP child reader; this subtask retained none. agentRunService remains for its executable-lookup regression.

## [2026-10-01] Construct collaborators in the component providers
Context: RuntimeBootstrapBindings forwarded repository-root, transport and database construction, and the database helper read SkillBillVersion.VALUE directly.
Decision: Construct them in RuntimeComponent providers and pass the bound RuntimeVersion to SQLiteDatabaseSessionFactory. Keep runtimeContext in RuntimeBootstrapBindings.
Reason: A3, A5 and P3 require one composition root and deletion of forwarders. Keeping context resolution in di.core preserves the package-cycle repair and the existing ambient-read exemption without moving forwarding code elsewhere.

## [2026-10-01] Scan package and import statements at column 0
Context: Leading-whitespace matching treated indented fixture text as Kotlin statements, while stripped fixture imports no longer exercised alias and allowed-import behavior.
Decision: Anchor the shared statement patterns at column 0, restore the fixture imports, and exercise the shared scanner with real and indented statements.
Reason: G3 and G5 require fixtures that prove acceptance and rejection through the actual scanner. This matches RuntimeArchitectureTestSupport and prevents fixture text from changing detected packages or imports.

## [2026-10-01] Keep raw-map guards tied to live contracts
Context: RuntimeRawMapArchitectureTest pinned architecture prose, walked the whole repository for retired machinery, and checked a retired annotation.
Decision: Delete those assertions while retaining the inner-layer raw-map rule, domain artifact-key rule and synthetic rejection fixtures.
Reason: The deleted assertions tracked incidental prose and retired implementation details. The repository walk also exceeded the guard's declared-input scope under G6. Keeping live boundary checks preserves A12 and G3 without recreating retirement guards.
