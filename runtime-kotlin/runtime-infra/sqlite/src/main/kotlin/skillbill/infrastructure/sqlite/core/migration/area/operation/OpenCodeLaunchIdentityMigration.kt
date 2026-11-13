package skillbill.infrastructure.sqlite.core.migration.area.operation

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.schema.recordOpenCodeLaunchIdentity

internal fun openCodeLaunchIdentityMigration(): DatabaseMigration =
  DatabaseMigration(
    version = 130,
    name = "record-opencode-launch-identity",
    operation = { connection ->
      recordOpenCodeLaunchIdentity(connection)
      addOperationOpenCodeIdentity(connection)
    },
  )
