package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection

internal object FeatureTaskPhaseStrategyTelemetryMigration {
  fun apply(connection: Connection) {
    DatabaseColumnMigrations.ensureColumn(connection, "feature_task_runtime_sessions", "phase_strategies", "TEXT")
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "feature_task_runtime_sessions",
      "phase_strategy_availability",
      "TEXT",
    )
  }
}
