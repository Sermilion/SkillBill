package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.schema.DatabasePaths
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.GoalPlanningPreparationStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.GoalRunnerControlStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

object SqliteTestDatabasePaths {
  const val DB_ENVIRONMENT_KEY: String = DatabasePaths.DB_ENVIRONMENT_KEY

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
