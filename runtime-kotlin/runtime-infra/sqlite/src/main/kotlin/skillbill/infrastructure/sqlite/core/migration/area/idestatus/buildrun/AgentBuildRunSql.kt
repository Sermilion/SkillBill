package skillbill.infrastructure.sqlite.core.migration.area.idestatus.buildrun

import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.BUILD_ID
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.COMMAND
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.ENDED_AT
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.EXIT_CODE
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.OUTCOME
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.PARENT_RUN_ID
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.PID
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.PROJECT_KIND
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.REPOSITORY_IDENTITY
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.RUN_ID
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.SHORT_COMMAND
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.STARTED_AT
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.TABLE
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.TOOL
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildRun
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val BUILD_SOURCE = "agent-build-runs"

private const val RETAINED_SETTLED_BUILDS: Int = 20

private const val HISTORY_WINDOW: Int = 5

private val BUILD_INSTANT_FORMAT: DateTimeFormatter =
  DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

internal fun Instant.toBuildText(): String = BUILD_INSTANT_FORMAT.format(truncatedTo(ChronoUnit.MILLIS))

internal const val INSERT_BUILD_SQL: String =
  "INSERT INTO $TABLE ($BUILD_ID, $RUN_ID, $PARENT_RUN_ID, $REPOSITORY_IDENTITY, $COMMAND, $SHORT_COMMAND, " +
    "$TOOL, $PROJECT_KIND, $PID, $STARTED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

internal const val SETTLE_BUILD_SQL: String =
  "UPDATE $TABLE SET $ENDED_AT = ?, $OUTCOME = ?, $EXIT_CODE = ? WHERE $BUILD_ID = ? AND $ENDED_AT IS NULL"

internal const val SELECT_BUILD_KEY_SQL: String =
  "SELECT $REPOSITORY_IDENTITY, $COMMAND FROM $TABLE WHERE $BUILD_ID = ?"

internal const val PRUNE_SETTLED_SQL: String =
  "DELETE FROM $TABLE WHERE $REPOSITORY_IDENTITY = ? AND $COMMAND = ? AND $ENDED_AT IS NOT NULL " +
    "AND $BUILD_ID NOT IN (SELECT $BUILD_ID FROM $TABLE WHERE $REPOSITORY_IDENTITY = ? AND $COMMAND = ? " +
    "AND $ENDED_AT IS NOT NULL ORDER BY $ENDED_AT DESC, $BUILD_ID DESC LIMIT $RETAINED_SETTLED_BUILDS)"

internal const val ABANDON_OPEN_SQL: String =
  "UPDATE $TABLE SET $OUTCOME = ? WHERE $RUN_ID = ? AND $ENDED_AT IS NULL AND $OUTCOME IS NULL"

internal const val HISTORY_SQL: String =
  "SELECT $OUTCOME, $STARTED_AT, $ENDED_AT FROM $TABLE WHERE $REPOSITORY_IDENTITY = ? AND $COMMAND = ? " +
    "AND $ENDED_AT IS NOT NULL AND $STARTED_AT < ? ORDER BY $ENDED_AT DESC, $BUILD_ID DESC LIMIT $HISTORY_WINDOW"

internal fun latestForRunSql(count: Int): String {
  val placeholders = List(count) { "?" }.joinToString()
  return "SELECT * FROM $TABLE WHERE $RUN_ID IN ($placeholders) OR $PARENT_RUN_ID IN ($placeholders) " +
    "ORDER BY ($ENDED_AT IS NULL) DESC, $STARTED_AT DESC, $BUILD_ID DESC LIMIT 1"
}

internal fun ResultSet.readBuildRun(): AgentBuildRun =
  AgentBuildRun(
    buildId = getString(BUILD_ID),
    runId = getString(RUN_ID),
    parentRunId = getString(PARENT_RUN_ID),
    repositoryIdentity = getString(REPOSITORY_IDENTITY),
    command = getString(COMMAND),
    shortCommand = getString(SHORT_COMMAND),
    tool = getString(TOOL),
    projectKind = getString(PROJECT_KIND),
    pid = getLong(PID),
    startedAt = Instant.parse(getString(STARTED_AT)),
    endedAt = getString(ENDED_AT)?.let(Instant::parse),
    outcome = readBuildOutcome(),
    exitCode = getInt(EXIT_CODE).takeIf { !wasNull() },
  )

private fun ResultSet.readBuildOutcome(): AgentBuildOutcome? {
  val raw = getString(OUTCOME) ?: return null
  return AgentBuildOutcome.fromWire(raw)
    ?: throw invalidIdeStatusSchemaError(BUILD_SOURCE, OUTCOME, "Unknown build outcome '$raw'.")
}

internal fun ResultSet.readSettledOutcome(): AgentBuildOutcome =
  readBuildOutcome() ?: throw invalidIdeStatusSchemaError(BUILD_SOURCE, OUTCOME, "Settled build has no outcome.")
