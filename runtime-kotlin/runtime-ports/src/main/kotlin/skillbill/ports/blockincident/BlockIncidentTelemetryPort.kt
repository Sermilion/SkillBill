package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.BlockIncidentTelemetryAvailability
import skillbill.ports.blockincident.model.ProductReportRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt

interface BlockIncidentTelemetryPort {
  fun diagnosed(
    incident: BlockIncidentOccurrence,
    status: BlockIncidentDiagnosis,
    diagnosis: BlockIncidentDiagnosisRecord?,
  )

  fun recovery(
    incident: BlockIncidentOccurrence,
    attempt: BlockIncidentRecoveryAttempt,
  )

  fun reported(
    incident: BlockIncidentOccurrence,
    report: ProductReportRecord,
  )

  fun availability(incidentToken: String): BlockIncidentTelemetryAvailability
}
