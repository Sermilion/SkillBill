package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.telemetry.STALE_SESSION_THRESHOLD_SECONDS
import skillbill.infrastructure.sqlite.telemetry.StaleSessionReconciliationPolicy
import skillbill.infrastructure.sqlite.telemetry.reconcileStaleFeatureTaskRuntimeSessions
import skillbill.infrastructure.sqlite.telemetry.reconcileStaleTelemetrySessions
import skillbill.infrastructure.sqlite.workflow.WorkflowStateStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.GoalPlanningPreparationStore
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import skillbill.ports.telemetry.model.TelemetryReconciliationResult
import skillbill.ports.workflow.WorkflowSnapshotValidator
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock

const val SAMPLE_REVIEW: String =
  """
  Routed to: bill-kotlin-code-review
  Review session ID: rvs-20260402-001
  Review run ID: rvw-20260402-001
  Detected review scope: unstaged changes
  Detected stack: kotlin
  Execution mode: inline
  Specialist reviews: architecture, testing, architecture

  ### 2. Risk Register
  - [F-001] Major | High | README.md:12 | README wording is stale after the routing change.
  - [F-002] Minor | Medium | install.sh:88 | Installer prompt wording is inconsistent with the new flow.
  """

const val TABLE_REVIEW: String =
  """
  Routed to: bill-kmp-code-review
  Review session ID: rvs-20260402-tbl-a
  Review run ID: rvw-20260402-tbl-a
  Detected review scope: files
  Detected stack: kmp
  Execution mode: inline

  ## Section 2 — Risk Register

  | # | Severity | Category | File | Line(s) | Finding |
  |---|----------|----------|------|---------|---------|
  | 1 | High | Correctness | ViewModel.kt | 147-152 | init block calls refresh() |
  | 2 | Medium | UI | Screen.kt | 156 | Loading indicator not centered |
  """

fun tempDbConnection(prefix: String): Pair<Path, Connection> {
  val tempDir = Files.createTempDirectory(prefix)
  val dbPath = tempDir.resolve("metrics.db")
  return dbPath to DatabaseRuntime.ensureDatabase(dbPath)
}

internal fun DatabaseRuntime.ensureDatabase(path: Path): Connection = ensureDatabase(path, SqliteTestDiagnostics)

internal fun DatabaseRuntime.establishSchemaReadiness(path: Path) =
  establishSchemaReadiness(path, SqliteTestDiagnostics)

internal fun DatabaseRuntime.openDb(
  cliValue: String?,
  environment: Map<String, String>,
  userHome: Path,
) = openDb(cliValue, environment, userHome, SqliteTestDiagnostics)

internal fun DatabaseRuntime.openReadDb(
  cliValue: String?,
  environment: Map<String, String>,
  userHome: Path,
) = openReadDb(cliValue, environment, userHome, SqliteTestDiagnostics)

internal fun DatabaseMigrations.apply(connection: Connection) = apply(connection, SqliteTestDiagnostics)

internal fun <T> Connection.inNestedWriteTransaction(block: Connection.() -> T): T =
  inNestedWriteTransaction(SqliteTestDiagnostics, block)

internal fun reconcileStaleTelemetrySessions(
  connection: Connection,
  request: TelemetryReconciliationRequest,
  runtimeVersion: String = "test-runtime-version",
): TelemetryReconciliationResult =
  reconcileStaleTelemetrySessions(connection, request, SqliteTestDiagnostics, runtimeVersion)

internal fun reconcileStaleTelemetrySessions(
  connection: Connection,
  clock: Clock,
  level: String,
  runtimeVersion: String = "test-runtime-version",
  policy: StaleSessionReconciliationPolicy = StaleSessionReconciliationPolicy(),
): TelemetryReconciliationResult =
  reconcileStaleTelemetrySessions(
    connection = connection,
    request =
      TelemetryReconciliationRequest(
        level = level,
        cadenceSeconds = 0L,
        maximumBatchSize = Int.MAX_VALUE,
        sessionThresholdSeconds = policy.sessionThresholdSeconds,
        goalIssueAbandonmentDays = policy.goalIssueAbandonmentDays,
        now = clock.instant(),
      ),
    runtimeVersion = runtimeVersion,
  )

internal fun reconcileStaleFeatureTaskRuntimeSessions(
  connection: Connection,
  runtimeVersion: String = "test-runtime-version",
  thresholdSeconds: Long = STALE_SESSION_THRESHOLD_SECONDS,
): Int = reconcileStaleFeatureTaskRuntimeSessions(connection, SqliteTestDiagnostics, runtimeVersion, thresholdSeconds)

internal fun WorkflowStateStore(
  connection: Connection,
  clock: Clock,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
): WorkflowStateStore = WorkflowStateStore(connection, clock, workflowSnapshotValidator, SqliteTestDiagnostics)

internal fun GoalPlanningPreparationStore(connection: Connection): GoalPlanningPreparationStore =
  GoalPlanningPreparationStore(connection, SqliteTestDiagnostics)
