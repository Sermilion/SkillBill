package skillbill.application.telemetry.service

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.blockincident.model.BlockIncidentTelemetryAvailability
import skillbill.ports.blockincident.model.BlockIncidentTelemetryState
import skillbill.ports.blockincident.model.ProductReportRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentReportKeys as Keys

@Inject
class BlockIncidentTelemetry(
  private val database: DatabaseSessionFactory,
  private val settings: TelemetrySettingsProvider,
  private val diagnostics: RuntimeDiagnostics,
) : BlockIncidentTelemetryPort {
  override fun diagnosed(
    incident: BlockIncidentOccurrence,
    status: BlockIncidentDiagnosis,
    diagnosis: BlockIncidentDiagnosisRecord?,
  ) {
    emit(TelemetryOutboxEvent.BLOCK_INCIDENT_DIAGNOSED, incident) { level ->
      put(Keys.DIAGNOSIS_STATUS, status.wireValue)
      incident.failureCode?.let { put(Keys.FAILURE_CODE, it) }
      diagnosis?.let {
        put(Keys.DIAGNOSIS_REVISION, it.revision)
        put(Keys.RESPONSIBLE_CATEGORY, it.responsibleCategory.wireValue)
        put(Keys.CERTAINTY, it.certainty.wireValue)
      }
      if (level == FULL_LEVEL) putReport(linkedReport(incident.occurrenceToken), level)
    }
  }

  override fun recovery(
    incident: BlockIncidentOccurrence,
    attempt: BlockIncidentRecoveryAttempt,
  ) {
    emit(TelemetryOutboxEvent.BLOCK_INCIDENT_RECOVERY, incident) { level ->
      val context = context(incident.occurrenceToken, includeReport = level == FULL_LEVEL)
      context.diagnosis?.let { putDiagnosis(it) }
      put(Keys.RECOVERY_ATTEMPT_ID, attempt.attemptId)
      put(Keys.RECOVERY_STATUS, attempt.status.wireValue)
      attempt.failure?.let { put(Keys.FAILURE_CODE, it.wireValue) }
      putReport(context.report, level)
    }
  }

  override fun reported(
    incident: BlockIncidentOccurrence,
    report: ProductReportRecord,
  ) {
    emit(TelemetryOutboxEvent.BLOCK_INCIDENT_REPORTED, incident) { level ->
      val context = context(incident.occurrenceToken, includeReport = level == FULL_LEVEL)
      context.diagnosis?.let { putDiagnosis(it) }
      put(Keys.REPORT_STATUS, report.status.wireValue)
      report.failure?.let { put(Keys.FAILURE_CODE, it.wireValue) }
      putReport(context.report?.takeIf { it.reportId == report.reportId } ?: report, level)
    }
  }

  override fun availability(incidentToken: String): BlockIncidentTelemetryAvailability {
    val loaded = loadSettings()
    val setting = (loaded as? TelemetrySettingsLoad.Loaded)?.settings
    if (setting?.level == OFF_LEVEL) {
      val occurrence =
        catchingNonCancellation {
          database.read { unit -> unit.blockIncidents.find(incidentToken)?.occurrence }
        }.getOrElse { error ->
          diagnostics.warning("Block incident telemetry identifiers are unreadable while disabled.", error)
          null
        }
      return BlockIncidentTelemetryAvailability(
        BlockIncidentTelemetryState.DISABLED,
        setting.installId,
        incidentToken,
        occurrence?.workflowId,
        occurrence?.invocationId,
      )
    }
    if (setting == null) {
      return BlockIncidentTelemetryAvailability(
        BlockIncidentTelemetryState.UNAVAILABLE,
        null,
        incidentToken,
        null,
        null,
      )
    }
    val occurrence =
      catchingNonCancellation {
        database.read { unit -> unit.blockIncidents.find(incidentToken)?.occurrence }
      }.getOrElse { error ->
        diagnostics.warning("Block incident telemetry identifiers are unreadable.", error)
        null
      }
    return catchingNonCancellation {
      val latest = database.read { unit -> unit.telemetryOutbox.latestByCorrelation(REPORT_EVENTS, incidentToken) }
      val state =
        when {
          latest == null -> BlockIncidentTelemetryState.UNAVAILABLE
          !latest.lastError.isNullOrBlank() -> BlockIncidentTelemetryState.FAILED
          latest.synced -> BlockIncidentTelemetryState.SYNCED
          else -> BlockIncidentTelemetryState.PENDING
        }
      BlockIncidentTelemetryAvailability(
        state,
        setting.installId,
        incidentToken,
        occurrence?.workflowId,
        occurrence?.invocationId,
      )
    }.getOrElse { error ->
      diagnostics.warning("Block incident telemetry availability is unreadable.", error)
      BlockIncidentTelemetryAvailability(
        BlockIncidentTelemetryState.UNAVAILABLE,
        setting.installId,
        incidentToken,
        occurrence?.workflowId,
        occurrence?.invocationId,
      )
    }
  }

  private fun loadSettings(): TelemetrySettingsLoad =
    catchingNonCancellation {
      settings.loadOrUnavailable()
    }.getOrElse { error ->
      diagnostics.warning("Block incident telemetry settings are unavailable.", error)
      TelemetrySettingsLoad.Unavailable(error.javaClass.simpleName)
    }

  private fun emit(
    event: TelemetryOutboxEvent,
    incident: BlockIncidentOccurrence,
    fields: MutableMap<String, Any?>.(String) -> Unit,
  ) {
    catchingNonCancellation {
      val level =
        settings.loadOrUnavailable().let { result ->
          (result as? TelemetrySettingsLoad.Loaded)?.settings?.level
            ?: run {
              diagnostics.warning("Block incident telemetry settings are unavailable; event was skipped.")
              return
            }
        }
      if (level == OFF_LEVEL) return
      val payload =
        buildMap<String, Any?> {
          put(Keys.INCIDENT_TOKEN, incident.occurrenceToken)
          incident.workflowId?.let { put(Keys.WORKFLOW_ID, it) }
          incident.parentWorkflowId?.let { put(Keys.PARENT_WORKFLOW_ID, it) }
          incident.phaseId?.let { put(Keys.PHASE_ID, it) }
          incident.invocationId?.let { put(Keys.INVOCATION_ID, it) }
          incident.attempt?.let { put(Keys.ATTEMPT, it) }
          fields(level)
        }
      database.transaction { unit -> unit.telemetryOutbox.enqueue(event, JsonCodec.mapToJsonString(payload)) }
    }.getOrElse { error ->
      diagnostics.warning("Block incident telemetry could not be enqueued.", error)
    }
  }

  private fun context(
    incidentToken: String,
    includeReport: Boolean,
  ): TelemetryContext =
    database.read { unit ->
      TelemetryContext(
        unit.blockIncidents.diagnoses(incidentToken).lastOrNull(),
        if (includeReport) unit.productReports.forIncident(incidentToken) else null,
      )
    }

  private fun linkedReport(incidentToken: String): ProductReportRecord? =
    database.read { unit -> unit.productReports.forIncident(incidentToken) }

  private fun MutableMap<String, Any?>.putDiagnosis(diagnosis: BlockIncidentDiagnosisRecord) {
    put(Keys.DIAGNOSIS_REVISION, diagnosis.revision)
    put(Keys.RESPONSIBLE_CATEGORY, diagnosis.responsibleCategory.wireValue)
    put(Keys.CERTAINTY, diagnosis.certainty.wireValue)
  }

  private fun MutableMap<String, Any?>.putReport(
    report: ProductReportRecord?,
    level: String,
  ) {
    if (level != FULL_LEVEL || report == null) return
    put(Keys.REPORT_ID, report.reportId)
    report.issue?.let { put(Keys.ISSUE_URL, it.url) }
  }

  private data class TelemetryContext(
    val diagnosis: BlockIncidentDiagnosisRecord?,
    val report: ProductReportRecord?,
  )

  private companion object {
    const val OFF_LEVEL = "off"
    const val FULL_LEVEL = "full"
    val REPORT_EVENTS =
      setOf(
        TelemetryOutboxEvent.BLOCK_INCIDENT_DIAGNOSED,
        TelemetryOutboxEvent.BLOCK_INCIDENT_RECOVERY,
        TelemetryOutboxEvent.BLOCK_INCIDENT_REPORTED,
      )
  }
}
