package skillbill.cli.kernel.work

import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkIncidentProgress
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure

internal fun incidentTextLines(
  detail: WorkDetailItem,
  verbose: Boolean,
): List<String> {
  val projection = detail.incident ?: return emptyList()
  return buildList {
    add(projection.progress.statusLabel())
    add("Revision: ${projection.revision}")
    add("Cause: ${projection.cause ?: "Unknown"} (${projection.certainty.wireValue.lowercase()})")
    add("Next: ${projection.nextAction}")
    recoverCommand(detail.recoveryWorkId, projection)?.let { add("Recover: $it") }
    val reportDetail = projection.report.issueUrl ?: projection.report.failureReason?.displayText()
    add("Report: ${projection.report.status.wireValue}${reportDetail?.let { " $it" }.orEmpty()}")
    val identifiers =
      listOfNotNull(
        projection.telemetry.installId?.let { "install_id=$it" },
        "incident_token=${projection.telemetry.incidentToken}",
        projection.telemetry.workflowId?.let { "workflow_id=$it" },
        projection.telemetry.invocationId?.let { "invocation_id=$it" },
      )
    add("Telemetry: ${projection.telemetry.availability} ${identifiers.joinToString(" ")}")
    if (verbose) projection.evidence.forEach { add("Evidence: ${it.referenceId} ${it.summary}") }
  }
}

private fun ProductReportFailure.displayText(): String =
  when (this) {
    ProductReportFailure.MISSING_AUTH -> "Not submitted: GitHub credentials not found"
    ProductReportFailure.OFFLINE -> "Not submitted: network unavailable"
    ProductReportFailure.PERMISSION_REFUSED -> "Not submitted: GitHub permission refused"
    ProductReportFailure.RATE_LIMITED -> "Not submitted: GitHub rate limited the request"
    ProductReportFailure.SUBMISSION_FAILED -> "Not submitted: issue creation failed"
    ProductReportFailure.RESIDUAL_SECRET -> "Not submitted: report contains a secret"
    ProductReportFailure.RETRIES_EXHAUSTED -> "Not submitted: retries exhausted"
  }

private fun WorkIncidentProgress.statusLabel(): String =
  when (this) {
    WorkIncidentProgress.INVESTIGATING -> "Investigating block"
    WorkIncidentProgress.INVESTIGATION_PENDING -> "Investigation queued"
    WorkIncidentProgress.DIAGNOSIS_COMPLETE -> "Diagnosis ready"
    WorkIncidentProgress.DIAGNOSIS_PARTIAL -> "Partial diagnosis"
    WorkIncidentProgress.DIAGNOSIS_FAILED -> "Diagnosis unavailable"
    WorkIncidentProgress.NEEDS_PREREQUISITES -> "Needs your action"
    WorkIncidentProgress.RECOVERY_AVAILABLE -> "Recovery available"
    WorkIncidentProgress.RECOVERING -> "Recovering"
    WorkIncidentProgress.RECOVERY_FAILED -> "Recovery failed"
    WorkIncidentProgress.RESUMED -> "Resumed"
  }
