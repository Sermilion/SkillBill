# SKILL-396: runtime-infra architecture investigation

## Judgment
runtime-infra is in good shape. The SKILL-376 repairs largely landed:
- no main-code upward imports (0 imports of skillbill.application|engine|di|cli|mcp)
- runtime-core is the only main consumer (86 imports)
- no `api` edges
- the unused yaml and json-schema dependencies are gone
- the git sessions are gone; ProcessBuilder is confined to host BoundedExternalProcessRunner.kt:79 and launcher JvmAgentRunProcessRunner.kt:337
- currentTimeMillis, Instant.now and Clock.system* are absent from main
- test packages mirror main packages
- ARCHITECTURE.md drift is fixed
- the readiness gate is a val
- the package-acyclicity guard is live for infra

What remains is one real correctness regression and a few moved-not-removed leftovers:
- the SQLite adapter still resolves diagnostics through a process-global registry keyed by Connection, with a silent no-op fallback. This is the ambient-default pattern SKILL-356 set out to remove. The review-stats production path drops its degradation records today.
- the contracts test source set still depends on runtime-application; SKILL-376 F-008 moved the edge rather than removing it.
- one architecture guard is vacuous.
- two adapters duplicate each other.
- one pure policy function lives in an adapter.
- workflow has one package stutter and three homes for gh adapters.

Every fix removes code or moves it to its owner. Nothing adds a layer.

## Method and baseline
- Baseline: HEAD ae23f4f28f16d851a0548e8149e0fe6fadbbc612 (SKILL-386 merged; SKILL-387/388 planned).
- The census was done by hand with grep and find over runtime-kotlin, with no delegated review. It read the build.gradle.kts files, the imports, the declarations and the repoTest guards and baselines.
- Prior done investigation read first: SKILL-376-runtime-infra-hexagonal-boundaries; each of its findings was rechecked.
- Sibling bundles read: SKILL-387, 388, 389, 392 and 393 (from the peer notice). 390, 391, 395 and 397 have no bundle on disk yet.
- Nothing was compiled or run.

## Census
| Module | Main files | Main lines | Test files | Main edges |
|---|---:|---:|---:|---|
| host | 25 | 1,930 | 12 | ports, domain, contracts |
| contracts | 53 | 6,463 | 60 | ports, domain, contracts; test: runtime-application (build.gradle.kts:18) |
| skills | 244 | 27,547 | 134 | + infra:contracts, infra:host |
| launcher | 35 | 4,053 | 19 | + infra:skills, infra:host |
| workflow | 74 | 7,837 | 46 | + infra:skills, infra:contracts, infra:host |
| http | 10 | 809 | 7 | domain, ports, contracts |
| sqlite | 134 | 17,200 | 64 | domain, ports, contracts, serialization, sqlite-jdbc |

Package and consumer counts:
- 109 main packages; maximum 12 files per package (8 packages at 12); 24 single-file packages.
- sqlite fell from 158 files / 21,449 lines (SKILL-376) because the goalrunner move landed.
- Main consumers outside infra: runtime-core only, 86 imports (contracts 10, host 13, http 4, launcher 4, skills 26, sqlite 3, workflow 26).
- Test consumers (files importing infra): cli test 22, core test 15, core repoTest 2, engine test 37, engine testFixtures 2, mcp test 8, application testFixtures 4.

## Principle checklist (13 items)
1. **Dependency direction.** Main upward imports: 0. Test upward imports: 8 in 2 files (F-001).
2. **Build edges.** 0 `api` edges and 0 unused declared dependencies. 1 backward test edge: contracts/build.gradle.kts:18 (F-001).
3. **Package cycles.** The acyclicity guard is live: PrincipleEnforcementInventory.packagePrefixForModule (repoTest L52-61) uses RuntimeModuleCatalog.moduleMainPackageRoots (L228-242). The skills cycle baseline has 1 row (`nativeagent|scaffold`); the other infra baselines are empty.
4. **Port in adapter / policy in adapter.** 1: GoalPlanningStatusSnapshotDerivation.kt holds a pure status rule (F-005).
5. **Ambient state.**
   - 2 globals. diagnosticsByConnection (InternalSqliteDiagnostics.kt) is a finding (F-003). processBundleLocks (DecompositionManifestBundleJournal.kt:61) is an in-process lock and stays.
   - System.getenv/getProperty appear in 6 files. All are baselined: skills 2 (F-004 removes both), host 3, launcher 2.
   - System.nanoTime appears in 7 files; it is monotonic and stays.
6. **Silent defaults.** 14 `= InternalSqliteDiagnostics` defaults (DatabaseRuntime.kt ×4 at L32/57/68/108; ConnectionTransactions.kt:120; DatabaseMigrations.kt:22; ReviewStatsArithmetic.kt:34, 68; LifecycleTelemetryDurations.kt:13; LifecycleTelemetryPayloads.kt:66, 218, 285; GoalTelemetryPayloads.kt:100, 125). 3 production call sites drop records: ReviewWorkflowStats.kt:29, 83, 88 (F-003).
7. **Error model.** 4 `catch (_: Exception)` sites, each converting to a typed error or finding: GoalPlanningPreparationSqlHydrate.kt:51, FileSystemValidationGateRunnerCommandExec.kt:179/209, SkillRemoveJvmFileSystemApply.kt:175. There are 201 runCatching sites in 93 files. The SKILL-376 F-006 swallow sites were rechecked:
   - RepoValidationCollected.kt:35-37 and RepoValidationRuntimeSkillDiscovery.kt:132-136 now record an issue on failure: fixed.
   - FileSystemNativeAgentPlannedWorkerValidation.kt:19-20 skips unparsable sources. Parse failures are reported by NativeAgentValidationReport.kt:83, so it stays.
8. **Interfaces with one implementation.** AgentRun* decoder/builder/probe/idle/process runner, AgentAddonSchemaResourceLoader and NativeAgentPlatformPackLoader were kept by SKILL-376. GhCommandRunner has 1 production implementation and lambda substitutes in 2 test files; it stays as the test seam. AgentRunAdapter is gone.
9. **Typealiases.** 5 (DirName, DirTarget, DirSlug, TrackedRepoFilesProvider, AuthoringTargetRenderer), all kept per SKILL-376. The 0 pass-through aliases confirm the SKILL-376 removal.
10. **Injection.** 81 @Inject in 71 files. 0 constructors expose non-private vals. The 3 gh adapters use an internal constructor seam plus an @Inject no-arg constructor.
11. **Dead code.** 4 items (F-003): the SQLiteUnitOfWork sessionClock/sessionDiagnostics getters (SQLiteRepositories.kt:80-81, 0 uses); DatabaseWriteReadinessGate onSchemaEstablishment (never passed); the ensureWriteReady establishSchema default (always overridden); DatabaseRuntime.openReadDbIfPresent (0 callers).
12. **Duplication.** 1 pair: FileExternalAddonSourceConfigStore.kt (133 lines) and file/FileExternalAgentAddonSourceConfigStore.kt (102 lines) share the key, the read skeleton and a byte-identical resolveSourcePath (F-004).
13. **Package naming.** 1 stutter, workflow.git.workflow (3 files). The gh adapters are split across 3 packages (F-006).

## Findings

### F-003 (P1): SQLite diagnostics side channel and silent no-op default
**Evidence**
- sqlite/core/ops/InternalSqliteDiagnostics.kt defines a no-op `object InternalSqliteDiagnostics : RuntimeDiagnostics` and a `ConcurrentHashMap<Connection, RuntimeDiagnostics>`. Its attach, detach and lookup extensions fall back to the no-op.
- Attach/detach calls: SQLiteDatabaseSessionFactory.kt:49/64, 70/85, 133/137 and DatabaseMigrations.kt:28-34/63-65. DatabaseMigrations also runs a sentinel identity check.
- 8 main readers: LegacyGoalRunnerControlLedgerMigration.kt:52, FeatureTaskWorkflowStateStoreSql.kt:64, LifecycleTelemetryEmit.kt:41/68/94, GoalTelemetrySave.kt:241, GoalTelemetryEmit.kt:59/80.
- SQLiteUnitOfWork (SQLiteRepositories.kt:69-155) already holds the diagnostics as a constructor value. It builds SQLiteReviewRepository(connection, clock, runtimeVersion), which delegates WorkflowStatsRepository to SQLiteWorkflowStatsRepository(connection) (L206), and on to ReviewStatsRuntime.kt:56/59 and ReviewWorkflowStats.
- ReviewWorkflowStats.kt:29/83/88 call parseJsonList and durationSeconds without diagnostics. The degradation records for seams review_stats.json_array and review_stats.duration_seconds are therefore discarded. SKILL-356 AC3 required them.
- This violates docs/observability-policy (every fallback emits a record) and hides a dependency behind a global.

**Fix**
- Pass the UnitOfWork or factory RuntimeDiagnostics as an explicit constructor or function parameter wherever it is read.
- DatabaseMigration's operation receives the diagnostics explicitly.
- Delete the map, attach/detach/lookup, the sentinel check, InternalSqliteDiagnostics, all 14 defaults and the 4 dead members.
- Test-only DatabaseRuntime entry points that have callers stay (see What stays).

### F-001 (P2): contracts test edge to runtime-application moved, not removed
**Evidence**
- runtime-infra/contracts/build.gradle.kts:18 declares `testImplementation(project(":runtime-application"))`.
- SchemaValidatorPortLoudFailTest.kt and DecompositionManifestValidationTest.kt (lines 3-6 of each) import baseBranch, executionModel and parentSpecPath, which are unused (they match only named arguments), plus encodeValidatedDecompositionManifestYaml.
- The real use is a private helper calling the application function by fully qualified name (SchemaValidatorPortLoudFailTest.kt:230-241; DecompositionManifestValidationTest.kt:377).
- That application function (DecompositionManifestFileWrites.kt:88-114) is only port calls (encodeManifestWireMap, encodeManifestYaml, validateYamlTextResult) plus domain requireAccepted.

**Fix**
- Build a private test helper from those port and domain calls.
- Delete the 8 imports and the build edge.
- Feasible: the test classpath already has ports and domain.

### F-002 (P2): vacuous skills import-direction guard
**Evidence**
- runtime-core/src/repoTest/.../InfrastructureSkillsImportDirectionArchitectureTest.kt skillsLayerIndex (L36-50) takes indexOfLast over a prefix list ending with the module root. Every package therefore gets layer 5 and the check never fires. runtime-kotlin/agent/history.md:204 records this.
- A most-specific-prefix fix would report 7 imports (6 in 4 nativeagent files importing scaffold; 1 in ScaffoldServicePlanning.kt:9 importing externalplatformpack). Those would need a baseline row (disallowed) or a nativeagent/scaffold split, which SKILL-376 rejected as needing a design.
- The live acyclicity guard already pins the one real cycle, `nativeagent|scaffold`.

**Fix:** delete the guard. It is a standalone file; nothing else references it.

### F-004 (P2): duplicate external addon source readers
**Evidence**
- skills/externaladdon/FileExternalAddonSourceConfigStore.kt and skills/file/FileExternalAgentAddonSourceConfigStore.kt both read `external_addon_sources` from the telemetry config through host readTelemetryConfigFile/resolveTelemetryConfigPath. They differ only in which kind they skip.
- They carry byte-identical resolveSourcePath bodies.
- Each calls System.getProperty("user.dir"); these are the 2 rows of runtime-infra-skills-ambient-environment-baseline.txt. FileExternalPlatformPackSourceConfigStore.kt:195 already uses JdkHostPlatformPort.resolveWorkingDirectory().

**Fix**
- Keep both port implementations, but move the agent-addon store into skills.externaladdon.
- Share one internal entry-read and resolveSourcePath helper in FileExternalAddonSourceConfigParsing.kt.
- Use the host working-directory seam.
- Delete the skills.file package. The baseline shrinks by 2 rows; shrinking is allowed, only expansion is not.

### F-005 (P2): goal-planning status policy in the SQLite adapter
**Evidence**
- sqlite/workflow/goalrunner/planning/GoalPlanningStatusSnapshotDerivation.kt (59 lines) is a pure function over domain models. It covers state precedence (blocked > not_started > prepared > preplanned > partially_planned), the wave cap, resumeAt and reasons.
- Its only imports are the contracts constant GOAL_PLANNING_WAVE_CAP and skillbill.goalrunner.model (GoalPlanningStatusReasons, GoalPlanningStatusSnapshot, GoalPlanningStatusState, all in runtime-domain GoalRunnerStatusProjectionModels.kt). Its only caller is GoalPlanningStatusProjectionSql.kt:22.
- GoalPlanningStatusProjectionSql.kt:80 and :94 restate the literal "prepared" where neighbouring files use GoalPlanningPreparationState.PREPARED.wireValue (ports GoalPlanningPreparationRecord.kt:103-105).

**Fix**
- Move the function, public and unchanged, into runtime-domain skillbill.goalrunner.model beside GoalPlanningStatusSnapshot.
- Replace the two literals.
- Feasibility: domain may import contracts (existing implementation edge), and the moved code uses no java.nio and no skillbill.ports.
- Input validation in the SQL file stays; it checks stored data.

### F-006 (P3): workflow package stutter and scattered gh adapters
**Evidence**
- skillbill.infrastructure.workflow.git.workflow (GitReadinessTreeIdentityOperations.kt, GitWorkflowGitOperations.kt, GitWorkflowGitOperationsFingerprint.kt) is the only stutter in infra main. The workflow.git root holds no files.
- The gh adapters sit in 3 packages: workflow.git.goal (GhCommandRunner.kt, GhGoalPullRequestPort.kt, GhPullRequestIdentityLookup.kt), workflow.git.github (GhPullRequestReviewThreads.kt, which imports internal GhCommandRunner, ProcessGhCommandRunner and describeFailure from git.goal), and workflow.github (GitHubPullRequestCheckDiscovery.kt).

**Fix**
- Move the 3 stutter files to workflow.git.
- Move the 4 gh files to workflow.github; GhCommandRunner stays internal to the module.
- Import-only consumer edits (files): workflow main 4 + 1, workflow test 11 + 2, engine test 11, core main 1 + 2, core test 1, cli test 1, cli repoTest 1, mcp test 1.
- No new cycle: git.goal keeps no GhCommandRunner users after the move.

## Over-engineering register (removals only)
- InternalSqliteDiagnostics, the connection-keyed registry, attach/detach, the sentinel identity check and 14 defaulted parameters (F-003).
- SQLiteUnitOfWork sessionClock/sessionDiagnostics getters, the readiness-gate onSchemaEstablishment hook, the establishSchema default lambda and DatabaseRuntime.openReadDbIfPresent (F-003).
- InfrastructureSkillsImportDirectionArchitectureTest (F-002).
- The contracts test edge to runtime-application and 8 imports (F-001).
- The skills.file package and one duplicated resolveSourcePath (F-004).
- The workflow.git.workflow and workflow.git.github packages (F-006).

## What stays unchanged, and why
- DatabaseRuntime.ensureDatabase/openDb/openReadDb test entry points (227 + 1 + 9 test uses across 32 files). Moving them is churn with no boundary gain.
- The GhCommandRunner interface and the internal-constructor seam on the gh adapters: the real test seam that replaced the SKILL-376 mutable hook.
- JdkHostPlatformPort default arguments at about 30 sites: the sanctioned host seam, baselined where ambient.
- processBundleLocks: a legitimate in-process lock.
- The 5 kept typealiases, and the AgentRun* and loader interfaces: retained by SKILL-376 decisions; each has a test or variant seam.
- The nativeagent/scaffold cycle row: breaking it needs a design, as SKILL-376 recorded.
- ReviewWorkflowStats local token lists and about 28 public Map<String, Any?> adapter signatures: adapter seams, per SKILL-376 and the raw-map guard.
- The engine CodeReviewResumeRules "[SQLITE_BUSY]" marker: engine-owned (SKILL-390); the adapter already translates to DatabaseBusyError.
- repoTest descriptive packages (skillbill.scaffold, install, nativeagent, agentaddon, contracts.*): a SKILL-376 retention decision.
- FileSystemNativeAgentPlannedWorkerValidation's skip of unparsable sources: NativeAgentValidationReport.kt:83 reports the parse failure.
- The 4 `catch (_: Exception)` sites: each converts to a typed error or finding.
- runtime-kotlin/agent/history.md: an append-only record.

## Guard validity (scan roots read files)
- ApplicationPackageAcyclicityArchitectureTest via PrincipleEnforcementInventory.packagePrefixForModule and RuntimeModuleCatalog.moduleMainPackageRoots: reads each module's src/main/kotlin. Live (1 skills baseline row).
- The ambient-environment guard, via runtime-infra-skills-ambient-environment-baseline.txt: reads skills main. Its 2 rows are the F-004 stores.
- InfrastructureSkillsImportDirectionArchitectureTest reads files but its classification is vacuous (F-002).
- No guard is extended; no scanner change is needed.

## Coordination
| Key | Module | Overlap with 396 | Order |
|---|---|---|---|
| SKILL-387 | prose phase output | none in infra | independent |
| SKILL-388 | application | edits sqlite LegacyGoalRunnerControlLedgerMigration.kt:112 and GoalRunnerControlStoreDecodePolicies.kt:92-97 (exact-int), workflow FileSystemDiffResolver.kt, the inventory and scan support | 396 lands after 388 and rebases; F-003 touches the migration's diagnostics read, not its parse |
| SKILL-389 | core | inlines databaseSessionFactory into RuntimeComponent (SQLiteDatabaseSessionFactory constructor unchanged); edits PrincipleEnforcementInventory and ArchitectureScanSupport | 396 lands after 389; the F-003 factory constructor stays source-compatible; F-002 deletes a standalone file |
| SKILL-390 | engine | engine test importers of workflow.git.workflow (11 files); 390 subtask 3 moves engine test packages | either order; whichever lands second rewrites the import lines in the files present |
| SKILL-391 | contracts | keeps GOAL_PLANNING_WAVE_CAP in runtime-contracts (used by F-005); repackages six runtime-infra/contracts repoTests, none of which 396 edits | independent |
| SKILL-392 | cli | subtask 2 changes ports HostPlatformPort and infra host JdkHostPlatformPort (java-command member); cli test and repoTest import workflow.git.workflow | 396 subtask 2 lands after 392; no host file edited by 396 |
| SKILL-393 | ports | moves the installer output cap and sentinel into infra/host as internal; the launcher REVIEW_EVIDENCE_BATCH_SIZE becomes the single owner | no shared file with 396 |
| SKILL-395 | mcp | the mcp test imports workflow.git.workflow (1 file); 395 switches ReviewRowMappers.kt to ReviewFinishedTelemetryPayloadKeys | either order; whichever lands second keeps both edits |
| SKILL-397 | domain | F-005 adds planningStatusSnapshot to domain goalrunner/model; 397 subtask 3 repairs domain packages | either order; whichever lands second keeps the function in the domain goalrunner model package |

## Limits (what only compiling confirms)
- kotlin-inject graph resolution after the diagnostics become constructor parameters of the sqlite repositories.
- The contracts test classpath compiling without runtime-application.
- The DatabaseMigration operation signature change reaching every migration step.
- Visibility of the moved planningStatusSnapshot from sqlite.
- Import rewrites in about 31 files for subtask 2.
- Key re-census: git log and branch listing need approval in this headless session. The key SKILL-396 was confirmed by the peer notice, and no .feature-specs/SKILL-396* directory exists.
