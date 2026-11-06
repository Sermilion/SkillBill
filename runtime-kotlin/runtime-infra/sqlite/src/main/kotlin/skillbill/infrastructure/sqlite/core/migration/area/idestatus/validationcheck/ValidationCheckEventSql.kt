package skillbill.infrastructure.sqlite.core.migration.area.idestatus.validationcheck

import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.idestatus.model.LiveChecksAvailability
import skillbill.ports.idestatus.model.LiveChecksWriteMode
import skillbill.ports.idestatus.model.StandaloneNarrowWriteResult
import skillbill.ports.idestatus.model.StandaloneValidationOutcome
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import skillbill.ports.idestatus.model.ValidationCheckOutcome
import skillbill.workflow.time.parsePersistedInstantOrNull
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant

private const val EVENT_SOURCE = "validation-check-events"

private const val LIVE_ROW_FILTER =
  "WHERE invocation_id = ? AND finished_at IS NULL AND lifecycle_state IN ('active', 'paused')"

internal fun Connection.insertValidationCheckEvent(record: ValidationCheckEventRecord) {
  prepareStatement(
    "INSERT INTO $VALIDATION_CHECK_EVENTS_TABLE (invocation_id, sequence, check_id, event, name, command, " +
      "working_dir, outcome, exit_code, note, attempt, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
  ).use { statement ->
    statement.bindAll(
      record.invocationId,
      record.sequence,
      record.checkId,
      record.event.wireValue,
      record.name,
      record.command,
      record.workingDir,
      record.outcome?.wireValue,
      record.exitCode,
      record.note,
      record.attempt,
      record.recordedAt.toString(),
    )
    statement.executeUpdate()
  }
}

internal fun Connection.readValidationCheckEvents(invocationId: String): List<ValidationCheckEventRecord> =
  prepareStatement(
    "SELECT * FROM $VALIDATION_CHECK_EVENTS_TABLE WHERE invocation_id = ? ORDER BY sequence ASC",
  ).use { statement ->
    statement.setString(1, invocationId)
    statement.executeQuery().use { rows ->
      buildList { while (rows.next()) add(rows.toValidationCheckEvent()) }
    }
  }

private fun ResultSet.toValidationCheckEvent(): ValidationCheckEventRecord =
  ValidationCheckEventRecord(
    invocationId = getString("invocation_id"),
    sequence = getInt("sequence"),
    checkId = getString("check_id"),
    event = readCheckEvent(),
    name = getString("name"),
    command = getString("command"),
    workingDir = getString("working_dir"),
    outcome = readCheckOutcome(),
    exitCode = getInt("exit_code").takeIf { !wasNull() },
    note = getString("note"),
    attempt = getInt("attempt"),
    recordedAt = readRecordedAt(),
  )

private fun ResultSet.readCheckEvent(): ValidationCheckEvent {
  val raw = getString("event")
  return ValidationCheckEvent.fromWire(raw)
    ?: throw invalidIdeStatusSchemaError(EVENT_SOURCE, "event", "Unknown check event '$raw'.")
}

private fun ResultSet.readCheckOutcome(): ValidationCheckOutcome? {
  val raw = getString("outcome") ?: return null
  return ValidationCheckOutcome.fromWire(raw)
    ?: throw invalidIdeStatusSchemaError(EVENT_SOURCE, "outcome", "Unknown check outcome '$raw'.")
}

private fun ResultSet.readRecordedAt(): Instant {
  val raw = getString("recorded_at")
  return parsePersistedInstantOrNull(raw.orEmpty())
    ?: throw invalidIdeStatusSchemaError(EVENT_SOURCE, "recorded_at", "Invalid time '$raw'.")
}

internal fun Connection.writeStandaloneCurrentActivity(
  invocationId: String,
  activity: String,
): StandaloneNarrowWriteResult =
  narrowWrite(invocationId, "UPDATE standalone_phase_status SET current_activity = ? $LIVE_ROW_FILTER") {
    it.setString(1, activity)
    it.setString(2, invocationId)
  }

internal fun Connection.writeStandaloneLiveChecks(
  invocationId: String,
  mode: LiveChecksWriteMode,
): StandaloneNarrowWriteResult {
  val unavailable = "'${LiveChecksAvailability.UNAVAILABLE.wireValue}'"
  val value =
    when (mode) {
      LiveChecksWriteMode.UNAVAILABLE -> unavailable
      LiveChecksWriteMode.KEEP_OR_UNAVAILABLE -> "COALESCE($LIVE_CHECKS_COLUMN, $unavailable)"
      LiveChecksWriteMode.AVAILABLE -> "'${LiveChecksAvailability.AVAILABLE.wireValue}'"
    }
  return narrowWrite(invocationId, "UPDATE standalone_phase_status SET $LIVE_CHECKS_COLUMN = $value $LIVE_ROW_FILTER") {
    it.setString(1, invocationId)
  }
}

private fun Connection.narrowWrite(
  invocationId: String,
  sql: String,
  bind: (PreparedStatement) -> Unit,
): StandaloneNarrowWriteResult {
  val updated =
    prepareStatement(sql).use { statement ->
      bind(statement)
      statement.executeUpdate()
    }
  if (updated > 0) return StandaloneNarrowWriteResult.WRITTEN
  val exists =
    prepareStatement("SELECT 1 FROM standalone_phase_status WHERE invocation_id = ?").use { statement ->
      statement.setString(1, invocationId)
      statement.executeQuery().use { rows -> rows.next() }
    }
  return if (exists) StandaloneNarrowWriteResult.FINISHED else StandaloneNarrowWriteResult.MISSING
}

internal fun ResultSet.readLiveChecks(sourceLabel: String): LiveChecksAvailability? {
  val raw = getString(LIVE_CHECKS_COLUMN) ?: return null
  return LiveChecksAvailability.fromWire(raw)
    ?: throw invalidIdeStatusSchemaError(sourceLabel, LIVE_CHECKS_COLUMN, "Unknown live checks value '$raw'.")
}

internal fun ResultSet.readValidationOutcome(sourceLabel: String): StandaloneValidationOutcome? {
  val raw = getString(VALIDATION_OUTCOME_COLUMN) ?: return null
  return StandaloneValidationOutcome.fromWire(raw)
    ?: throw invalidIdeStatusSchemaError(sourceLabel, VALIDATION_OUTCOME_COLUMN, "Unknown validation outcome '$raw'.")
}
