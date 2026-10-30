## [2026-10-30] Keep RuntimeComponent database accessors port-typed
Context: Binding DatabaseSessionFactory and DatabaseReadConnections to one SQLite factory exposed the concrete type on RuntimeComponent's public ABI, which ImplementationOwnershipArchitectureTest forbids.
Decision: sqliteDatabaseSessionFactory is internal. databaseSession and databaseReadConnections are public port-typed accessors backed by the same factory instance.
Reason: Hosts release pooled reads through the port, and the concrete factory stays an infra type.

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

## [2026-10-12] Expose desktop storage snapshots and control admission through runtime composition
Context: The desktop uses the same generated runtime graph for status reads and goal controls. Creating another database graph or assembling engine collaborators in the desktop would split ownership.
Decision: RuntimeComponent exposes databaseSession and workControlService. The inbound API inventory pins both names and still rejects every unlisted property. The desktop repository consumes readIfPresent for strict snapshots and WorkControlService for admitted controls. PackagedDataSmoke also consumes the database session to create its disposable fixture through the supported runtime write path.
Reason: The runtime retains database and control ownership while the desktop owns read-only Room connections and external observation. No ceiling, exemption or boundary rule changes.

## [2026-10-12] Bind work ownership and feature execution in the runtime component
Context: Standalone process identity and exit observation use the existing host supervisor. A generated CLI child cannot construct its concrete host adapter through runtime-core's implementation dependency.
Decision: Expose FeatureTaskRuntimeRunEntry through RuntimeComponent and remove the unused FeatureTaskRuntimeRunner export. Keep workObservationService as the desktop's bounded owner-registration entry. The inbound API inventory pins these production consumers and retains the rejection fixture.
Reason: A1, A3 and A5 keep adapter construction in the existing runtime composition root. The CLI consumes the admitted execution entry rather than depending on host infrastructure. The replacement removes an unused export instead of adding another one beside it.
