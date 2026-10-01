# SKILL-396 Subtask 1 - Infra boundary and diagnostics repairs

Parent spec: [.feature-specs/SKILL-396-runtime-infra-boundary-repairs/spec.md](spec.md)
Issue key: SKILL-396

## Scope

Five semantic repairs:
- F-003: thread RuntimeDiagnostics explicitly through the SQLite session factory, SQLiteUnitOfWork, the review and workflow-stats repositories, the lifecycle and goal telemetry emitters, the feature-task workflow state store and the migrations. Delete the connection-keyed registry, InternalSqliteDiagnostics, every defaulted RuntimeDiagnostics parameter in sqlite main, and the dead members (sessionClock/sessionDiagnostics getters, the onSchemaEstablishment hook, the establishSchema default, openReadDbIfPresent).
- F-001: replace the application-backed helper in the two contracts decomposition tests with port and domain calls, and drop the runtime-application test edge.
- F-002: delete InfrastructureSkillsImportDirectionArchitectureTest.
- F-004: move FileExternalAgentAddonSourceConfigStore into skills.externaladdon, share one resolveSourcePath and entry-read helper, and use the host working-directory seam; remove the 2 matching ambient-environment baseline rows.
- F-005: move planningStatusSnapshot into runtime-domain skillbill.goalrunner.model and replace the two prepared literals in GoalPlanningStatusProjectionSql.kt with GoalPlanningPreparationState.PREPARED.wireValue.

## Acceptance Criteria

1. runtime-kotlin/runtime-infra/contracts/build.gradle.kts declares no project(":runtime-application") dependency in any configuration.
2. No .kt file under runtime-kotlin/runtime-infra imports or references, by qualified name in code, any declaration in a skillbill.application package. Text inside string literals, such as the sample stack trace in FileSystemValidationGateJunitFindingsTest.kt, does not count and stays unchanged.
3. SchemaValidatorPortLoudFailTest.kt and DecompositionManifestValidationTest.kt produce manifest YAML through a private helper that calls encodeManifestWireMap, encodeManifestYaml and validateYamlTextResult.
4. runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/InfrastructureSkillsImportDirectionArchitectureTest.kt does not exist, and no new *ArchitectureTest.kt file exists under runtime-kotlin.
5. No file under runtime-kotlin/runtime-infra/sqlite/src/main contains diagnosticsByConnection, attachSqliteDiagnostics, detachSqliteDiagnostics, sqliteDiagnostics( or InternalSqliteDiagnostics, and InternalSqliteDiagnostics.kt no longer declares a Connection-keyed map.
6. No parameter declaration in runtime-kotlin/runtime-infra/sqlite/src/main has a RuntimeDiagnostics type with a default value.
7. SQLiteUnitOfWork passes its RuntimeDiagnostics constructor value to SQLiteReviewRepository and on to SQLiteWorkflowStatsRepository, and every parseJsonList and durationSeconds call in ReviewWorkflowStats.kt receives that diagnostics argument.
8. The DatabaseMigration operation takes a RuntimeDiagnostics argument, and LegacyGoalRunnerControlLedgerMigration.kt obtains its diagnostics from that argument.
9. SQLiteRepositories.kt declares no sessionClock or sessionDiagnostics member, DatabaseWriteReadinessGate declares no onSchemaEstablishment parameter, the establishSchema parameter of DatabaseRuntime.ensureWriteReady has no default, and DatabaseRuntime declares no openReadDbIfPresent.
10. A test under runtime-kotlin/runtime-infra/sqlite/src/test stores a feature-verify workflow row with malformed completed-phase JSON, reads workflow stats through a SQLiteUnitOfWork built with a recording RuntimeDiagnostics, and asserts that a degradation record with seam review_stats.json_array was recorded.
11. FileExternalAgentAddonSourceConfigStore is declared in package skillbill.infrastructure.skills.externaladdon, no main file declares package skillbill.infrastructure.skills.file, that package declares resolveSourcePath exactly once, and neither external addon source store calls System.getProperty.
12. runtime-infra-skills-ambient-environment-baseline.txt no longer lists either external addon source store, and no file under runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines has more rows than at ae23f4f28.
13. planningStatusSnapshot is declared in runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/model, GoalPlanningStatusSnapshotDerivation.kt no longer exists under runtime-infra/sqlite, and GoalPlanningStatusProjectionSql.kt contains no "prepared" string literal.
14. No settings.gradle.kts include and no build.gradle.kts dependency line was added.

## Non-Goals

- Breaking the nativeagent/scaffold package cycle or making the skills import-direction guard live.
- Moving the DatabaseRuntime ensureDatabase/openDb/openReadDb test entry points or their 237 test call sites.
- Changing any wire, JSON, YAML, telemetry payload or persisted value.
- Changing the SQLiteDatabaseSessionFactory constructor that SKILL-389 inlines, or the SKILL-388 exact-int parse changes.
- Engine-owned SQLITE_BUSY legacy marker handling.
- The workflow package consolidation (subtask 2).

## Dependency Notes

Depends on: none
This subtask waits for no other issue. If SKILL-388 (shared sqlite goalrunner migration and decode files) or SKILL-389 (RuntimeComponent session factory and the architecture inventory files) has landed, rebase onto it first; otherwise implement against the current tree, and whichever lands second keeps both edits. SKILL-391 keeps GOAL_PLANNING_WAVE_CAP in runtime-contracts. If SKILL-397 has edited domain goalrunner/model, keep planningStatusSnapshot in that package. SKILL-393 touches host/process and launcher/review only, so there is no shared file.

## Validation Strategy

Goal build gate: compile all runtime-kotlin modules; run the sqlite, contracts and skills unit tests, the runtime-core repoTest architecture guards (acyclicity, ambient environment, raw-map) and spotless. The new sqlite review-stats diagnostics test covers the realistic bug of a silently dropped degradation record.

## Implementation Details

Scope is this subtask only: F-001 to F-005, criteria 1-14. F-006, the workflow git/github package moves, belongs to subtask 2 and is not planned here. Work against the current tree at `base/SKILL-380-phase-slot-strategies`. SKILL-387/388/389/391/392/393/395 have landed; SKILL-390 and SKILL-397 have not. All paths below are relative to `../../../runtime-kotlin` unless stated otherwise.

### Task 1 — F-002: delete the vacuous guard (AC 4)
- Delete `runtime-core/src/repoTest/kotlin/skillbill/architecture/InfrastructureSkillsImportDirectionArchitectureTest.kt`. No code references it. `../../../agent/history.md` mentions it, but that file is append-only, so leave it alone.
- Add no replacement guard and no new `*ArchitectureTest.kt`.
- Tests: none.

### Task 2 — F-001: drop the contracts → application test edge (AC 1, 2, 3, 14)
- `runtime-infra/contracts/build.gradle.kts`: delete the `testImplementation(project(":runtime-application"))` line. This only removes a line and adds none.
- `runtime-infra/contracts/src/test/kotlin/skillbill/infrastructure/contracts/workflow/decomposition/SchemaValidatorPortLoudFailTest.kt` and `DecompositionManifestValidationTest.kt`:
  - Delete the four `skillbill.application.decomposition.*` imports at L3-6. `baseBranch`, `executionModel` and `parentSpecPath` are stale imports; those names appear only as named constructor arguments.
  - Rewrite the private `encodeDecompositionManifestYaml(manifest, validator, fileStore, sourceLabel = "<in-memory>"): String` helper at the bottom of each file. Inline the body of `DecompositionManifestFileWrites.kt:88-114` without the application `ValidatedDecompositionManifestYaml` wrapper:
    1. `val wireMap = validator.encodeManifestWireMap(manifest, sourceLabel)` (ports extension `skillbill.ports.workflow.decomposition.encodeManifestWireMap`)
    2. `val yaml = fileStore.encodeManifestYaml(wireMap)`
    3. `when (val result = validator.validateYamlTextResult(yaml, sourceLabel))`: return `result.yamlText` for `AcceptedUnchanged` and `AcceptedAfterRepair`. For `Rejected`, call `result.requireAccepted(sourceLabel)` (domain, `skillbill.workflow.decomposition.model`), then `error("Unreachable rejected decomposition manifest result.")`.
  - Keep `requireAccepted` so the loud-fail tests still see the same typed `InvalidDecompositionManifestSchemaError`. Call sites keep their signatures.
- Leave the stack-trace string literal in `FileSystemValidationGateJunitFindingsTest.kt` unchanged.
- Tests: no new tests. The existing contract tests keep their assertions.

### Task 3 — F-005: move planning-status policy to the domain (AC 13)
- Move `planningStatusSnapshot` and its private helpers verbatim from `runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/workflow/goalrunner/planning/GoalPlanningStatusSnapshotDerivation.kt` into a new file `runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalPlanningStatusSnapshot.kt` (package `skillbill.goalrunner.model`).
- Make the function `public`, since it is now called across modules. Keep the helpers `private`. Delete the sqlite file.
- Feasibility: the function uses no `java.nio` and no `skillbill.ports`, and runtime-domain already depends on runtime-contracts, so `GOAL_PLANNING_WAVE_CAP` stays where SKILL-391 put it. If the body turns out to name a ports type, stop and re-scope; do not add a dependency.
- `GoalPlanningStatusProjectionSql.kt`: import `skillbill.goalrunner.model.planningStatusSnapshot`. Replace both `"prepared"` literals (around L80 and L94) with `GoalPlanningPreparationState.PREPARED.wireValue`, as sibling `GoalPlanningPreparationRecordSql.kt` already does. If a literal sits inside SQL text, bind it as a parameter or interpolate the wire value so the SQL stays byte-identical at runtime.
- Move any existing sqlite test that calls `planningStatusSnapshot` directly to the matching domain test package (`runtime-domain/src/test/kotlin/skillbill/goalrunner/model/`), unchanged. No new tests: this is a pure move.

### Task 4 — F-004: consolidate the external add-on source readers (AC 11, 12)
- Move `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/file/FileExternalAgentAddonSourceConfigStore.kt` to `.../skills/externaladdon/` and change its package to `skillbill.infrastructure.skills.externaladdon`. Delete the now-empty `skills.file` main package.
- Move its test (`skills/src/test/.../skills/file/FileExternalAgentAddonSourceConfigStoreTest.kt`) to the matching `externaladdon` test package so test packages still mirror main.
- Update the import in `runtime-core/src/main/kotlin/skillbill/di/review/RuntimeReviewAddonCatalogProvides.kt` and in any other consumer the grep finds.
- In `FileExternalAddonSourceConfigParsing.kt` (package `externaladdon`), declare `internal fun resolveSourcePath(userHome: Path, rawPath: String): Path` once. It expands `~` against `userHome` and resolves relative paths against `JdkHostPlatformPort.resolveWorkingDirectory()`, the seam `FileExternalPlatformPackSourceConfigStore` already uses. Delete the per-store copies.
- Add one shared entry-read helper for config-path resolution, file read, `IllegalArgumentException` translation and the list-shape check. It takes the error-message prefix and list-shape message as parameters, so the agent store keeps "External agent add-on config at …" / "must be a list of source entries." and the platform store keeps "External addon config at …" / "must be a list of {path, platform} entries." byte-identical.
- Remove every `System.getProperty` call from both stores. Keep both `@Inject` no-arg constructors and both public port contracts, so the DI graph is unchanged. Keep the existing typed `ExternalAddonConfigError`; add no exception types (SKILL-398).
- `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/runtime-infra-skills-ambient-environment-baseline.txt`: remove both store rows. The file may end up empty, like the other infra baselines. Add no row to any baseline.
- Tests: no new tests. The moved store test and the platform store test already pin the messages and path resolution. Update only the moved test's package and imports.

### Task 5 — F-003: thread RuntimeDiagnostics explicitly through SQLite (AC 5, 6, 7, 8, 9)
Sqlite main lives under `runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/`. Diagnostics change only which warnings are recorded, never payload or persisted values.

5a. Migration operation (AC 8)
- `core/migration/DatabaseMigrationSteps.kt`:
  - Change `DatabaseMigration`'s primary constructor to `operation: (Connection, RuntimeDiagnostics) -> Unit` and `fun apply(connection, diagnostics)`.
  - Add one secondary constructor taking `operation: (Connection) -> Unit` that adapts it as `{ connection, _ -> operation(connection) }`. The 45 connection-only entries in `DatabaseMigrationEntries.kt` and the test entry in `DatabaseWriteReadinessTest.kt:94` then stay unchanged.
  - Fallback if the build phase reports overload ambiguity on lambdas or callable references: drop the secondary constructor and wrap each connection-only entry as `{ connection, _ -> ... }`.
- `workflow/goalrunner/runner/LegacyGoalRunnerControlLedgerMigration.kt`: change the signature to `applyLegacyGoalRunnerControlLedgerMigration(connection: Connection, diagnostics: RuntimeDiagnostics)` and replace `connection.sqliteDiagnostics()` at L52 with `diagnostics`. Keep SKILL-388's exact-int parse logic untouched. The entry `operation = ::applyLegacyGoalRunnerControlLedgerMigration` now resolves to the primary constructor.
- `core/migration/DatabaseMigrations.kt`:
  - `apply(connection, diagnostics, migrationSet = migrations)`: remove the diagnostics default and keep the `migrationSet` default.
  - Delete the `existingDiagnostics`/`ownsDiagnostics` attach logic and the try/finally detach.
  - Call `migration.apply(this, diagnostics)`.

5b. DatabaseRuntime and the readiness gate (AC 6, 9)
- `core/schema/DatabaseRuntime.kt`:
  - Remove the diagnostics defaults on `ensureWriteReady`, `openDbAt`, `establishSchemaReadiness` and `openReadDbAt`.
  - Give `openDb(cliValue, environment, userHome, diagnostics)` and `openReadDb(..., diagnostics)` a required `diagnostics` parameter and pass it through. Change `ensureDatabase(path)` to `ensureDatabase(path, diagnostics)`.
  - Delete `openReadDbIfPresent(cliValue, …)`, which has 0 callers. Keep `openReadDbIfPresentAt`.
  - Drop the `InternalSqliteDiagnostics` import.
- `core/schema/DatabaseWriteReadinessGate.kt`: delete the `onSchemaEstablishment` constructor parameter and its call. Remove the default on `ensureReady`'s `establishSchema` lambda, so every caller supplies it. `DatabaseRuntime.ensureWriteReady` already passes it explicitly.
  - Criterion 9 names "DatabaseRuntime.ensureWriteReady", but that function has no `establishSchema` parameter. The defaulted one is `DatabaseWriteReadinessGate.ensureReady`, the gate `ensureWriteReady` delegates to. This is where the default is removed; the audit should read criterion 9 against the gate.
- `core/ops/ConnectionTransactions.kt:120` (`inNestedWriteTransaction`): remove the diagnostics default. Thread diagnostics to its seven main callers:
  - `GoalPlanningPreparationRecordSql.kt:14`
  - `GoalSharedPreplanSql.kt:38/63/113`
  - `GoalSubtaskPlanSql.kt:37/47`
  - `FeatureTaskRuntimeWorkerStore.kt:24`
  - To reach them, add a `diagnostics: RuntimeDiagnostics` constructor parameter to `GoalPlanningPreparationStore` and to the shared-preplan and subtask-plan stores or functions it delegates to. `SQLiteUnitOfWork` (around L96) passes its own `diagnostics`.

5c. Review-stats chain (AC 7)
- `SQLiteRepositories.kt`:
  - `SQLiteUnitOfWork` builds `SQLiteReviewRepository(connection, clock, runtimeVersion, diagnostics)`.
  - `SQLiteReviewRepository` gains `diagnostics: RuntimeDiagnostics` and delegates `WorkflowStatsRepository by SQLiteWorkflowStatsRepository(connection, diagnostics)`.
  - `SQLiteWorkflowStatsRepository` passes it to `ReviewStatsRuntime.featureVerifyStats(connection, diagnostics)` and `featureTaskRuntimeStats(connection, diagnostics)`.
  - Delete the dead `sessionClock` and `sessionDiagnostics` getters (L80-81).
- `review/stats/ReviewStatsRuntime.kt`: both functions take `diagnostics` and pass it to the builders.
- `review/stats/ReviewWorkflowStats.kt`: `buildFeatureTaskRuntimeStats(rows, diagnostics)` and `buildFeatureVerifyStats(rows, diagnostics)`.
  - Pass `diagnostics` to `parseJsonList` at L29 and L83.
  - Replace `finishedRows.map(::durationSeconds)` at L88 with `finishedRows.map { durationSeconds(it, diagnostics) }`.
- `review/stats/ReviewStatsArithmetic.kt`: make `diagnostics` required on `parseJsonList` and `durationSeconds`. Drop the `InternalSqliteDiagnostics` import.

5d. Telemetry, reconciliation and workflow-state chains (AC 5, 6)
- Lifecycle telemetry:
  - `LifecycleTelemetryStore` (companion `invoke`) takes `diagnostics` and passes it to `LifecycleTelemetryStoreAdapters` and each `LifecycleTelemetry*SessionAdapter`. The adapters pass it to `emit*Finished`, which replaces `connection.sqliteDiagnostics()` at `LifecycleTelemetryEmit.kt:41/68/94`.
  - Remove the defaults in `LifecycleTelemetryPayloads.kt:66/218/285` and `LifecycleTelemetryDurations.kt:13`.
- Goal telemetry:
  - Thread `diagnostics` to the `GoalTelemetryEmit.kt` emitters (L59/80) and `saveGoalIssueFinished` in `GoalTelemetrySave.kt` (L241).
  - Remove the defaults in `GoalTelemetryPayloads.kt:100/125`.
- Reconciliation: `SQLiteTelemetryReconciliationRepository` passes diagnostics through `reconcileStaleTelemetrySessions` to `StaleSessionReconciler` and its emit calls (L50/59/67/108).
- Workflow state:
  - `WorkflowStateStore` passes diagnostics to `FeatureTaskWorkflowStateStore`, then `FeatureTaskRuntimeWorkerStore`.
  - `Connection.featureTaskRuntimeWorkerOwnership` in `FeatureTaskWorkflowStateStoreSql.kt:64` takes `diagnostics` as a parameter instead of calling `sqliteDiagnostics()`. Callers are `FeatureTaskRuntimeWorkerStore.kt:15/184`.
- Every constructor in this chain is built by hand inside `SQLiteUnitOfWork` or the session factory from the one session `diagnostics`. kotlin-inject wiring is unchanged.

5e. Session factory and registry deletion (AC 5)
- `SQLiteDatabaseSessionFactory.kt`: delete the three `attachSqliteDiagnostics`/`detachSqliteDiagnostics` try/finally pairs (L49/64, L70/85, L133/137) and keep the bodies. Pass the factory's `diagnostics` into the `DatabaseRuntime` open calls and the unit of work. The constructor `(EnvironmentContext, Clock, RuntimeDiagnostics, WorkflowSnapshotValidator, String)` stays byte-identical (SKILL-389).
- `core/ops/InternalSqliteDiagnostics.kt`: delete the `InternalSqliteDiagnostics` object, `diagnosticsByConnection`, and the attach, `sqliteDiagnostics` and detach functions. Keep `recordMigrationNormalization`, `recordDegradedValue` and `degradedValuePreview` if it lives here.
- Rename the file to `core/ops/SqliteDiagnosticRecords.kt` so no main file is named after the deleted object.

5f. Sqlite test and testFixtures fallout (no behavior change)
- Make no new silent default anywhere in main.
- In sqlite test `TestSupport.kt` (package `skillbill.infrastructure.sqlite`), add one-argument extension overloads that pass `SqliteTestDiagnostics`:
  - `DatabaseRuntime.ensureDatabase(path)`
  - `DatabaseRuntime.openReadDb(cliValue, environment, userHome)`
  - `DatabaseRuntime.openDb(cliValue, environment, userHome)`
  - `DatabaseRuntime.establishSchemaReadiness(path)`
- Kotlin falls through to the extensions when the member is not applicable, so the ~237 existing test call sites stay byte-identical. That honours the non-goal of not moving the test entry points.
- Add one import of the extension to each subpackage test that uses them:
  - `operation/SqliteOperationProposalRepositoryTest`
  - `review/stage/ReviewStageStatePersistenceTest`
  - `telemetry/lifecycle/FeatureTaskRuntimeSharedEvidenceTelemetryStoreTest`
  - `telemetry/lifecycle/LifecycleTelemetryTruthfulnessTest`
  - `telemetry/redaction/TelemetryRedactionTest`
  - `telemetry/redaction/TelemetryAnonymousRedactionTest`
  - `workflow/goalrunner/planning/GoalPlanningPreparationStoreTest`
- testFixtures pass `SqliteTestDiagnostics` directly:
  - `SqliteTestDatabaseFixtures` (`establishSchemaReadiness`, and the `LifecycleTelemetryStore(...)` around L73)
  - `TelemetryReliabilitySupport`
  - `WorkflowStateStoreTestSupport`
  - the `withGoalPlanningPreparationRepository` helper
- `withLifecycleTelemetryStore` and `withTelemetryOutboxStore` keep their signatures, so cli, mcp and core tests are untouched.
- Add `SqliteTestDiagnostics` to these calls:
  - `DatabaseMigrations.apply(connection)` in `GoalPlanningPhaseOutputMigrationTest` ×3, `DatabaseMigrationsTest` ×6, `ReviewStageTelemetryTest:374` and `DatabaseWriteReadinessTest:100`
  - direct `parseJsonList` / `durationSeconds` / payload-builder test calls
- `DatabaseMigrationsTest.kt` (~L1568): replace the registry-attaching recording-driver setup with `DatabaseRuntime.establishSchemaReadiness(dbPath, recordingDiagnostics)`. Keep the assertion on the migration-normalization record; it is the existing regression coverage for the legacy ledger record.
- `DatabaseWriteReadinessTest.kt`: rewrite the five `DatabaseWriteReadinessGate(onSchemaEstablishment = …)` uses as `DatabaseWriteReadinessGate()` plus `ensureReady(dbPath) { count++; DatabaseRuntime.establishSchemaReadiness(dbPath, SqliteTestDiagnostics) }`. The same execution-count assertions are kept; this is coverage-preserving, not new.
- Mocks, if any are touched, use `relaxUnitFun = true`, never `relaxed = true`.

### Task 6 — New review-stats diagnostics test (AC 10)
- Location: a new `ReviewStatsDiagnosticsTest.kt` under `runtime-infra/sqlite/src/test/kotlin/skillbill/infrastructure/sqlite/review/stats/`, or added to the existing `SqliteDegradationDiagnosticsTest.kt`, reusing its private `RecordingDiagnostics`. Prefer the existing file if its package lets it build a `SQLiteUnitOfWork`.
- Setup: create a schema-ready temp DB, then build a `SQLiteUnitOfWork` on its connection with a recording `RuntimeDiagnostics` and the test clock.
- Criterion 10 says "feature-verify workflow row with malformed completed-phase JSON", but `completed_phase_ids` exists only on `feature_task_runtime_sessions`; `feature_verify_sessions` carries JSON in `gaps_found`. Cover both malformed-JSON reads, since each is a separate call site that can lose its diagnostics argument independently:
  - Test A: insert a finished `feature_verify_sessions` row with `gaps_found = '[not json'`, call `unitOfWork.reviews.featureVerifyStats()`, and assert a recorded warning containing `degraded review_stats.json_array`. Realistic bug: `buildFeatureVerifyStats` not threaded, so the record goes to a no-op.
  - Test B: insert a finished `feature_task_runtime_sessions` row with `completed_phase_ids = '[not json'`, call `featureTaskRuntimeStats()`, and make the same assertion. Realistic bug: the L29 call left on a default.
- Assert the observable warning text only, with no interaction verification. Seed rows with plain SQL `INSERT`s using only the columns the schema requires.

### Task 7 — Self-check before handing to build (no compile, no tests here)
Run these read-only greps; the build and validate phases own compilation, tests, guards and spotless:
- No `diagnosticsByConnection|attachSqliteDiagnostics|detachSqliteDiagnostics|sqliteDiagnostics\(|InternalSqliteDiagnostics` in sqlite main (AC 5).
- No `RuntimeDiagnostics = ` in sqlite main (AC 6).
- No `sessionClock|sessionDiagnostics|onSchemaEstablishment|fun openReadDbIfPresent\(` in sqlite main (AC 9).
- No `skillbill\.application` outside string literals under `runtime-infra` (AC 2).
- No `package skillbill.infrastructure.skills.file` in main, and exactly one `fun resolveSourcePath` in `externaladdon` (AC 11).
- No `System.getProperty` in either store (AC 11).
- No `"prepared"` in `GoalPlanningStatusProjectionSql.kt` (AC 13).
- `git diff` on `settings.gradle.kts` and `build.gradle.kts` files shows only removed lines (AC 14).
- `git diff --stat` on `baselines/` shows only deletions (AC 12).

### Constraints
- No new module, port, guard class, dependency bag, exception type, `settings.gradle.kts` include or `build.gradle.kts` dependency line.
- No baseline row is added.
- Wire, JSON, YAML, telemetry and persisted values stay byte-identical.
- The `SQLiteDatabaseSessionFactory` constructor is unchanged.
- SKILL-388's exact-int parsing is unchanged.
- `GOAL_PLANNING_WAVE_CAP` stays in runtime-contracts.
- Follow `../../../runtime-kotlin/ARCHITECTURE.md` Design Principles and `docs/code-principles.md`.
- No ARCHITECTURE.md or docs edits are expected. If a grep finds a doc naming `InternalSqliteDiagnostics` or `skills.file`, update that line only.
- Spotless runs in a regular clone, not a linked worktree. On a stale-cache error, rerun with `--no-configuration-cache`.
- Risks the build phase must confirm:
  - `DatabaseMigration` constructor overload resolution (fallback in 5a)
  - the test extension overloads resolving over the members
  - the contracts test classpath compiling without runtime-application
  - domain `planningStatusSnapshot` visibility from sqlite
  - the acyclicity, ambient-environment, raw-map and test-mirroring repoTest guards staying green

## Next Path

skill-bill goal SKILL-396

## Spec Path

.feature-specs/SKILL-396-runtime-infra-boundary-repairs/spec_subtask_1_infra-boundary-and-diagnostics-repairs.md
