package skillbill.infrastructure.sqlite.core.migration.area.operation

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.schema.retireUnsupportedLaunchAgents

internal fun retiredUnsupportedLaunchAgentMigrations(): List<DatabaseMigration> =
  listOf(
    DatabaseMigration(
      version = 129,
      name = "retire-unsupported-agent-ids",
      operation = ::retireUnsupportedLaunchAgents,
    ),
    DatabaseMigration(
      version = 130,
      name = "normalize-launch-agents-after-vocabulary-change",
      operation = ::retireUnsupportedLaunchAgents,
    ),
  )
