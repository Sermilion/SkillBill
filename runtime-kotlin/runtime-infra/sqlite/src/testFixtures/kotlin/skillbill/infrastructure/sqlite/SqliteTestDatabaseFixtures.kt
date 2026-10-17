package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.schema.DatabasePaths
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.infrastructure.sqlite.workflow.WorkflowStateStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.GoalPlanningPreparationStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.GoalRunnerControlStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readBytes

object SqliteTestDatabasePaths {
  const val DB_ENVIRONMENT_KEY: String = RuntimeDatabaseEnvironmentKeys.DATABASE_PATH

  fun defaultDbPath(userHome: Path): Path = DatabasePaths.defaultDbPath(userHome)
}

data class TemporarySchemaDatabase(
  val tempDir: Path,
  val dbPath: Path,
  val connection: Connection,
) : AutoCloseable {
  override fun close() {
    connection.close()
  }
}

fun establishTemporarySchemaReadiness(prefix: String = "skillbill-sqlite-test"): TemporarySchemaDatabase {
  val tempDir = Files.createTempDirectory(prefix)
  val dbPath = tempDir.resolve("metrics.db")
  val connection = ensureTestDatabase(dbPath)
  return TemporarySchemaDatabase(tempDir = tempDir, dbPath = dbPath, connection = connection)
}

fun ensureTestDatabase(dbPath: Path): Connection {
  DatabaseRuntime.establishSchemaReadiness(dbPath.toAbsolutePath().normalize(), SqliteTestDiagnostics)
  return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}")
}

fun withGoalPlanningPreparationRepository(
  userHome: Path,
  dbPath: Path,
  block: (GoalPlanningPreparationRepository) -> Unit,
) {
  ensureTestDatabase(dbPath).use { connection ->
    block(GoalPlanningPreparationStore(connection, SqliteTestDiagnostics))
  }
}

fun withGoalRunnerControlRepository(
  dbPath: Path,
  block: (GoalRunnerControlRepository) -> Unit,
) {
  ensureTestDatabase(dbPath).use { connection ->
    block(GoalRunnerControlStore(connection))
  }
}

fun withLifecycleTelemetryStore(
  userHome: Path,
  dbPath: Path,
  block: (LifecycleTelemetryRepository) -> Unit,
) {
  ensureTestDatabase(dbPath).use { connection ->
    block(LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics))
  }
}

fun withTelemetryOutboxStore(
  userHome: Path,
  dbPath: Path,
  version: String = "test-runtime-version",
  block: (TelemetryOutboxTestHandle) -> Unit,
) {
  ensureTestDatabase(dbPath).use { connection ->
    block(telemetryOutboxOnConnection(connection, version))
  }
}

fun sqliteSessionFactoryForTests(
  userHome: Path,
  dbPathOverride: String? = null,
  environment: Map<String, String>,
  clock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
  diagnostics: RuntimeDiagnostics = SqliteTestDiagnostics,
): SQLiteDatabaseSessionFactory =
  sqliteDatabaseSessionFactory(
    userHome = userHome,
    dbPathOverride = dbPathOverride,
    environment = environment,
    clock = clock,
    diagnostics = diagnostics,
  )

data class MaintenanceWorkflowSnapshot(
  val workflowId: String,
  val sessionId: String,
  val issueKey: String,
  val currentStepId: String,
  val workflowStatus: String,
  val repositoryIdentity: String,
  val phaseId: String,
  val phaseAttempt: Int,
  val settlementEnvelopeJson: String,
)

fun pragmaIntegrityCheck(dbPath: Path): String {
  DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}").use { connection ->
    connection.createStatement().executeQuery("PRAGMA integrity_check").use { rows ->
      require(rows.next()) { "integrity_check returned no rows" }
      return rows.getString(1)
    }
  }
}

fun walCheckpointTruncate(dbPath: Path) {
  DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}").use { connection ->
    connection.createStatement().execute("PRAGMA wal_checkpoint(TRUNCATE)")
  }
}

fun baselineReviewMetricsBackupPath(userHome: Path): Path =
  userHome.resolve(".skill-bill/backups/baseline/review-metrics.db")

fun attemptBaselineReviewMetricsRestore(userHome: Path): SkillBillRuntimeException {
  val liveDb = SqliteTestDatabasePaths.defaultDbPath(userHome)
  val backup = baselineReviewMetricsBackupPath(userHome)
  val primaryBefore = liveDb.readBytes()
  val error =
    runCatching {
      DatabaseRuntime.openReadDb(
        backup.toString(),
        emptyMap(),
        userHome,
        SqliteTestDiagnostics,
      ).close()
    }.exceptionOrNull() as? SkillBillRuntimeException
      ?: error("expected typed database access failure for unreadable baseline backup")
  check(primaryBefore.contentEquals(liveDb.readBytes())) {
    "baseline restore must not overwrite live database bytes when backup admission fails"
  }
  return error
}

fun seedMaintenanceWorkflowHistory(
  dbPath: Path,
  workflowId: String = "wftr-maint-422",
  issueKey: String = "SKILL-422",
): MaintenanceWorkflowSnapshot {
  val sessionId = "ftr-maint-422"
  val phaseId = "implement"
  val phaseAttempt = 1
  val settlementEnvelopeJson = """{"status":"completed","produced_outputs":{"value":"maintenance-seed"}}"""
  val repositoryIdentity = "repo/skill-bill"
  ensureTestDatabase(dbPath).use { connection ->
    val store =
      WorkflowStateStore(
        connection,
        Clock.systemUTC(),
        SqliteTestWorkflowSnapshotValidator,
        SqliteTestDiagnostics,
      )
    val row =
      WorkflowStateRecord(
        workflowId = workflowId,
        sessionId = sessionId,
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = WorkflowStatus.RUNNING.wireValue,
        currentStepId = "implement",
        stepsJson =
          """[{"step_id":"preplan","status":"completed","attempt_count":1},""" +
            """{"step_id":"implement","status":"running","attempt_count":1}]""",
        artifactsJson = """{"issue_key":"$issueKey"}""",
        startedAt = "2026-10-17T12:00:00Z",
        updatedAt = "2026-10-17T12:00:00Z",
        finishedAt = null,
        mode = RUNTIME,
      )
    store.saveFeatureTaskWorkflow(row, RUNTIME)
    store.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        workflowId = workflowId,
        normalizedIssueKey = issueKey,
        repositoryIdentity = repositoryIdentity,
        governedSpecPath = ".feature-specs/$issueKey/spec.md",
        mode = RUNTIME,
        routeScope = FeatureTaskRouteScope.STANDALONE,
      ),
    )
    SqliteFeatureTaskPhaseSettlementStore(connection, Clock.systemUTC(), SqliteTestDiagnostics).upsert(
      FeatureTaskPhaseSettlement(
        workflowId = workflowId,
        phaseId = phaseId,
        attempt = phaseAttempt,
        kind = FeatureTaskPhaseSettlementKind.Complete,
        envelopeJson = settlementEnvelopeJson,
        recordedAt = "2026-10-17T12:05:00Z",
      ),
    )
    connection.prepareStatement(
      """
      INSERT INTO goal_issue_progress (
        parent_workflow_id,
        issue_key,
        first_started_at,
        last_activity_at,
        finished_at
      ) VALUES (?, ?, ?, ?, NULL)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, issueKey)
      statement.setString(3, "2026-10-17T12:00:00Z")
      statement.setString(4, "2026-10-17T12:05:00Z")
      statement.executeUpdate()
    }
  }
  return MaintenanceWorkflowSnapshot(
    workflowId = workflowId,
    sessionId = sessionId,
    issueKey = issueKey,
    currentStepId = "implement",
    workflowStatus = WorkflowStatus.RUNNING.wireValue,
    repositoryIdentity = repositoryIdentity,
    phaseId = phaseId,
    phaseAttempt = phaseAttempt,
    settlementEnvelopeJson = settlementEnvelopeJson,
  )
}

fun readMaintenanceWorkflowSnapshot(
  dbPath: Path,
  expected: MaintenanceWorkflowSnapshot,
): MaintenanceWorkflowSnapshot {
  DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}").use { connection ->
    val store =
      WorkflowStateStore(
        connection,
        Clock.systemUTC(),
        SqliteTestWorkflowSnapshotValidator,
        SqliteTestDiagnostics,
      )
    val row = store.getFeatureTaskWorkflowAsMode(expected.workflowId, RUNTIME)
    require(row != null) { "missing workflow row ${expected.workflowId}" }
    val identity = store.getFeatureTaskExecutionIdentity(expected.workflowId)
    require(identity != null) { "missing execution identity ${expected.workflowId}" }
    val settlement =
      SqliteFeatureTaskPhaseSettlementStore(connection, Clock.systemUTC(), SqliteTestDiagnostics).find(
        expected.workflowId,
        expected.phaseId,
        expected.phaseAttempt,
      )
    require(settlement != null) { "missing settlement ${expected.workflowId}/${expected.phaseId}" }
    connection.prepareStatement(
      "SELECT issue_key FROM goal_issue_progress WHERE parent_workflow_id = ?",
    ).use { statement ->
      statement.setString(1, expected.workflowId)
      statement.executeQuery().use { rows ->
        require(rows.next()) { "missing goal_issue_progress for ${expected.workflowId}" }
        require(rows.getString(1) == expected.issueKey) { "unexpected issue_key" }
      }
    }
    return MaintenanceWorkflowSnapshot(
      workflowId = row.workflowId,
      sessionId = row.sessionId,
      issueKey = identity.normalizedIssueKey,
      currentStepId = row.currentStepId,
      workflowStatus = row.workflowStatus,
      repositoryIdentity = identity.repositoryIdentity,
      phaseId = settlement.phaseId,
      phaseAttempt = settlement.attempt,
      settlementEnvelopeJson = settlement.envelopeJson,
    )
  }
}
