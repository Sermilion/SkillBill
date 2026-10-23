package skillbill.infrastructure.sqlite

import skillbill.infrastructure.contracts.BlockIncidentSchemaValidator
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

fun sqliteDatabaseSessionFactory(
  userHome: Path,
  dbPathOverride: String? = null,
  environment: Map<String, String>,
  clock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
  diagnostics: RuntimeDiagnostics = SqliteTestDiagnostics,
  runtimeVersion: String = "test-runtime-version",
): SQLiteDatabaseSessionFactory =
  SQLiteDatabaseSessionFactory(
    EnvironmentContext(
      dbPathOverride = dbPathOverride,
      environment = environment,
      userHome = userHome,
    ),
    clock,
    diagnostics,
    SqliteTestWorkflowSnapshotValidator,
    runtimeVersion,
    BlockIncidentSchemaValidator(),
  )

object SqliteTestWorkflowSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(
    snapshot: WorkflowStateSnapshot,
    slug: String,
  ) = Unit
}

object SqliteTestDiagnostics : RuntimeDiagnostics {
  private val warnings = mutableListOf<String>()

  fun recordedWarnings(): List<String> = warnings.toList()

  fun reset() {
    warnings.clear()
  }

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }
}
