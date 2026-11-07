package skillbill.infrastructure.sqlite.core.migration.area.idestatus.validationcheck

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.schema.installLaterDesktopChangeTriggers
import skillbill.infrastructure.sqlite.core.schema.reinstallStandalonePhaseStatusChangeTriggers
import skillbill.ports.idestatus.model.LiveChecksAvailability
import skillbill.ports.idestatus.model.StandaloneValidationOutcome
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckOutcome
import java.sql.Connection

internal const val VALIDATION_CHECK_EVENTS_TABLE: String = "validation_check_events"

internal const val LIVE_CHECKS_COLUMN: String = "live_checks"

internal const val VALIDATION_OUTCOME_COLUMN: String = "validation_outcome"

internal const val VALIDATION_OUTCOME_ASSIGNMENT: String =
  "$VALIDATION_OUTCOME_COLUMN = COALESCE(?, $VALIDATION_OUTCOME_COLUMN)"

internal fun recordValidationCheckEvents(connection: Connection) {
  val events = ValidationCheckEvent.entries.joinToString { "'${it.wireValue}'" }
  val outcomes = ValidationCheckOutcome.entries.joinToString { "'${it.wireValue}'" }
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS $VALIDATION_CHECK_EVENTS_TABLE (
        invocation_id TEXT NOT NULL,
        sequence INTEGER NOT NULL CHECK (sequence >= 1),
        check_id TEXT NOT NULL,
        event TEXT NOT NULL CHECK (event IN ($events)),
        name TEXT NOT NULL,
        command TEXT NOT NULL,
        working_dir TEXT NOT NULL,
        outcome TEXT NULL CHECK (outcome IS NULL OR outcome IN ($outcomes)),
        exit_code INTEGER NULL,
        note TEXT NULL,
        attempt INTEGER NOT NULL CHECK (attempt >= 0),
        recorded_at TEXT NOT NULL,
        PRIMARY KEY (invocation_id, sequence)
      )
      """.trimIndent(),
    )
  }
  val availability = LiveChecksAvailability.entries.joinToString { "'${it.wireValue}'" }
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "standalone_phase_status",
    LIVE_CHECKS_COLUMN,
    "TEXT NULL CHECK ($LIVE_CHECKS_COLUMN IS NULL OR $LIVE_CHECKS_COLUMN IN ($availability))",
  )
  val validationOutcomes = StandaloneValidationOutcome.entries.joinToString { "'${it.wireValue}'" }
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "standalone_phase_status",
    VALIDATION_OUTCOME_COLUMN,
    "TEXT NULL CHECK ($VALIDATION_OUTCOME_COLUMN IS NULL OR $VALIDATION_OUTCOME_COLUMN IN ($validationOutcomes))",
  )
  installLaterDesktopChangeTriggers(connection, setOf(VALIDATION_CHECK_EVENTS_TABLE))
  reinstallStandalonePhaseStatusChangeTriggers(connection)
}
