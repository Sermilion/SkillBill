# SKILL-396 Subtask 1 - Infra boundary and diagnostics repairs

Parent spec: [.feature-specs/SKILL-396-runtime-infra-boundary-repairs/spec.md](./spec.md)
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

## Next Path

skill-bill goal SKILL-396

## Spec Path

.feature-specs/SKILL-396-runtime-infra-boundary-repairs/spec_subtask_1_infra-boundary-and-diagnostics-repairs.md
