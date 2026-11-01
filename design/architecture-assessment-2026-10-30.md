# Architecture assessment — 2026-10-30

Scope: whole repository, system level. Read-only. HEAD `2d5e03e5f8023780b0365dd7038001e3606ea9ed` at start and at end; no drift.

## 1. Verdict

The hexagonal module split (contracts, domain, ports, application, infra, core, cli, mcp) is sound and worth keeping. The compiler enforces it, and the inner layers mostly respect it. Three weak spots carry most of the change cost:

1. **`runtime-engine` is a monolith with a cycle that no check can see.** It holds 113k of the runtime's 291k main lines. 88 of its 136 packages form one strongly connected component that spans featuretask, goalrunner, diagnostics and operation. 636 of its public symbols have no reader outside the module. The cycle guard skips this module: its baseline file is never asserted, and its "subarea" test scans nothing.
2. **Durable state is an untyped JSON document, and five places own its schema.** These are the domain codecs, the engine accessors, the SQLite `json_extract` projections, 58 YAML schemas with 112 Kotlin version constants, and the desktop's own SQL. This is the root cause of the recurring wire-vocabulary, raw-map and `Any` findings (A6, A12). It also ties every migration to the desktop: `DesktopReadSchema.VERSION` was bumped 17 times in 12 days.
3. **The governance machinery is large but misses the rules that matter most.** It is 15,980 lines of guard code, 65 baseline files, a 2,826-line `ARCHITECTURE.md` and 270 decision entries. Exact-package acyclicity (A9) is checked in only 3 of 15 modules. Three feature merges since the round-4 squash re-added edges that SKILL-390 had removed, and every guard stayed green.

Keep the module structure. Make three changes: break the engine cycle, then split the engine in two; give the persisted document one codec owner; and replace the desktop's direct database reads with runtime service calls. The rest is deletion.

## 2. Baseline

HEAD at start and end: `2d5e03e5f`. The working tree was clean. The KSP-generated components (`InjectRuntimeComponent.kt`, 8,652 lines) were built at 08:11, after the last commit at 07:36, so they are current and were counted as readers.

### 2.1 Lines per module and source set (`.kt` files)

| Module | main | test | testFixtures | repoTest |
| --- | ---: | ---: | ---: | ---: |
| runtime-engine | 113,070 (723 files) | 91,267 | 2,534 | – |
| runtime-domain | 42,390 (432) | 23,521 | – | – |
| runtime-infra:skills | 28,008 (244) | 23,864 | 228 | 6,014 |
| runtime-infra:sqlite | 24,818 (171) | 19,506 | 714 | – |
| runtime-application | 20,943 (192) | 17,773 | 2,091 | 44 |
| runtime-cli | 14,145 (133) | 17,472 | – | 3,441 |
| runtime-infra:workflow | 12,387 (102) | 11,577 | – | 42 |
| runtime-ports | 9,263 (330) | 479 | 2,632 | – |
| runtime-infra:contracts | 7,105 (63) | 6,754 | – | 828 |
| runtime-infra:launcher | 5,239 (43) | 4,543 | – | – |
| runtime-contracts | 4,950 (131) | 360 | – | – |
| runtime-infra:host | 3,384 (36) | 1,696 | 121 | – |
| runtime-mcp | 2,322 (28) | 4,180 | – | 1,348 |
| runtime-infra:http | 1,689 (13) | 907 | – | – |
| runtime-core | 1,643 (36) | 8,082 | 17 | 15,980 |
| **runtime total** | **291,356** | | | |

Engine subareas (main): `featuretask` 65,814 lines (430 files; `slot` 19,213, `runloop` 15,169, `lifecycle` 7,667), `goalrunner` 30,388 (176), `diagnostics` 6,082, `work` 5,274, `operation` 3,984, `specbrowser` 962.

Desktop (`desktop-tray`), main lines: `composeApp` 4,108 (commonMain 827 + jvmMain 3,281; the census's 8.6k figure included 4,513 test lines), `core:data` 4,519, `core:ui` 1,286, `core:domain` 778, `feature:detail` 2,403, `feature:specs` 1,646, other features 235–674 each.

Other: `install.sh` 2,736 lines and `uninstall.sh` 992 lines of Bash; `skills/skill-bill/content.md` 564 lines; 9 platform packs with 149 files; 58 schemas in `orchestration/contracts/`.

### 2.2 Gradle edges (project dependencies)

runtime-kotlin:

| Configuration | Count | Notes |
| --- | ---: | --- |
| `api` | 11 | application, engine and ports re-export contracts, domain and ports; core re-exports application, ports and engine |
| `implementation` | 51 | core → all 7 infra modules; infra → infra: skills → host, contracts; launcher → skills, host; workflow → skills, contracts, host |
| `testImplementation` | 41 | mcp tests → `runtime-cli` (an adapter-to-adapter edge) and `testFixtures(runtime-core)`, which is 17 lines |
| `testFixturesImplementation` | 19 | engine fixtures → infra:sqlite and infra:contracts |

desktop-tray: `composeApp` → 6 core and 6 feature modules. Each feature → core:domain, core:designsystem, core:ui. No feature-to-feature edges. `core:data` and `composeApp` take `runtime-core` and `runtime-contracts` as `compileOnly`, and runtime-core's `api` closure (application, engine, ports, domain, contracts) as their ABI. Tests add `runtime-infra-host`, `runtime-infra-contracts` and `testFixtures(runtime-infra-sqlite)`.

### 2.3 Coupling metrics (file granularity, main)

Ca = main files in other modules that import this module. Ce = files in this module that import another runtime module. I = Ce/(Ca+Ce). A = abstract types / all types. D = |A+I−1|.

| Module | Ca | Ce | I | A | D |
| --- | ---: | ---: | ---: | ---: | ---: |
| runtime-contracts | 967 | 0 | 0.00 | 0.02 | **0.98** |
| runtime-domain | 1,131 | 178 | 0.14 | 0.04 | **0.83** |
| runtime-infra:host | 69 | 29 | 0.30 | 0.05 | **0.66** |
| runtime-application | 187 | 165 | 0.47 | 0.06 | 0.47 |
| runtime-ports | 915 | 115 | 0.11 | 0.43 | 0.46 |
| runtime-infra:contracts | 31 | 46 | 0.60 | 0.00 | 0.40 |
| runtime-infra:http | 5 | 11 | 0.69 | 0.00 | 0.31 |
| runtime-core | 8 | 34 | 0.81 | 0.05 | 0.14 |
| runtime-infra:workflow | 13 | 93 | 0.88 | 0.00 | 0.12 |
| runtime-infra:launcher | 5 | 37 | 0.88 | 0.06 | 0.06 |
| runtime-infra:skills | 14 | 194 | 0.93 | 0.02 | 0.05 |
| runtime-cli, runtime-mcp | 0 | 106 / 27 | 1.00 | ~0 | ~0 |
| runtime-engine | 46 | 624 | 0.93 | 0.09 | 0.02 |
| runtime-infra:sqlite | 3 | 128 | 0.98 | 0.00 | 0.02 |

Four modules sit furthest from the main sequence:

- contracts and domain are in the zone of pain (stable and concrete). That is normal for a shared kernel. The problem is what they contain: domain holds volatile wire codecs (F-004), and contracts holds 48 exception classes and a desktop Room hash (F-011).
- infra:host is a shared library for three other infra modules (69 inbound files).
- The engine has the metrics of a leaf adapter (I = 0.93), yet it holds the core use cases.

### 2.4 Structural censuses (main)

| Census | Result |
| --- | --- |
| Exact-package nontrivial SCCs | engine 88 + 2; sqlite 28; skills 20 + 4 + 2; application 7 + 3 + 2 + 2 + 2; ports 4 + 2 + 2; infra:contracts 4; host 3; workflow 3 + 3; launcher 2. **19 SCCs in total.** domain, contracts, cli, core, mcp and http have none. |
| Packages split across modules | 1: `skillbill.model` in both domain and ports |
| Public top-level symbols with no reader in another module (main, generated code and tests counted) | engine 636 of 940 (284 functions, 352 types); domain 112; infra:contracts 57; infra:skills 53; application 34 |
| Open interfaces: main implementations / test substitutes | engine: 151 open, 65 with one implementation, 53 of those with no test substitute; ports: 200 open, 118 with one implementation, 30 with no substitute |
| `typealias` | 7: skills 5 (`DirName = Pair<String,String>` and similar), mcp 1, ports 1 (`MergeStatePullRequestIdentityLookup = PullRequestIdentityLookup`) |
| `@Inject` constructors with more than 8 parameters | 31: engine 27, application 3, cli 1. The largest have 11 (`IdeStatusService`, `GoalRunner`, `DefaultGoalRunnerQaGate`, `StandalonePlanRun`, `WorkflowService`, `InstallService`, …). None exceeds the A3 limit of 12. |
| Files over 600 lines | 32: engine 27, sqlite 3, domain 1, application 1. Three engine files have 1,177–1,199 lines against `PRODUCTION_LINE_CEILING = 1200` (`PrincipleEnforcementInventory.kt:662`); the next largest has 950. |
| Custom `Throwable` subclasses (from `custom-throwable-baseline.txt`) | 66: contracts 48, host 6, launcher 6, domain 3, mcp 1, skills 1, workflow 1 |
| Broad catches | 485 `runCatching` (engine 185, skills 78, launcher 55, workflow 45, application 40, host 32, sqlite 22); 4 `catch (Exception)`; 191 `catch (SkillBillRuntimeException)` (engine 76); 14 `catch (IllegalArgumentException/IllegalStateException)` (engine 7, http 5) |
| SQL text in engine and application | 0. All SQL is in infra:sqlite, plus the desktop's own queries (F-005). |
| JSON handling outside adapters | `JsonCodec.` used in engine 85 files, domain 48, application 10. `Map<String, Any?>` appears in engine 96 files, domain 87, application 22. |

## 3. Prior work audit

All round-4 bundles (SKILL-386 to 399, plus 402) landed together in one squash commit, `5a17798c1` (2026-10-04, "[SKILL-380] Goal workflow repairs and runtime module cleanup", 2,101 files, +77,521 / −37,150). That commit also carried feature work for SKILL-380, 384 and 385. No per-bundle commit exists, so P4 (verify after landing) could not be run per bundle. Two different bundles use the key SKILL-425. Commit `b2f4f5099`, titled "DO NOT MERGE [SKILL-441]", is on `main`.

| Criterion | Status | Evidence |
| --- | --- | --- |
| SKILL-390 AC1: no engine `typealias` | Landed | 0 in engine main |
| SKILL-390 AC2: ≤12 constructor parameters | Landed | max 11 |
| SKILL-390 AC3: no `System.nanoTime` in engine | Landed | 0 |
| SKILL-390 AC3: no inline `trim().uppercase()` issue-key canonicalisation | **Regressed** | `goalrunner/intake/LegacySpecIntake.kt:107`, added by `b2f4f5099` (SKILL-441, 2026-10-24) |
| SKILL-390 AC4: engine cycle baseline empty; no featuretask file imports goalrunner or work | **Regressed / never landed** | The baseline is empty but no test reads it (F-001). Six featuretask files import goalrunner or work: `runloop/durable/FeatureTaskRuntimeRunLoopDurableState.kt`, `runloop/durable/DurablePhaseRunAdapters.kt`, `runner/FeatureTaskRuntimeDurableRunFactory.kt`, `lifecycle/execution/FeatureTaskRuntimeExecutionAdmission.kt`, `phaserun/PhaseRunIntakeResolver.kt`, `slot/intake/RuntimeIntakeStrategy.kt`. They were added by `5a17798c1` (the landing squash itself), `1d9ed2fd9` (SKILL-425), `b2f4f5099` (SKILL-441) and `1ef0c125b` (SKILL-446). |
| SKILL-390 AC5: inject-property rule covers the engine | Landed | `InjectConstructorDefaultsArchitectureTest.kt:20,66` |
| SKILL-391 AC1: `InvalidMcpToolArgumentError` is only in mcp | Landed | `runtime-mcp/.../shared/InvalidMcpToolArgumentError.kt` |
| SKILL-391 AC6: operation errors | Superseded by SKILL-398 | `skillbill/error/operation` is gone |
| SKILL-393 AC2: no engine alias over ports | Landed | However, a new pass-through alias appeared in ports: `MergeStatePullRequestIdentityLookup` (bc284b0bd, 2026-10-12), 5 references (A5) |
| SKILL-393 AC5: ports cycle baseline empty | **Vacuous** | First-segment scan. Exact SCCs: `ports.featuretask.model ↔ ports.idestatus.model ↔ ports.taskruntime(.model)`, `ports.review.evidence ↔ ports.review.model`, `ports.process ↔ ports.process.model` |
| SKILL-396 AC1, AC3, AC4 | Landed | 0 infra → application imports; 0 defaulted `RuntimeDiagnostics` in sqlite; 0 `System.getProperty` in skills |
| SKILL-397 AC1: no public declaration typed exactly `Any` | **Partial** | 7 public domain functions take `artifacts: Any` (`GoalTerminalOutcomeDerivation.kt:152,158`, `AttemptLedgerAccumulator.kt:78`, `AttemptLedgerDecoding.kt:18,24`, and others). The guard fixture asserts that `fun decode(raw: Any)` is **not** reported (`RuntimeRawMapArchitectureTest.kt:100,111`). |
| SKILL-397 AC5: domain SCC baseline empty | Landed | 0 exact SCCs in domain |
| SKILL-425 AC4, AC9 | Landed | 0 references to `suppressDecomposition`, `specBundleRequired`, `GoalPlanningPhaseRunState`, `DefaultGoalPlanningSweep` |
| SKILL-425 AC8: nothing writes `goal_*` planning tables | Landed | The 14 `INSERT`s are in migrations only |
| SKILL-425 sweep removal | **Moved, not deleted** | `goalrunner/planning/sweep/GoalPlanningSweepConstants.kt` re-exports two `FeatureTaskRuntimePhaseWorkflowDefinition` constants. Its one reader is in featuretask, where it forms a back-edge (P3). |

Pattern: fixes that a working guard enforces have held (typealias, ambient, inject properties). Fixes whose guard reads nothing, or reads at the wrong granularity, regressed through feature work within three weeks.

## 4. Principle table

| Principle (rule) | Verdict | Worst instances | Counts |
| --- | --- | --- | --- |
| Dependency direction, hexagonal (A1) | Partial | Domain holds serialization: 48 files use `JsonCodec`, and the artifact wire mappers live in `workflow/taskruntime/artifact/*Wire*.kt`. runtime-contracts holds `DesktopReadSchema.ROOM_IDENTITY_HASH` (desktop vendor vocabulary). Engine → application is used as a library (118 files, 76 imports from `application.decomposition`). | 0 infra → application; 0 SQL in engine or application |
| Acyclic dependencies at exact-package level (A9) | **Broken** | engine has one 88-package SCC; sqlite 28; skills 20 | 19 SCCs, 0 failing guards |
| Stable dependencies, stable abstractions | Partial | contracts D = 0.98; domain D = 0.83 with volatile codecs inside | §2.3 |
| SRP | Partial | `IdeStatusService` (1,198 lines, 5 public entry points: CLI status, desktop board, board selection, detail, wire map; plus repository-identity derivation and candidate collection). `AcceptanceCriterionRepairPersistence` (949 lines: repair state machine, JSON encoding, size budget at line 514, legacy adoption) | 32 files over 600 lines |
| OCP | Partial | Adding a platform pack still means editing `NormalizedStackLabel.kt:83-95` (hard-coded slugs; includes java, ruby and docs, which have no pack, and omits rust and typescript, which do) and `ScaffoldCatalog.kt:72-85` (Kotlin/KMP baseline branch). Adding a phase means a new `Phase*StepBinding` interface plus a class in the 1,176-line `FeatureTaskRuntimeRunLoopStepBindings.kt`. | 2 identity-branch sites |
| LSP | Partial | `GoalRunnerQualityExecutionStore` default bodies throw `UnsupportedOperationException` (`GoalRunnerWorkflowOutcomeStore.kt:303,318`); the MCP mapper throws on the decomposition arms (`WorkflowMcpResultMappers.kt:28`) | 3 sites |
| ISP | Holds, at a cost | Ports are split into role interfaces (`BlockIncident*Reads/Writes`); the slot capability interfaces deliberately restrict strategies | 53 single-implementation engine interfaces without a test substitute, 30 in ports |
| DIP, one composition root (A3) | Holds | No bags were found by the guard. The desktop instantiates `RuntimeComponent` through `skillbill.di.core.create` as a third root user, which is allowed. | 31 constructors with >8 parameters |
| YAGNI / KISS (A5) | Partial | Room in the desktop for one entity (F-005); 92-name and 62-name hand lists of allowed API (F-006); 2 forwarders/aliases (F-010) | §6 |
| DRY with judgment (A6) | Partial | Candidate SQL exists twice (runtime `WorkBoardCandidateStore.kt`, 68 lines; desktop `WorkCandidateQueries.kt`, 172 lines). Build-identity equality is implemented twice (`install.sh` `build_identities_match` and desktop `BuildIdentityRecord.kt`). | 2 restated policies across builds |
| Law of Demeter, tell-don't-ask | Holds in sampled code | Constructor bags are gone (A3 guard). Binding classes expose 68 accessor-style overrides to strategies. | – |
| Error model (A7) | Partial | 485 `runCatching` sites rely on a convention of `rethrowUnless` (107 calls) and `rethrowIfCooperative…` (29) to keep cancellation. `runCatching{…}.getOrNull()` turns failure into absence (`GoalRunnerPurgeSpecBundleReset.kt:83`, `IdeStatusRepositoryCorrelation.kt:72`). The tier-3 exception is caught at 191 sites. | 66 custom throwables (down from 231) |
| Ambient effects (A8) | Holds in substance | Inner layers are clean. `System.nanoTime` (18 reads) and `ProcessHandle` (28) appear only in host, launcher and workflow adapters, but A8's text forbids them everywhere and the scan forms omit both (`ArchitectureScanGuardSupport.kt:48-58`). | 5 baseline rows, 2 exemptions |
| State and transactions | Partial | 64 tables, all owned by sqlite. Workflow state is an opaque `artifacts_json` document (83 references in sqlite) whose schema the engine and domain own. Writes go through `RuntimeOwnedPersistenceBoundary.requiredOwningWrite`; no unguarded multi-step write was found in the files read. | – |
| Encapsulation (A11, A12) | **Broken** in the engine | 636 engine public symbols with no outside reader; 7 public `Any` parameters in domain | §2.4 |
| Test value and placement (A10) | Partial | runtime-core tests (8,082 lines) are wired integration tests over application and real adapters, and runtime-core is the only module whose classpath allows them, so their placement is correct. runtime-contracts has 360 test lines for 4,950 main lines of declarations, which is adequate. Guard tests assert structure by design. No timing tests were inspected. | – |

## 5. Findings

### F-001 — P1 — Exact-package acyclicity is not enforced in 12 of 15 modules, and the engine has no cycle test at all

- **Rule:** A9, G2, G3, G4, G5.
- **Evidence:**
  - `PrincipleEnforcementInventory.kt:43` selects `EXACT_PACKAGE_SCC` only for domain, contracts and cli. Every other module uses `FIRST_SEGMENT_MUTUAL_PAIR` (`ArchitectureScanSupport.kt:779-818`, with the collapse at lines 798 and 807 and `mutualImportCyclesForEdges` at line 977). That mode collapses packages to the first segment after the prefix and reports only two-node mutual imports. The 19 exact SCCs in §2.4 all pass.
  - `ApplicationPackageAcyclicityArchitectureTest` has a hand-written test method per module for 13 modules and none for runtime-engine. `runtime-engine-package-cycle-baseline.txt` is recorded by `ArchitectureBaselineRecorder.kt:45-47` but never asserted (G4 violation; this is the same failure as SKILL-373 F-001 and SKILL-378 F-008).
  - The test "engine subareas have an empty package cycle baseline" (`ApplicationPackageAcyclicityArchitectureTest.kt:439-456`) passes the prefix `skillbill.engine.featuretask` without a trailing dot. Every package then maps to `".x"`, its first segment is blank, and the file is skipped (`ArchitectureScanSupport.kt:802`). The test reads files but cannot fail (G2, G3).
  - `runtime-infra-skills-package-cycle-baseline.txt` holds a cycle row, `nativeagent|scaffold`. A9 says cycles are never baselined.
- **Why it matters:** this is the mechanism behind the SKILL-390 AC4 regression: three feature merges re-added featuretask → goalrunner edges with every guard green. Any engine package can now depend on any other, so no part of the engine can be changed or extracted on its own.
- **Fix (end state):**
  - `PackageCycleGranularity` and `FIRST_SEGMENT_MUTUAL_PAIR` exist in no source set.
  - Package-cycle checking is one parameterized test over `PrincipleEnforcementInventory.moduleArchitectureScanCases` that asserts an empty SCC set and a non-empty scanned-package set.
  - No `*-package-cycle-baseline.txt` file exists, and the engine-subarea test method exists in no source set.
- **Feasibility:** the test can flip a module only once that module's cycles are broken. Each module's flip is independent (WP-1, WP-2, WP-5). The rule change itself touches only repoTest code.

### F-002 — P1 — runtime-engine is one 88-package cycle; featuretask and goalrunner cannot be separated

- **Rule:** A9, A1 (charter), acyclic dependencies.
- **Evidence:**
  - The SCC covers 20 `featuretask.slot` packages, 13 `runloop`, 7 `lifecycle`, 19 goalrunner packages, 3 `diagnostics.block` packages, and parts of operation, recovery and worktreeedit.
  - Back-edges from featuretask:
    - 5 files import goalrunner: `GoalRunnerGoalChildHistoryDraftStore` (2 files), `GoalPlanningSweepConstants`, `PlanIntakeSettlement`, `FeatureTaskRuntimePlanIntake`, `LegacySpecIntake`, `LegacySpecRoute`;
    - 1 file imports `engine.work`;
    - 3 files import `operation` (9 go the other way);
    - 50 import `diagnostics` (8 go the other way).
  - In the forward direction, 50 goalrunner files import featuretask.
- **Why it matters:** the goal runner is a client of the feature-task run loop, but the run loop reaches back into goal intake, goal planning constants and goal history. Changing either part means reading both. This also blocks F-003.
- **Fix (end state):**
  - No file under `skillbill/engine/featuretask` imports `skillbill.engine.goalrunner`, `skillbill.engine.work` or `skillbill.engine.operation`.
  - `GoalPlanningSweepConstants` exists in no source set; its reader uses `FeatureTaskRuntimePhaseWorkflowDefinition` directly.
  - The intake types (`FeatureTaskRuntimePlanIntake`, `PlanIntakeSettlement`, `LegacySpecIntake`, `LegacySpecRoute`) are declared under `featuretask/slot/intake`, because SKILL-425 made intake a feature-task slot.
  - The goal-child history draft is written through an interface that featuretask declares and goalrunner implements, or it is written only at goal finalization (SKILL-446 already moved history writing there).
  - Then the diagnostics ↔ featuretask edges are resolved by moving the 8 diagnostics files that read featuretask types into featuretask.
- **Feasibility:** the work is within one module. kotlin-inject resolves by type, not by package, so the generated `InjectRuntimeComponent` only needs the moved types to stay public. No module import rules apply.

### F-003 — P2 — The engine is a module in name only: no `internal` boundary, oversized coordinators

- **Rule:** A11, SRP, A1 (charter), component cohesion.
- **Evidence:**
  - 113,070 main lines (39% of the runtime) in 723 files.
  - 284 public top-level functions and 352 public types with no reader in another module, after counting generated components and other modules' tests.
  - 27 of the 31 constructors with more than 8 parameters.
  - 27 of the 32 files over 600 lines. `IdeStatusService.kt` (1,198), `DefaultGoalRunnerQaGate.kt` (1,193) and `FeatureTaskRuntimeRunLoopStepBindings.kt` (1,176) sit just under the 1,200-line ceiling.
  - The charter says the engine holds "the run loop, goal runner, planning and the pinned inbound API", yet it also holds the desktop and CLI read model (`work/`, 5,274 lines), the spec browser, verify operations and block-incident diagnostics.
- **SRP of the named classes:**
  - `IdeStatusService` has five entry points for three clients and its own repository-identity derivation (`repositoryIdentityFor`, line 794, and the three helpers after it). Several reasons to change.
  - `DefaultGoalRunnerQaGate` has one public method and 31 private steps that hand-code a resumable state machine (`prepareQuality`, `reuseSettledQuality`, `resumeValidation`, `settleResumedValidation`, …). One reason to change, but its states are implicit.
  - `FeatureTaskRuntimeRunLoopStepBindings` holds 19 types and 68 overrides that implement the per-phase `Phase*StepBinding` interfaces. One reason to change (strategy capabilities), but every new phase lands in this file.
  - `AcceptanceCriterionRepairPersistence` mixes the repair transition, document encoding and a byte budget.
- **On "Persistence" in engine class names:** SQL did not leak into the engine (0 SQL strings). Document encoding did (F-004).
- **Why it matters:** with 636 symbols public by default, any engine file can bind to any other, and nothing outside review stops it. The 1,200-line ceiling acts as a target: growth stops at 1,199 lines instead of at a responsibility boundary.
- **Fix (end state):**
  - After F-002, the goal runner (`skillbill.engine.goalrunner.**`, 30k lines) is a separate Gradle module that depends on the run-loop module, and no run-loop file references a goal-runner type.
  - Each engine module compiles with `kotlin { explicitApi() }`, so a declaration without a cross-module reader is `internal` by compiler check, not by guard.
  - `IdeStatusService` exposes one read model per client: the board and detail read used by the desktop, and the CLI status read. Repository-identity derivation is declared once.
- **Feasibility:** kotlin-inject components are generated in runtime-core, so every type in the graph must stay public; `explicitApi()` forces those to be declared `public` explicitly. runtime-cli and runtime-mcp import 32 and 2 engine files, all on the pinned inbound API, so the split only needs the pinned list partitioned. This adds a module. A no-new-module alternative is in §8.

### F-004 — P2 — Durable workflow state is an untyped JSON document whose schema has five owners

- **Rule:** A1 (domain: no serialization), A6, A12, single owner of a contract.
- **Evidence:**
  - `feature_task_workflows.artifacts_json` and other `*_json` columns are referenced 83 (`artifacts_json`), 25 (`control_state_json`), 21 (`payload_json`) and 19 (`steps_json`) times in sqlite main.
  - Encoding and decoding are spread across domain (48 files using `JsonCodec`, e.g. `workflow/taskruntime/artifact/FeatureTaskRuntimeWorkflowArtifactWire*.kt`, `phaseartifacts/FeatureTaskRuntimePhaseArtifactDecoders.kt`), engine (85 files), sqlite (35), application (10) and the desktop SQL (`WorkCandidateQueries.kt:76`, `json_extract(child.artifacts_json, '$.feature_task_runtime_goal_continuation.parent_workflow_id')`).
  - 58 YAML schemas back it, mirrored by 112 `*_CONTRACT_VERSION` constants across 9 modules. Every schema is loaded only by runtime-infra:contracts. Of the 58, the agent-facing ones (phase output, audit result, review context, platform pack, agent add-on, issue key) have an external producer. Most of the rest (`acceptance-criterion-repair-state`, `goal-quality-execution`, `goal-subtask-review-state`, `qa-repair-state`, `feature-task-runtime-persistence`, …) are written and read only by the runtime itself.
  - 7 public domain functions take `artifacts: Any`.
- **Why it matters:** adding an artifact field touches a YAML schema, a version constant, a domain codec, an engine accessor, possibly a sqlite projection and possibly the desktop SQL. Every past round found restated keys (A6, 5 to 7 per round) and raw maps (A12) in exactly this path; the guards police the symptoms one seam at a time (38 hand-listed wire seams). Self-owned durable state validated by JSON Schema at runtime pays twice: once for the schema and once for the Kotlin types.
- **Fix (end state):**
  - No runtime-domain main file imports `skillbill.contracts.JsonCodec` or declares `Map<String, Any?>` in a public signature.
  - One adapter (runtime-infra:sqlite) encodes and decodes durable artifacts to typed domain records, behind the existing `WorkflowStateRepository` port.
  - The schemas for runtime-internal state are replaced by `kotlinx.serialization` types with a single version field.
  - Fields used in queries (goal-continuation parent id, issue key, repository identity) are typed columns, and no source set outside sqlite contains `json_extract`.
- **Feasibility:** domain bans `java.nio` and `skillbill.ports`, and codecs are pure, so they can move outward into sqlite. runtime-contracts already depends on `kotlinx.serialization.json` (`runtime-contracts/build.gradle.kts:7`). Quarantine-and-regenerate (ARCHITECTURE.md §"Quarantine-and-regenerate") covers old rows, and the 2026-10-17 decision says no backward compatibility is required. Migrate one artifact family per change.

### F-005 — P2 — The desktop treats the runtime database as an unversioned public API and restates runtime read policy

- **Rule:** A6, A1 (contracts holds no adapter vocabulary); desktop decision [2026-10-12] "Read work through the runtime store".
- **Evidence:**
  - The desktop refuses the store unless the latest migration equals the compile-time constant (`core/data/.../store/JvmWorkStoreAccess.kt:38`). That constant is `DesktopReadSchema.VERSION = 86` (`runtime-contracts/.../persistence/DesktopReadSchema.kt:4`), and it was bumped 17 times between 2026-10-13 and 2026-10-25.
  - Room's identity hash is a runtime-contracts constant (`DesktopReadSchema.kt:6`), installed by runtime migration 52.
  - The Room database declares one entity, `schema_migrations` (`WorkRoomDatabase.kt:11-27`), plus one `@RawQuery`.
  - That raw query (`WorkCandidateQueries.kt`, 172 lines) reads 9 runtime tables, JSON paths and runtime repository-identity policy (`IdeStatusRepositoryIdentitySource.GOAL_CHILDREN`, `STANDALONE_REGISTRATION`). The runtime has its own candidate query (`runtime-infra/sqlite/.../worklist/WorkBoardCandidateStore.kt`).
  - Desktop main imports 72 distinct runtime symbols from contracts, di, engine, error, goalrunner, idestatus, ports and workflow packages.
  - The desktop already reads board and detail through `IdeStatusService` (`core/data/.../runtime/RuntimeWorkAdapter.kt:203,228`).
- **Why it matters:**
  - Every migration requires a desktop rebuild, even when it touches no table the desktop reads (see memory: a new migration bumps `DesktopReadSchema`; a worktree install advances the shared DB).
  - A schema change that does affect the candidate SQL compiles in the runtime build and fails only in the desktop build.
  - The decision's stated reason ("Reusing runtime policy avoids a desktop lifecycle mirror") is contradicted by the mirror in `WorkCandidateQueries.kt`.
  - The exact build-identity gate (decision [2026-10-21]) already guarantees that the desktop runs the installed runtime's own classes, so the separate migration-number constant adds nothing except the bump ritual.
- **Fix (end state):**
  - Room, `WorkRoomDatabase`, `WorkCandidateQueries` and `DesktopReadSchema.ROOM_IDENTITY_HASH` and `VERSION` exist in no source set.
  - Board candidate selection exists only in runtime-infra:sqlite.
  - The desktop asks the in-process runtime whether the store is current, through the runtime's own migration list.
  - The commit-signal topics stay in runtime-contracts, because two builds read them.
- **Feasibility:** decision [2026-10-15] "Let desktop reach runtime services" already permits in-process service calls. Migration 52's Room identity row becomes inert and can be dropped by a later migration.

### F-006 — P2 — Governance machinery: large, hand-maintained, and blind where it matters

- **Rule:** G1 to G7, P7, YAGNI.
- **Evidence:**
  - The suite is 63 guard files, 15,980 lines and 289 `@Test` methods in `runtime-core/src/repoTest/kotlin/skillbill/architecture`, plus 281 `@Test` methods in other repoTest sets. That is 9.7 times runtime-core's own main code and 5.5% of all runtime main lines.
  - There are 65 baseline files, 61 of them empty. Of the 4 non-empty files, `custom-throwable-baseline.txt` holds 66 rows.
  - The suite keeps hand lists: `PINNED_ENGINE_INBOUND_API_TYPES` (92 names), `runtimeComponentInboundApi` (62), `WireVocabularyGovernedSeamInventory` (38 seams), the Noop-validator permitted consumers (14), `suppressionAllowList` (8), `rawMapBoundaryAccessors` (4) and `ambientEnvironmentExemptions` (2).
  - `enforceableRules` has 25 entries and `reviewOnlyRules` has 3 (`PrincipleEnforcementInventory.kt:179,653`). A2, A5 and A11 are in neither list, which G1 says means they "do not exist".
  - Prose: `ARCHITECTURE.md` 2,826 lines (the phase-slot section alone, lines 1193 to 1754, is 561 lines); `runtime-kotlin/agent/decisions.md` 3,399 lines and 270 entries; `history.md` 4,024 lines.
  - Guards with defects: F-001; A8 forms omit `nanoTime` and `ProcessHandle` (F-012); the A12 fixture allows `Any` parameters (§3); three different repo-root resolvers (`ArchitectureScanSupport.kt:10`, `PlanningProjectionNoopValidatorGuardTest.kt:11`, `FeatureTaskAuditRemainingCriteriaPackIndependenceArchitectureTest.kt:10`).
- **Why findings keep coming back:**
  - Rules with a working scan hold (§3).
  - Rules checked at the wrong granularity, rules hand-listed per module, and rules that are review-only regress through feature work. Feature work lands outside the architecture bundles (F-013).
  - The suite grows by one hand list per finding instead of by moving the rule into the compiler: module edges, `internal`, `explicitApi()`.
  - Prose grows with every bundle, and P7 already says counts in prose must be tested or removed.
- **Fix (end state):**
  - Rules the compiler can decide exist only as compiler configuration:
    - Gradle module edges, instead of `RuntimeGradleModuleLayeringTest`, `RuntimeAdapterDependencyAllowlistTest` and `RuntimeCoreCompositionOnlyTest` restating the build files;
    - `internal` and `explicitApi()`, instead of `PINNED_ENGINE_INBOUND_API_TYPES` and `runtimeComponentInboundApi`;
    - the domain `publicDomainDeclarationViolations` scan, which `explicitApi` makes unnecessary.
  - Every per-module rule is one parameterized test over the module catalog.
  - `ARCHITECTURE.md` holds the module map, package ownership and boundary rules, and describes no feature design. The phase-slot, contract-seam and feature-task continuation sections are design notes beside their code, or are deleted.
- **Feasibility:** deleting tests needs no production change. Each deletion must name the compiler mechanism that replaces it.

### F-007 — P2 — Interface default bodies stand in for implementations in the engine

- **Rule:** A4, LSP.
- **Evidence:**
  - `GoalRunnerQualityExecutionStore` (`engine/goalrunner/persist/GoalRunnerWorkflowOutcomeStore.kt:282-325`) declares 9 members whose defaults return `null`, `false` or `emptyList()`, or throw `UnsupportedOperationException` (lines 303, 318). It has one implementation, `WorkflowGoalRunnerQualityExecutionStore`.
  - Feature work (SKILL-446, `1ef0c125b`, 2026-10-27) added it.
  - `PortNullObjectAbsenceArchitectureTest` checks companion `NONE` objects, not default bodies.
- **Why it matters:** a fake or a new implementation that skips a member compiles and silently returns "no quality execution". This is the null-object pattern that A4 bans.
- **Fix (end state):** no interface in runtime-ports or runtime-engine main declares a member body that returns a literal or throws. Fakes implement every member in testFixtures.
- **Feasibility:** single file. Test fakes need the missing members added.

### F-008 — P2 — Cancellation safety rests on a naming convention across 485 `runCatching` sites

- **Rule:** A7.
- **Evidence:**
  - 485 `runCatching` sites in main (engine 185). `runCatching` catches `CancellationException`, so preservation depends on callers using `rethrowUnless` (107 calls), `rethrowIfCooperativeCancellationOrInterruption` (29) and `rethrowIfCancellation` (22).
  - The engine has 15 `runCatching{…}.getOrNull()` chains, e.g. `GoalRunnerPurgeSpecBundleReset.kt:83`, where a manifest read failure becomes "unreset".
  - 191 sites catch the tier-3 `SkillBillRuntimeException` (engine 76). A7 places those catches at the edges and at degrading boundaries.
  - 48 custom throwables remain in runtime-contracts; SKILL-399 and SKILL-400 are unlaunched.
- **Why it matters:** a single missed `rethrow*` call swallows cancellation in a coroutine path, and no guard detects it. `RuntimeEngineBoundaryArchitectureTest.kt:18` only checks that a nearby `diagnostics.warning` exists.
- **Fix (end state):**
  - Main code uses one cancellation-preserving helper. `kotlin.runCatching` is banned in main by detekt (`ForbiddenMethodCall`).
  - No `getOrNull()` follows a broad catch without a diagnostics record.
- **Feasibility:** mechanical, per module; detekt is already configured.

### F-009 — P3 — Platform identity is hard-coded in shared code

- **Rule:** OCP; AGENTS.md "routing and install read manifests".
- **Evidence:**
  - `runtime-domain/.../review/attribution/NormalizedStackLabel.kt:83-95` lists 10 slugs. It includes `java`, `ruby` and `docs`, which have no pack, and omits `rust` and `typescript`, which have packs.
  - `runtime-infra/skills/.../scaffold/catalog/ScaffoldCatalog.kt:72-85` special-cases the Kotlin baseline and KMP.
- **Why it matters:** review attribution for the rust and typescript packs falls through to the generic slug path, and the next pack repeats the omission.
- **Fix (end state):** stack-label normalization takes the known slugs from the loaded pack manifests as a parameter, and `knownPlatformSlugs` exists in no source set.
- **Feasibility:** the domain function stays pure because the caller passes the slugs.

### F-010 — P3 — Forwarders, aliases and one-off scripts with no caller

- **Rule:** A5, P3.
- **Evidence:**
  - `GoalPlanningSweepConstants` re-exports two constants (F-002).
  - `typealias MergeStatePullRequestIdentityLookup = PullRequestIdentityLookup` in runtime-ports (5 references).
  - `scripts/skill361_census.py`, `skill361_import_repair.py` and `skill361_nest.py` (655 lines) are referenced by nothing.
  - `scripts/release_retention_smoke_test.sh` (65 lines) is referenced by no workflow or script.
- **Fix (end state):** these exist in no source set; callers name the owner.
- **Feasibility:** trivial.

### F-011 — P3 — runtime-contracts carries single-owner and foreign vocabulary

- **Rule:** A1, A2.
- **Evidence:**
  - 48 of the 66 custom throwables are in runtime-contracts (the SKILL-399 and SKILL-400 transition is open).
  - `DesktopReadSchema` holds Room's identity hash.
  - 35 of the 112 contract-version constants are in runtime-contracts.
  - Its D is 0.98 and 967 files depend on it.
- **Why it matters:** each change to the kernel recompiles about 970 files across 13 modules.
- **Fix (end state):** F-005 removes the Room vocabulary. SKILL-399 and SKILL-400 remove the exception classes. Contract versions for runtime-internal state leave with F-004.

### F-012 — P3 — A8 states a rule its guard does not check and the code reasonably breaks

- **Rule:** A8, G5.
- **Evidence:** A8 forbids `System.nanoTime` and `ProcessHandle` in all main code. `AMBIENT_CLOCK_FORMS` (`ArchitectureScanGuardSupport.kt:48-58`) lists neither. There are 18 `nanoTime` and 28 `ProcessHandle` reads, all in the host, launcher and workflow process adapters (`BoundedExternalProcessRunner.kt:44`, `JdkFeatureTaskRuntimeWorkerSupervisor.kt:291`).
- **Fix (end state):** A8 names the process adapters as the owners of these reads. The scan forms include both APIs for every module except runtime-infra:host, launcher and workflow. Today that is 0 violations.

### F-013 — P2 — The change process lets feature work undo architecture fixes

- **Rule:** P1, P4, P5, P6.
- **Evidence:**
  - Round 4 landed in one squash with feature work (§3).
  - SKILL-390 AC4 was re-broken by SKILL-425, 441 and 446 within 23 days.
  - The key SKILL-425 is used by two bundles.
  - A "DO NOT MERGE" commit is on `main`.
  - Section 1 of the guidelines already records lesson 7 ("feature work bypassed the rules").
- **Fix (end state):** an architecture bundle merges as its own commit, and acceptance is run against that commit. The guards in F-001 make feature regressions fail CI instead of failing the next investigation.

## 6. Over-engineering register

| Item | Cost | Return | Action |
| --- | --- | --- | --- |
| Room in the desktop (`WorkRoomDatabase`, `ReadOnlyRoomDriver`, `JdbcRoomConnection`, Room identity hash migration) | Runtime migration 52 for a desktop library; `DesktopReadSchema.VERSION` bump on every migration; a second read path | One entity and one raw query | Delete (F-005) |
| `FIRST_SEGMENT_MUTUAL_PAIR` granularity | Hides 19 SCCs | None | Delete (F-001) |
| Hand-written per-module guard methods (13 cycle methods and similar) | Engine silently omitted | – | One parameterized test per rule |
| `PINNED_ENGINE_INBOUND_API_TYPES` (92), `runtimeComponentInboundApi` (62) | Hand upkeep on every engine change | Restates visibility | Replace with module split plus `explicitApi()` |
| Guards restating Gradle edges (`RuntimeGradleModuleLayeringTest`, `RuntimeAdapterDependencyAllowlistTest`, `RuntimeCoreCompositionOnlyTest`) | Parse build files to re-check what Gradle resolves | Detects an added edge in review, which a reviewer also sees in the diff | Keep one edge-pin test, delete the other two |
| 61 empty baseline files | File noise; rules say they stay empty | None | Delete the files; the test asserts an empty result |
| JSON Schema plus Kotlin mirror for runtime-internal state (about 40 of 58 schemas) | YAML, version constant, codec and validator per family | Runtime validation of data the runtime itself wrote | Keep schemas for agent-produced input; use typed serialization for internal state (F-004) |
| `ARCHITECTURE.md` feature-design sections (phase slots, 561 lines; contract seams; continuation) | Drift, which every round has fixed (lesson 8) | Explanation that belongs beside the code | Move or delete (F-006) |
| `GoalPlanningSweepConstants`, `MergeStatePullRequestIdentityLookup`, `skill361_*.py`, `release_retention_smoke_test.sh` | – | None | Delete (F-010) |
| `Phase*StepBinding` interfaces (about 30, one implementation each, no fakes) | A 1,176-line implementation file and 555 lines of slot boundary scans | Capability restriction per strategy | Keep the seam (see §8) but drop interfaces whose only consumer is the one strategy that the binding already restricts; measure before acting |

## 7. Guard audit

| Guard | Defect |
| --- | --- |
| `ApplicationPackageAcyclicityArchitectureTest` "engine subareas…" (line 439) | Prefix lacks a trailing dot; every node is blank, so it cannot fail |
| `ApplicationPackageAcyclicityArchitectureTest` per-module methods | No runtime-engine method; `runtime-engine-package-cycle-baseline.txt` is never read |
| Same, granularity | First-segment mutual pairs for 12 modules; misses the 88, 28, 20, 7, 4 and 3-package SCCs |
| `application-package-cycle-baseline` assertion | One-sided (`packageCycleViolations`); a removed cycle does not have to leave the baseline |
| `RuntimeRawMapArchitectureTest` exact-`Any` | Fixture asserts that `Any` parameters are allowed (lines 100, 111); A12 says no `Any` in public declarations |
| Ambient-clock scan | Forms omit `System.nanoTime` and `ProcessHandle`, which A8 names |
| `PortNullObjectAbsenceArchitectureTest` | Covers companion `NONE` only, not interface default bodies (F-007) |
| `PrincipleEnforcementInventory.reviewOnlyRules` | 3 entries; A2, A5 and A11 are absent (G1) |
| Repo-root resolution | Three implementations. The two local ones resolve only when the working directory is a `runtime-*` module directory. Both currently scan real files: `PlanningProjectionNoopValidatorGuardTest` would fail on an empty set because its expected set is non-empty, and `FeatureTaskAuditRemainingCriteriaPackIndependenceArchitectureTest` asserts exactly one owner. |
| Checked and clean | `RuntimeEngineInboundApiTest` resolves through `moduleMainKotlinRootRelative` (memory's earlier blind-root defect is fixed). The ambient, inject-defaults and composition guards iterate `moduleArchitectureScanCases`, which derives from `RuntimeModuleCatalog.declaredGradleModules`. |

Rules a scan could enforce but no guard does:

- exact-package acyclicity in 12 modules;
- no interface default bodies in engine and ports;
- no `kotlin.runCatching` in main;
- no `json_extract` outside sqlite;
- no `JsonCodec` in domain;
- `explicitApi()` instead of hand lists.

## 8. What stays unchanged

| Item | Reason |
| --- | --- |
| The contracts/domain/ports/application/infra/core/cli/mcp split | Standard hexagonal layering; Gradle enforces direction; 0 infra → application imports; 0 SQL in inner layers |
| runtime-application as a separate module | 21k lines of real use cases (review runner, workflow service, install, telemetry lifecycle) used by cli (51 files) and mcp (11) without the engine. Merging it into the engine would make the CLI depend on the run loop for non-run-loop commands. Rejected. |
| Seven infra modules | Each isolates a vendor dependency set (sqlite-jdbc, json-schema-validator, snakeyaml, jackson). Merging http (1.7k) into host was considered: it saves one build file and four baselines, a gain too small to justify. |
| One composition root in runtime-core (kotlin-inject) | A3 holds; the generated readers make types public, so this is a cost to accept |
| The phase-slot and strategy seam | Recorded design (SKILL-380, "One generic PhaseRunner, compositional"); strategy implementations vary, and capability restriction is guarded. Only the binding-interface count is questioned (§6). |
| Two Gradle builds (runtime-kotlin, desktop-tray) | Keeps Compose and its dependency graph out of the runtime build (decision [2026-10-11]). Merging the builds was rejected; F-005 removes the database coupling, which was the real cost. |
| The desktop loading the installed runtime in process behind a build-identity gate | Decision [2026-10-19]; one migration owner. Kept; F-005 removes only the duplicate checks built on top of it. |
| runtime-core integration tests | They need application and real adapters together; runtime-core is the only module whose classpath allows that (A10 satisfied in spirit) |
| Desktop module layering | Features depend only on core; `composeApp` (4.1k main lines) holds shell and host integration; guarded by `ModuleGraphRulesTest` with rejection fixtures. Clean. |
| Legacy read paths (14 `Legacy*` types) | Persisted invariants need a legacy read path (memory and observed defects). Each needs a removal trigger, not removal now. |
| Agent-facing contract schemas (phase output, audit result, review context, platform pack, agent add-on, issue key) | Producers sit outside the process; runtime validation earns its cost |
| detekt, ktlint/spotless, comment policy | Platform-native, cheap |

Rejected options:

- A fifth per-module investigation round (P8: the evidence points at system causes).
- Folding domain into ports: domain is 42k lines of pure rules with 0 SCCs, the healthiest inner module.
- Making the desktop a CLI client again: decision [2026-10-12] and the measured latency work (`design/linear-study`) stand.

## 9. Target shape

Delta from today, with no big-bang rewrite:

1. **Guards first.** Exact-package SCC becomes the only cycle mode, one parameterized test over the catalog, with no cycle baselines. Each module flips when its cycles are gone. The modules with no cycles today (domain, contracts, cli, core, mcp, http) are already on it.
2. **Break the engine cycle.** Apply the F-002 back-edge removals. The engine is acyclic at exact-package level.
3. **Split the engine.** Create `runtime-engine` (feature-task run loop, slots, phase run, the work read model) and `runtime-goalrunner` (goal runner, goal planning, goal QA). Both use `explicitApi()`. runtime-core wires both, and cli and mcp depend on both. The pinned inbound-API lists are deleted.
4. **Desktop reads through services only.** Room, the candidate SQL and the `DesktopReadSchema` version and hash go. The desktop calls `IdeStatusService` (or the split read model) in process and keeps the commit-signal file.
5. **One owner for the durable document.** Artifact codecs move from domain and engine into runtime-infra:sqlite, one artifact family at a time. Query fields become columns. Internal-state YAML schemas and their version constants go as each family moves.
6. **Governance diet.** Delete the guards that restate Gradle or visibility, delete the empty baselines, and trim `ARCHITECTURE.md` to the module and ownership map.

Resulting structure: 17 runtime modules (one more than today), with the compiler enforcing what today is enforced by hand lists, and one persistence schema owner.

## 10. Candidate work packages

Each package runs on the current tree and waits for no other package.

| WP | Findings | Scope | Size |
| --- | --- | --- | --- |
| WP-1 Engine acyclicity | F-001 (engine part), F-002, F-010 (`GoalPlanningSweepConstants`) | Remove the featuretask → goalrunner/work/operation back-edges and the diagnostics ↔ featuretask edges; add runtime-engine to the exact-SCC cycle test; delete the engine-subarea test | M (about 15 files moved or edited, 1 test) |
| WP-2 Exact-SCC everywhere | F-001 (other modules) | Break the sqlite (28), skills (20, removing the baselined row), application (7 + 3 + 2 + 2 + 2), ports (4 + 2 + 2), infra:contracts, host, workflow and launcher cycles; delete `FIRST_SEGMENT_MUTUAL_PAIR` and the cycle baseline files. Can be cut per module. | L in total, S–M per module |
| WP-3 Desktop read path | F-005, F-011 (Room part) | Delete Room and the candidate SQL; serve board candidates from the runtime read model; get the compatibility answer from the in-process runtime; drop `DesktopReadSchema.VERSION` and `ROOM_IDENTITY_HASH` | M |
| WP-4 Engine visibility | F-003 (no-split variant) | `explicitApi()` on runtime-engine; mark the 284 functions and the confirmed types `internal`; this works inside the single module before any split | M, mostly mechanical |
| WP-5 Engine module split | F-003 | Extract `runtime-goalrunner`; partition the pinned inbound API; delete the hand lists | L. Only possible once the goalrunner ↔ featuretask edges are gone; if WP-1 has not landed, the package applies its own minimal back-edge cut. |
| WP-6 Durable document ownership, first family | F-004 | Move one artifact family (goal continuation: the desktop and sqlite `json_extract` users) to a typed record coded in sqlite, plus a `parent_workflow_id` column | M per family |
| WP-7 Guard diet | F-006, F-012 | Delete the Gradle-restating guards and the empty baselines; one parameterized per-module test per rule; add `nanoTime` and `ProcessHandle` forms with adapter scoping; record A2, A5 and A11 in `reviewOnlyRules`; one repo-root resolver | M |
| WP-8 Null objects and LSP | F-007 | Remove the interface default bodies in engine `*Store` interfaces; fakes implement every member | S |
| WP-9 Cancellation helper | F-008 | One helper; detekt `ForbiddenMethodCall` for `runCatching` in main; per module | M |
| WP-10 Hygiene batch | F-009, F-010 | Manifest-driven stack slugs; delete the alias and the unused scripts | S |

## 11. Limits

- The interface census matches supertypes with regex. Generic or multi-line declarations may be miscounted by a few.
- The "zero readers" counts for **types** are upper bounds: a type reached through inference needs no import. The function counts are exact for imports.
- Exact-package SCCs were computed with a Python Tarjan over `import` lines. Same-package references and wildcard imports of nested members could add edges, but not remove the cycles found. Confirming the cut set for WP-1 and WP-2 needs a compile after each move.
- No build or test run was performed. The engine split (WP-5) and `explicitApi()` (WP-4) need compiling to confirm which types kotlin-inject's generated code forces to stay public.
- The following were not inspected: `platform-packs/*` content quality; `evals/`; `skills/skill-bill/content.md` beyond its size; the release workflows beyond reference counting; the desktop `build-logic` and `build-identity` beyond the shared-version check and the identity equality duplication; transaction boundaries beyond the files cited; and every guard in the suite (only the guards cited above were opened).
- Coupling metrics use file granularity, not class granularity.
