# SKILL-389 - runtime-core-composition-and-guard-regressions

## Mode

decomposed

## Execution Rule

Each subtask runs on the current tree. It does not wait for another subtask of this bundle or for any other issue, and it stays green on its own. If a change its acceptance criteria need is missing, it makes it. If another bundle or subtask has already made it, it keeps it. Where another bundle has already moved, renamed or deleted an anchor named here, the subtask applies the same rule to what is present.

## Intended Outcome

Full architectural investigation of runtime-kotlin/runtime-core and its relationship to every other module in the hexagonal graph. This is a spec bundle only; there is no implementation in this phase. The bar is clean/hexagonal architecture, SOLID and YAGNI as practiced at Reddit, Microsoft and Meta. Over-engineering is a finding, and fixes remove layers.

### Judgment

runtime-core is what ARCHITECTURE.md says it should be: the single composition root. It has 30 main files and 1222 lines, all under `skillbill.di.*`, behind one `@RuntimeSingleton @Component abstract class RuntimeComponent(inputRuntimeContext)` with 23 `*Provides` mixins and 49 accessors (`runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt:86-211`).

What is clean:
- no dependency bags, no @JvmSynthetic, no service locator
- only one ambient seam (`RuntimeBootstrapBindings.runtimeContext`)
- every runtime-core baseline is empty
- its only consumers are runtime-cli and runtime-mcp, both `implementation`

The debt is not in the design. It is regression and residue:
- two SKILL-373 fixes were silently undone by SKILL-372 (3973aa265, #403, merged after #402)
- the same squash emptied out synthetic fixtures in 21 repoTest files
- three forwarders and three test-only accessors remain

Every fix below deletes code. The single addition is one test method, which guards the placement rule that regressed without detection.

### Method and baseline

- Baseline HEAD `ae23f4f28` ([SKILL-386] Runtime Cli Architecture, #417).
- Census by grep/find/wc and `git log`/`git diff` over absolute paths. No delegated review. Python execution was blocked in this environment, so the counts come from shell pipelines.
- KSP output (`build/generated/ksp`) is absent in this tree. Generated child-component readers could not be counted; see Limits.

Read:
- AGENTS.md
- runtime-kotlin/ARCHITECTURE.md
- docs/code-principles.md
- runtime-kotlin/agent/decisions.md and history.md
- `.feature-specs/done/SKILL-373-*` (investigation.md, spec.md)
- SKILL-350
- sibling bundles SKILL-387 and SKILL-388
- done SKILL-386

### Census

| Source set | .kt files | lines | other |
|---|---|---|---|
| main | 30 | 1222 | resources/skillbill/version.properties |
| test | 23 | 7476 | - |
| testFixtures | 1 (`skillbill/testing/RepoRoot.kt`) | 17 | - |
| repoTest | 64 | 15296 | 62 baseline files |

Main packages:
- di.core 6 (RuntimeComponent 212, RuntimeBootstrapBindings 65, RuntimeContext 40, RuntimeOptionalCallbackProvides 56, SkillBillVersion 33, RuntimeDiagnosticsProvides 60)
- di.install 4, di.goal 4, di.review 3, di.featuretask 3 (Slot 101, Provides 46, Validator 34)
- di.scaffold 2, di.workflow 2
- di.featurespec 1, di.operation 1 (68), di.telemetry 1 (30)

Build edges (`runtime-core/build.gradle.kts`):
- `api`: application, ports, engine. These types appear in RuntimeComponent's public accessor signatures, which is the kotlin-inject ABI.
- `implementation`: domain, contracts, infra host/contracts/skills/launcher/workflow/http/sqlite, kotlin-inject runtime
- `ksp`: kotlin-inject compiler
- `testImplementation`: testFixtures of application, engine, ports, domain and infra:sqlite
- repoTest inputs at lines 10-26: every `**/src/**`, `**/*.gradle.kts`, `**/ARCHITECTURE.md`, `**/agent/**`, `config/**`, `.editorconfig`

Inbound edges:
- runtime-cli/build.gradle.kts:15 and runtime-mcp/build.gradle.kts:16 (`implementation`)
- runtime-mcp:36 testFixtures
- Both run `ksp(kotlin.inject.compiler)` to generate the child components `InjectCliComponent` and `InjectMcpComponent`. Neither has an infra main dependency, so concrete adapters reach them only through accessors.

Accessor consumers (type references in cli+mcp main):
- GoalRunnerStatusService 13, InstallService 11, InstallNativeAgentLinkPort 9, ExternalAgentAddonSourceConfigPort 8
- ConfigResolutionService 6, ExternalPlatformPackResolutionService 6, FeatureTaskContinuationLookupService 6
- PhaseRunEntry 5, RepositoryEnclosingRootPort 5, RepoValidationGateway 4, InstallMcpRegistrationPort 3, FeatureSpecPathResolverPort 2
- most others 2
- zero: `agentRunService` (:175), `goalRunnerManifestStore` (:183), `goalRunnerWorkflowOutcomeStore` (:184), `telemetryConfigStorePort` (:206). Their only readers are the runtime-core tests (RuntimeComponentScopedIdentityTest:24-30, RuntimeComponentInvocationSnapshotTest:42/55, AgentRunServiceRuntimeComponentTest:38/93) and the pins in `PrincipleEnforcementInventory.runtimeComponentInboundApi` (:627, :644, :646, :671).

Other census results:
- Driven-port consumers in entry points: CLI 20 files, MCP 2. Sanctioned by the SKILL-231 decision (decisions.md ~730-735) and SKILL-386.
- Typealiases: none in main. The import aliases in RuntimeFeatureTaskValidatorProvides are two import-as renames, not typealiases.
- Dependency bags and @Inject constructor vals: none in main.
- Hand-built collaborators, all intentional composition in providers: 5 schema validators (RuntimeFeatureTaskValidatorProvides), 3 in review/workflow, `AgentAddonSelectionResolver(diagnostics)`, `FileSystemRepoLocalConfig(diagnostics)`, `DefaultPhaseRunner(launcher, gitOperations)`, `VerifyDelegatedReviewer(reviewRunner::run)`, and the operation list in RuntimeOperationProvides.
- Wire-token literals and JSON parse sites in main: none. The `version.properties` resource read in `SkillBillVersion` is the single resource read.
- Raw maps in public main signatures: none.
- Package cycles: none within di.* (the runtime-core package-cycle baseline is empty; the SKILL-373 di.core cycle was resolved by collecting context-reading providers in di.core).
- Other modules' real baseline rows are noted for coordination only: infra-skills `nativeagent|scaffold`, ports `persistence|workflow`, engine `featuretask|goalrunner` and `featuretask|work`, domain 4 SCC rows. Their owners handle them.

Test packages:
- skillbill.application: 7 files. ApplicationPersistencePortTest 437, …TestSupport 1507, …DecompositionWorkflowTest 693, …GoalTest 308, …WorkflowTest 633, DecompositionManifestWriterValidationTest 109, WorkflowServiceRuntimeComponentTest 98.
- skillbill.application.featurespec: 1 file. FeatureSpecPreparationWriterValidationTest 129.
- skillbill.review.review: 1 file. ReviewAccountingDurableRedactionTest 312.
- di.absent: 1 file, AbsentOptionalPortResolutionTest 66. There is no main package of that name.
- di.runtime: 1 file, RuntimeDatabasePathCompositionTest 85. There is no main package of that name.
- The remaining 12 files sit correctly in di.core, di.featuretask, di.telemetry, di.goal, di.install and di.review.

### Prior work: SKILL-373 (b33513215, #402, 2026-09-24)

| SKILL-373 AC | Status at ae23f4f28 | Evidence |
|---|---|---|
| AC-1 no @JvmSynthetic, no bags | landed | none in runtime-core main |
| AC-2 single composition root | landed | sanctionedCompositionEntrypoints absent; decision 2026-09-24 |
| AC-3 RuntimeContext family in di.core, not in engine | landed | `di/core/RuntimeContext.kt`; the engine's `PhaseAttempt*RuntimeContext` types are unrelated |
| AC-4 per-module rule iteration | landed for RuntimeCompositionGuard (moduleArchitectureScanCases, non-empty census asserted); other rules not re-opened | RuntimeCompositionGuardArchitectureTest |
| AC-5 baselines without line numbers | landed | rows keyed `path:call:count` |
| AC-6 no prose reads or completed-migration guards | **regressed** | RuntimeRawMapArchitectureTest.kt:11-22, :24-54, :56-64 were re-added by 3973aa265 |
| AC-7 suite in repoTest with declared inputs | landed | build.gradle.kts:10-26 |
| AC-8 runtime-core tests under skillbill.di.* | **regressed** | 9 files outside di.*, plus di.absent and di.runtime; 3973aa265 renamed them back |
| AC-9 docs | not re-verified | - |

Retention decisions kept, with no new evidence against them:
- accessors are the export list, pinned in `runtimeComponentInboundApi`
- `WorkflowGoalRunnerOutcomeStoreDependencies` (SKILL-376)
- the typed `RuntimeVersion` binding with `SkillBillVersion.VALUE` as the single resource read
- the three borderline tests (`SkillBillVersionTest`, `RuntimeExperimentProvidesTest`, `TelemetryLevelMutationServiceTest`)

SKILL-350's retentions are kept: burst schedule constants and the controlled-executable test.

### Principle checklist

| # | Item | Result |
|---|---|---|
| 1 | Dependency direction | Clean: core → application, engine, ports, domain, contracts, infra; only cli and mcp depend on core. |
| 2 | Inbound adapters call use cases | Clean for core. CLI/MCP driven-port use is sanctioned (SKILL-231). |
| 3 | Outbound ports purpose-built | Clean in core. `optionalSharedEvidenceLocatorReadPort` (di/review Evidence) re-exposes a bound port as nullable for runners that accept absence. It is retained: a one-line adapter of nullability, not a layer. |
| 4 | Domain richness | N/A: core holds no rules. |
| 5 | Composition | One root. F-004 forwarders; F-005 dead accessors. |
| 6 | Entry-point leakage | Clean. The IdeStatusReadSnapshotConcurrencyTest follow-up belongs to SKILL-386 and runtime-cli. |
| 7 | Ambient effects | One sanctioned seam, RuntimeBootstrapBindings.runtimeContext (`System.getProperty`, `System.getenv`), exempted at PrincipleEnforcementInventory:166-170. JvmSystemClock, Random.Default and TimeSource.Monotonic are bound once in RuntimeDiagnosticsProvides. |
| 8 | State/transactions | `@RuntimeSingleton` on databaseSessionFactory, the worker supervisor and the attempt recorder only. The scoped identity is tested. |
| 9 | Error model | UnresolvedRemoteTransportPortError is typed. No broad catches in main. |
| 10 | Cohesion/ownership | F-001: core tests of application and review subjects under non-di packages. Per the decision, the multi-adapter ones stay in core under di.*. |
| 11 | YAGNI | F-004 three forwarders; F-005 three test-only accessors; F-002 two completed-migration guards. |
| 12 | Naming/packages | F-001 orphan di.absent and di.runtime. |
| 13 | Guard validity | F-002 out-of-inputs walk; F-003 hollowed fixtures; F-001 has no placement guard. The RuntimeCompositionGuard root (`runtime-core/src/main/kotlin/skillbill/di`) resolves, and the census is asserted non-empty. `kotlinFilesUnder` errors on a missing root. |

### Findings

**F-001 (P1): test placement regressed with no guard.**
- Evidence: the 11 files listed above; 3973aa265 renamed them after #402. PrincipleEnforcementInventory.kt:138 (suppression allow-list: noopPort, UNCHECKED_CAST) pins `runtime-core/src/test/kotlin/skillbill/application/ApplicationPersistencePortTestSupport.kt`.
- Fix: move the 9 files under `skillbill.di.*` packages that match their composition subject (for example di.persistence, di.featurespec, di.review). Fold di.absent into di.core and di.runtime into di.core. Update inventory:138 to the new path. Add one test method to an existing runtime-core composition architecture test, with a synthetic violation, that requires every `runtime-core/src/test` file's package to start with `skillbill.di`.
- Feasibility: these are package renames within the same source set and classpath. Test-internal visibility is unchanged because internal is module-scoped. kotlin-inject is unaffected because tests call `RuntimeComponent::class.create`. Adding a method adds no class.

**F-002 (P1): SKILL-373 AC-6 regressed in RuntimeRawMapArchitectureTest.**
- Evidence:
  - :11-22 reads runtime-kotlin/ARCHITECTURE.md prose
  - :24-54 `raw-map allow-list machinery is absent` walks the whole repository (excluding only .feature-specs and .git) over kt/md/py/yaml/sh. That is outside the repoTest declared inputs, so the build cache can return a stale green.
  - :56-64 guards a retired annotation
- Fix: delete those three test methods. Keep the domain artifact-key test, the inner-layer raw-map rule over application, domain and ports, and its synthetic fixtures.
- Feasibility: deletion only; the remaining tests share no state with them.

**F-003 (P2): hollowed synthetic fixtures.**
- Evidence: 3973aa265 removed 95 indented `package`/`import` lines from string-literal fixtures across 21 repoTest files. The named behaviours that lost their exercising lines:
  - `WireVocabularyArchitectureTest` (~:87-128) lost `import fixture.Owner as AliasOwner`, so alias-import resolution is no longer exercised.
  - `RuntimeContractModuleImportRulesTest` :27-44 lost `package skillbill.model`, `import java.time.Instant` and `import kotlin.text.Regex`, so the "accepts allowed neighbours" half is hollow for Domain.
  - `InlineFqnArchitectureTest` lost its keep-list import line.
  - `RuntimeEnforcementHardeningArchitectureTest` lost its clean-fixture import line.
- Likely cause: `ArchitectureScanSupport.kt:508-509` `PACKAGE_PATTERN = ^\s*package` / `IMPORT_PATTERN = ^\s*import` (MULTILINE) match indented string-literal lines when a rule scans repoTest sources. `RuntimeArchitectureTestSupport.kt:933-934` already anchors at column 0.
- Fix: anchor both patterns at column 0 and restore the fixture lines the four named behaviours depend on.
- Feasibility: ktlint places real package/import statements at column 0, so production matches are unchanged. The fixtures use trimIndent over indented literals.

**F-004 (P2): RuntimeBootstrapBindings forwarders.**
- Evidence:
  - `RuntimeBootstrapBindings.kt:17` `repositoryEnclosingRootPort() = CanonicalRepositoryRoot`
  - :55-56 `remoteTransportPort(ctx) = ctx.requester ?: throw UnresolvedRemoteTransportPortError()`
  - :58-64 `databaseSessionFactory(...) = SQLiteDatabaseSessionFactory(..., SkillBillVersion.VALUE)`
  - Each has a single caller at RuntimeComponent.kt:128-129, :144-145 and :147-154.
  - RuntimeComponent.kt:138 already binds `RuntimeVersion(SkillBillVersion.VALUE)`.
  - `SQLiteDatabaseSessionFactory` (runtime-infra/sqlite …/SQLiteDatabaseSessionFactory.kt:27-33) takes `runtimeVersion: String`.
- Fix: inline the three bodies into their RuntimeComponent providers, keeping `@RuntimeSingleton`. The databaseSessionFactory provider takes the bound `RuntimeVersion` and passes `.value`. RuntimeBootstrapBindings keeps only `runtimeContext`.
- Feasibility: RuntimeComponent already implements runtime-core, which has infra:sqlite and infra:http as `implementation`. `RuntimeVersion` is in runtime-ports (`skillbill.model`), which is an `api` edge. The ambient exemption at PrincipleEnforcementInventory:166-170 names RuntimeBootstrapBindings.kt and stays valid because `runtimeContext` keeps the ambient reads. The SQLite constructor is unchanged.

**F-005 (P3): test-only accessors.**
- Evidence: `goalRunnerManifestStore` :183 and `goalRunnerWorkflowOutcomeStore` :184 were added for SKILL-373 F-004's experiment-parent-delivery inlining, and experiments were deleted by SKILL-378. `telemetryConfigStorePort` :206 is also unread by CLI/MCP main. Readers: RuntimeComponentScopedIdentityTest:21-31 (resolve-only test) and RuntimeComponentInvocationSnapshotTest:42/55, which already calls provider functions such as `component.databaseSessionFactory(...)` directly.
- Fix: remove the three accessors and their `runtimeComponentInboundApi` pins. The snapshot test calls the telemetry-config provider function directly. Delete the resolve-only store test, because the store behaviour is owned and tested in its adapter module.
- Feasibility: kotlin-inject children read a parent accessor when one exists and otherwise inline the provider chain. A generated child that transitively needs one of these types would inline its chain, and that chain could reach infra types missing from the CLI/MCP classpath. So a removal is only valid if the CLI/MCP KSP compile passes. If it does not, the accessor stays, and decisions.md records the generated reader that needs it.

**F-006 (P3, retained): `agentRunService` :175.**
- Evidence: it is test-only (AgentRunServiceRuntimeComponentTest:38/93).
- Why it stays: the test pins the SKILL-350 bug class (an OptionalCallbacks.executableLookup refusal must keep a controlled executable off the launch path). AgentRunService is an @Inject class with no provider function a test could call. Reading it otherwise would need kspTest plus a test component, which is more machinery than one accessor.

### Over-engineering register

| Item | Verdict |
|---|---|
| RuntimeBootstrapBindings forwarders (3) | remove (F-004) |
| Test-only accessors (3) | remove, conditional on generated readers (F-005) |
| Completed-migration guards and prose read (3 methods) | remove (F-002) |
| `optionalSharedEvidenceLocatorReadPort` nullable re-export | keep: one line, lets runners accept absence without a second binding |
| 23 provider mixins | keep: the file-per-area split mirrors ownership, and one class would exceed the line ceiling |
| Explicit strategy and operation registries | keep: SKILL-380 decision (decisions.md:49), operation-list decision (~2358) |

### What stays unchanged

| Item | Reason |
|---|---|
| kotlin-inject, the single RuntimeComponent, 23 mixins | a framework swap or a split is rejected; composition is already one root |
| Accessors as the export list, `runtimeComponentInboundApi` pin | decision 2026-09-24 |
| SKILL-231 direct driven-port resolution in CLI/MCP | decision; SKILL-386 non-goal |
| `RuntimeBootstrapBindings.runtimeContext` ambient seam and its exemption | sole ambient seam (decisions ~751, ~767) |
| `SkillBillVersion.VALUE` single resource read, typed `RuntimeVersion` binding | decision 2026-09-24 |
| RuntimeContext/TransportContext/WorkflowOpsContext/OptionalCallbacks in di.core | SKILL-373 cycle resolution |
| One DefaultPhaseRunner with an instance per strategy, explicit registrations, no multibinding | SKILL-380 decision |
| Explicit OperationRegistry list | decision |
| Goal burst schedule from DEFAULT_ constants | SKILL-350 retention |
| `parallelReviewParseRegister` function binding | SKILL-370 retention |
| Review evidence broker SAM factory | a factory for per-review instances; not a layer |
| Hand-constructed adapters and validators in providers | composition is where construction belongs |
| `WorkflowGoalRunnerOutcomeStoreDependencies` | SKILL-376 |
| `api` edges to application, ports and engine | these types appear in RuntimeComponent's public signatures |
| UnresolvedRemoteTransportPortError | typed error for a missing requester |
| `agentRunService` accessor | F-006 |
| Multi-adapter integration tests in runtime-core under di.*; SkillBillVersionTest, RuntimeExperimentProvidesTest, TelemetryLevelMutationServiceTest | test-placement decision |
| Module name, no Konsist/ArchUnit | a rename or framework adds churn for no gain |
| Other modules' baseline rows | owned by those modules |

### Coordination

- **SKILL-388** (runtime-application boundary repairs, baseline dfb489641): owns the TypedParseBoundary repair, the DiffResolverPort facts, the WireVocabulary inventory seams, the uninstall rendering move and the DecompositionManifestWriterPaths deletion. It touches `PrincipleEnforcementInventory` and WireVocabulary scanner inventories. No ordering: SKILL-389 edits only the `runtimeComponentInboundApi` and test-suppression rows of `PrincipleEnforcementInventory` that are present when it runs.
- **SKILL-387** (prose phase output): deletes response-only schemas, validators and bindings, likely including entries in RuntimeFeatureTaskValidatorProvides. No ordering: SKILL-389 does not edit validator providers.
- **SKILL-386** (done at ae23f4f28): no overlap. Its IdeStatusReadSnapshotConcurrencyTest follow-up stays with runtime-cli.
- **SKILL-372** (done): it caused the AC-6 and AC-8 regressions and the fixture stripping. SKILL-389 undoes only those parts.
- **SKILL-392** (runtime-cli boundary cleanup, pending): its subtask 1 edits `PrincipleEnforcementInventory.kt`, `InjectConstructorDefaultsArchitectureTest.kt`, `ArchitectureScanGuardSupport.kt` and `ApplicationPackageAcyclicityArchitectureTest.kt`; this bundle edits `PrincipleEnforcementInventory.kt` and `ArchitectureScanSupport.kt`. Whichever lands second rebases and keeps both changes. Its subtask 2 moves `IdeStatusReadSnapshotConcurrencyTest` from runtime-cli into runtime-core `src/test` under a `skillbill.di.*` package, which satisfies this bundle's subtask 2 placement guard. It reads the `RuntimeComponent.repositoryEnclosingRootPort()` provider, which F-004 keeps (only the `RuntimeBootstrapBindings` forwarder behind it goes). No ordering: if that test is already in runtime-core when SKILL-389 subtask 2 runs, the placement rule covers it like any other file.
- **SKILL-393** (runtime-ports ownership, pending): moves the goal-runner store types behind `goalRunnerManifestStore` and `goalRunnerWorkflowOutcomeStore` from ports into engine, and removes the ports entry from `rawMapBoundaryAccessors` in `RuntimeArchitectureTestSupport.kt`. SKILL-389 owns the two accessors (F-005) and edits `RuntimeRawMapArchitectureTest.kt` only, not that set. Independent: if SKILL-393 lands first, the accessors import engine types until SKILL-389 deletes them; if SKILL-389 lands first, SKILL-393 has nothing to re-import.
- No global order is required. Each SKILL-389 subtask runs on whatever tree it finds and applies its rules to the files present; on a shared file, whichever change lands second keeps both.

### Limits and what only compiling can confirm

- Generated child-component readers were not counted, because the tree has no KSP output. F-005 is therefore conditional.
- Which rule flagged the stripped fixture lines is inferred, not observed. Only a repoTest run confirms that column anchoring suffices.
- SKILL-373 AC-4 and AC-9 were not re-verified rule by rule.
- The runtime writes spec.md, the subtask specs and the manifest, not investigation.md. This investigation is therefore carried in this Overview.

## Acceptance Criteria

1. Subtask 1's acceptance criteria hold: the regressed RuntimeRawMapArchitectureTest methods are gone, the scan patterns are anchored at column 0, the four fixture behaviours are restored, RuntimeBootstrapBindings declares only runtimeContext, and the three test-only accessors are removed unless a generated child component reads them.
2. Subtask 2's acceptance criteria hold: every runtime-core `src/test` file declares a `skillbill.di.*` package, and an existing repoTest class rejects a synthetic violation of that rule.
3. No new module, framework, dependency bag or architecture-test class, no baseline row added, and CLI/MCP wire output byte-identical.

## Constraints

- No new modules, frameworks, dependency bags or architecture-test classes; extend an existing scanner only where a criterion needs it.
- No architecture baseline gains a row.
- CLI and MCP output, database schema and every wire contract stay byte-identical.
- Keep SKILL-373 and SKILL-350 retention decisions as listed in What stays unchanged.
- Do not edit sibling bundles (SKILL-387, SKILL-388, SKILL-392, SKILL-393).

## Non-Goals

- Wrapping CLI/MCP driven-port use behind application services (SKILL-231 decision).
- Removing the `agentRunService` accessor (F-006).
- Changing any infra module, including the SQLiteDatabaseSessionFactory constructor.
- Editing validator providers or scanner inventories owned by SKILL-387 and SKILL-388.

## Suggested landing order

Across the concurrent runtime architecture bundles, as of 2026-10-01:

1. SKILL-387 (prose phase output) and SKILL-388 (runtime-application): already launched, both blocked at audit on 2026-10-01. Unblock them first if possible.
2. SKILL-389 (runtime-core) **(this bundle)**
3. SKILL-393 (runtime-ports)
4. SKILL-395 (runtime-mcp)
5. SKILL-391 (runtime-contracts)
6. SKILL-392 (runtime-cli)
7. SKILL-396 (runtime-infra)
8. SKILL-397 (runtime-domain)
9. SKILL-390 (runtime-engine)

This order keeps rebases small. SKILL-390 goes last because it touches the most engine files. It is a preference, not a prerequisite: this bundle waits for no other issue and follows the second-lander rules in its Dependency Notes. Run the goals one at a time per checkout, because each one switches branches.

## Validation Strategy

Each subtask's acceptance criteria can be checked by reading the tree: package lines under runtime-core/src/test, the remaining methods of RuntimeRawMapArchitectureTest, the patterns in ArchitectureScanSupport, the restored fixture lines, the members of RuntimeBootstrapBindings and RuntimeComponent, the runtimeComponentInboundApi rows, and the baseline files. The build phase runs the pack build command, including runtime-core repoTest, runtime-cli and runtime-mcp compile, and spotless, to confirm the changes compile and the suite stays green. Wire output is untouched because no contracts, CLI or MCP rendering code changes.
