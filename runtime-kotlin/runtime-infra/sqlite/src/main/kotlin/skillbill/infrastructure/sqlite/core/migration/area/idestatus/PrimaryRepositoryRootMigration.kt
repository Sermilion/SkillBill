package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection

internal object PrimaryRepositoryRootMigration {
  fun apply(connection: Connection) {
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "standalone_phase_status",
      PRIMARY_REPOSITORY_ROOT_COLUMN,
      "TEXT",
    )
    DatabaseColumnMigrations.ensureColumn(
      connection,
      "ide_status_workflow_execution",
      PRIMARY_REPOSITORY_ROOT_COLUMN,
      "TEXT",
    )
  }
}

internal const val PRIMARY_REPOSITORY_ROOT_COLUMN = "primary_repository_root"
