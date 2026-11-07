package skillbill.infrastructure.sqlite.core.migration.area.idestatus.buildrun

import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.BUILD_ID
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.COMMAND
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.ENDED_AT
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.EXIT_CODE
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.HISTORY_INDEX
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.OUTCOME
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.PARENT_INDEX
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.PARENT_RUN_ID
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.PID
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.PROJECT_KIND
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.REPOSITORY_IDENTITY
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.RUN_ID
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.RUN_INDEX
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.SHORT_COMMAND
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.STARTED_AT
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.TABLE
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.TOOL
import skillbill.infrastructure.sqlite.core.schema.installLaterDesktopChangeTriggers
import skillbill.ports.idestatus.model.AgentBuildOutcome
import java.sql.Connection

internal fun recordAgentBuildRuns(connection: Connection) {
  val outcomes = AgentBuildOutcome.entries.joinToString { "'${it.wireValue}'" }
  listOf(
    """
    CREATE TABLE IF NOT EXISTS $TABLE (
      $BUILD_ID TEXT PRIMARY KEY,
      $RUN_ID TEXT NOT NULL,
      $PARENT_RUN_ID TEXT NULL,
      $REPOSITORY_IDENTITY TEXT NOT NULL,
      $COMMAND TEXT NOT NULL,
      $SHORT_COMMAND TEXT NOT NULL,
      $TOOL TEXT NOT NULL,
      $PROJECT_KIND TEXT NOT NULL,
      $PID INTEGER NOT NULL,
      $STARTED_AT TEXT NOT NULL,
      $ENDED_AT TEXT NULL,
      $OUTCOME TEXT NULL CHECK ($OUTCOME IS NULL OR $OUTCOME IN ($outcomes)),
      $EXIT_CODE INTEGER NULL
    )
    """.trimIndent(),
    "CREATE INDEX IF NOT EXISTS $RUN_INDEX ON $TABLE ($RUN_ID, $STARTED_AT)",
    "CREATE INDEX IF NOT EXISTS $PARENT_INDEX ON $TABLE ($PARENT_RUN_ID, $STARTED_AT)",
    "CREATE INDEX IF NOT EXISTS $HISTORY_INDEX ON $TABLE ($REPOSITORY_IDENTITY, $COMMAND, $ENDED_AT)",
  ).forEach { sql ->
    connection.createStatement().use { statement -> statement.execute(sql) }
  }
  installLaterDesktopChangeTriggers(connection, setOf(TABLE))
}
