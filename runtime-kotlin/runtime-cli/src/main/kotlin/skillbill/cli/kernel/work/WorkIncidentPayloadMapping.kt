package skillbill.cli.kernel.work

import skillbill.ports.idestatus.model.WorkIncidentProgress
import skillbill.ports.idestatus.model.WorkIncidentProjection
import skillbill.ports.idestatus.model.WorkIncidentRecoveryProjection
import skillbill.ports.idestatus.model.WorkIncidentReportProjection
import skillbill.ports.idestatus.model.WorkIncidentTelemetryProjection
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibilityResult
import skillbill.contracts.work.WorkIncidentWireKeys as Keys

internal fun workIncidentWireMap(
  projection: WorkIncidentProjection,
  workId: String,
): Map<String, Any?> =
  buildMap {
    put(Keys.INCIDENT_TOKEN, projection.incidentToken)
    put(Keys.REVISION, projection.revision)
    put(Keys.PROGRESS, projection.progress.wireValue)
    put(Keys.SUMMARY, projection.summary)
    projection.cause?.let { put(Keys.CAUSE, it) }
    put(Keys.CERTAINTY, projection.certainty.wireValue)
    put(Keys.RESPONSIBLE_CATEGORY, projection.responsibleCategory.wireValue)
    put(Keys.NEXT_ACTION, projection.nextAction)
    put(Keys.NO_WORKAROUND_KNOWN, projection.noWorkaroundKnown)
    put(
      Keys.PREREQUISITES,
      projection.prerequisites.map { mapOf(Keys.KIND to it.kind, Keys.INSTRUCTION to it.instruction) },
    )
    put(
      Keys.EVIDENCE,
      projection.evidence.map {
        mapOf(
          Keys.KIND to it.kind.wireValue,
          Keys.REFERENCE_ID to it.referenceId,
          Keys.SUMMARY to it.summary,
          Keys.TRUSTED to it.trusted,
        )
      },
    )
    put(Keys.DEGRADATIONS, projection.degradations.map { it.wireValue })
    put(Keys.RECOVERY, recoveryWireMap(projection.recovery))
    put(Keys.REPORT, reportWireMap(projection.report))
    put(Keys.TELEMETRY, telemetryWireMap(projection.telemetry))
    put(Keys.OBSERVED_AT, projection.observedAt.toString())
    projection.settledAt?.let { put(Keys.SETTLED_AT, it.toString()) }
    recoverCommand(workId, projection)?.let { put(Keys.RECOVER_COMMAND, it) }
  }

internal fun recoverCommand(
  workId: String,
  projection: WorkIncidentProjection,
): String? {
  if (projection.progress != WorkIncidentProgress.RECOVERY_AVAILABLE &&
    projection.progress != WorkIncidentProgress.NEEDS_PREREQUISITES
  ) {
    return null
  }
  return buildString {
    append("skill-bill work recover ").append(workId)
      .append(" --incident ").append(projection.incidentToken)
      .append(" --revision ").append(projection.revision)
    projection.prerequisites.forEach { append(" --confirm-prerequisite ").append(it.kind) }
  }
}

private fun recoveryWireMap(recovery: WorkIncidentRecoveryProjection): Map<String, Any?> =
  buildMap {
    recovery.eligibility?.let { eligibility ->
      put(
        Keys.ELIGIBILITY,
        when (eligibility) {
          is BlockIncidentRecoveryEligibilityResult.Available -> "available"
          is BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites -> "needs_prerequisites"
          is BlockIncidentRecoveryEligibilityResult.Unavailable -> "unavailable"
        },
      )
    }
    recovery.previewLabel?.let { put(Keys.PREVIEW_LABEL, it) }
    recovery.effects?.let { put(Keys.EFFECTS, it) }
    recovery.targetWorkflowId?.let { put(Keys.TARGET_WORKFLOW_ID, it) }
    recovery.targetInvocationId?.let { put(Keys.TARGET_INVOCATION_ID, it) }
    recovery.latestAttemptId?.let { put(Keys.LATEST_ATTEMPT_ID, it) }
    recovery.latestAttemptStatus?.let { put(Keys.LATEST_ATTEMPT_STATUS, it.wireValue) }
    recovery.failure?.let { put(Keys.FAILURE, it.wireValue) }
  }

private fun reportWireMap(report: WorkIncidentReportProjection): Map<String, Any?> =
  buildMap {
    put(Keys.STATUS, report.status.wireValue)
    report.failureReason?.let { put(Keys.FAILURE_REASON, it.wireValue) }
    report.issueUrl?.let { put(Keys.ISSUE_URL, it) }
  }

private fun telemetryWireMap(telemetry: WorkIncidentTelemetryProjection): Map<String, Any?> =
  buildMap {
    put(Keys.AVAILABILITY, telemetry.availability)
    put(
      Keys.SEARCH_IDENTIFIERS,
      buildMap {
        telemetry.installId?.let { put(Keys.INSTALL_ID, it) }
        put(Keys.INCIDENT_TOKEN, telemetry.incidentToken)
        telemetry.workflowId?.let { put(Keys.WORKFLOW_ID, it) }
        telemetry.invocationId?.let { put(Keys.INVOCATION_ID, it) }
      },
    )
  }
