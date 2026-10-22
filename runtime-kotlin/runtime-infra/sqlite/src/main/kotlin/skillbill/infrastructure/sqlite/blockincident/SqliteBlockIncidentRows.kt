package skillbill.infrastructure.sqlite.blockincident

import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReport
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentState
import java.sql.ResultSet
import java.time.Instant
import java.time.format.DateTimeParseException

private val LEASE_COLUMNS = listOf("owner_generation", "host_identity", "process_id", "lease_expires_at")

internal fun ResultSet.storedClaim(): BlockIncidentClaim? {
  val owner = getString("owner_token")
  if (owner == null) {
    if (LEASE_COLUMNS.any { column -> getObject(column) != null }) {
      throw incidentCorrupt("Block incident has lease fields without an owner token.")
    }
    return null
  }
  return BlockIncidentClaim(
    ownerToken = owner,
    ownerGeneration = requiredLong("owner_generation"),
    hostIdentity = requiredText("host_identity"),
    processId = requiredLong("process_id"),
    expiresAt = requiredText("lease_expires_at").toInstant("lease_expires_at"),
  )
}

internal fun ResultSet.storedState(): BlockIncidentState =
  BlockIncidentState(
    diagnosis = requiredText("diagnosis").decodeEnum(BlockIncidentDiagnosis.entries) { it.wireValue },
    recovery = requiredText("recovery").decodeEnum(BlockIncidentRecovery.entries) { it.wireValue },
    report = requiredText("report").decodeEnum(BlockIncidentReport.entries) { it.wireValue },
    revision = requiredLong("revision"),
    deadline = requiredText("deadline").toInstant("deadline"),
    retryCount = requiredInt("retry_count"),
    nextEligibleAt = getString("next_eligible_at")?.toInstant("next_eligible_at"),
    claim = storedClaim(),
  )

internal fun ResultSet.columnOccurrence(token: String): BlockIncidentOccurrence =
  BlockIncidentOccurrence(
    occurrenceToken = token,
    scope =
      BlockIncidentScope(
        workflowId = getString("workflow_id"),
        parentWorkflowId = getString("parent_workflow_id"),
        phaseId = getString("phase_id"),
        phaseRunId = getString("phase_run_id"),
        invocationId = getString("invocation_id"),
        executionId = getString("execution_id"),
        attempt = getInt("attempt").takeUnless { wasNull() },
        generation = getLong("generation").takeUnless { wasNull() },
      ),
    failure =
      BlockIncidentFailure(
        origin = requiredText("origin").decodeEnum(BlockIncidentOrigin.entries) { it.wireValue },
        reason = requiredText("reason"),
        reasonRetention = BlockIncidentReasonRetention.UNKNOWN_HISTORICAL,
        failureCode = getString("failure_code"),
        failureDisposition = getString("failure_disposition"),
        throwableType = getString("throwable_type"),
        exitCode = getInt("exit_code").takeUnless { wasNull() },
        exitBehavior = requiredText("exit_behavior").decodeEnum(BlockIncidentExitBehavior.entries) { it.wireValue },
      ),
    observedAt = requiredText("observed_at").toInstant("observed_at"),
    evidenceAvailability =
      requiredText("evidence_availability").decodeEnum(BlockIncidentEvidenceAvailability.entries) {
        it.wireValue
      },
    identityAvailability =
      requiredText("identity_availability").decodeEnum(BlockIncidentIdentityAvailability.entries) {
        it.wireValue
      },
    fieldAvailability = SqliteBlockIncidentOccurrenceCodec.HISTORICAL_FIELD_AVAILABILITY,
  )

internal fun <T> String.decodeEnum(
  values: List<T>,
  wireValue: (T) -> String,
): T = values.firstOrNull { wireValue(it) == this } ?: throw incidentCorrupt("Unsupported block incident value.")

internal fun ResultSet.requiredText(column: String): String =
  getString(column) ?: throw incidentCorrupt("Block incident $column is absent.")

internal fun ResultSet.requiredLong(column: String): Long =
  getLong(column).takeUnless { wasNull() } ?: throw incidentCorrupt("Block incident $column is absent.")

internal fun ResultSet.requiredInt(column: String): Int =
  getInt(column).takeUnless { wasNull() } ?: throw incidentCorrupt("Block incident $column is absent.")

internal fun String.toInstant(field: String): Instant =
  try {
    Instant.parse(this)
  } catch (failure: DateTimeParseException) {
    throw incidentCorrupt("Invalid block incident $field.", failure)
  }

internal fun incidentCorrupt(
  message: String,
  cause: Throwable? = null,
) = SkillBillRuntimeException(BlockIncidentFailureCode.INVALID_SCHEMA, message, cause)
