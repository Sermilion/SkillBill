package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.block.BlockIncidentLiveReferenceResolution
import skillbill.engine.diagnostics.block.BlockIncidentLiveReferenceResolver
import skillbill.engine.work.recovery.BlockIncidentProjectionAssessment
import skillbill.engine.work.recovery.BlockIncidentRecoveryService
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.WorkIncidentEvidence
import skillbill.ports.idestatus.model.WorkIncidentPrerequisite
import skillbill.ports.idestatus.model.WorkIncidentProgress
import skillbill.ports.idestatus.model.WorkIncidentProjection
import skillbill.ports.idestatus.model.WorkIncidentRecoveryProjection
import skillbill.ports.idestatus.model.WorkIncidentReportProjection
import skillbill.ports.idestatus.model.WorkIncidentSet
import skillbill.ports.idestatus.model.WorkIncidentTelemetryProjection
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCertainty
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibilityResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReport
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus

private val RECOVERING_ATTEMPTS =
  setOf(
    BlockIncidentRecoveryAttemptStatus.REQUESTED,
    BlockIncidentRecoveryAttemptStatus.APPLYING,
    BlockIncidentRecoveryAttemptStatus.APPLIED,
    BlockIncidentRecoveryAttemptStatus.RESUMING,
  )

@Inject
public class WorkIncidentProjectionBuilder(
  private val recovery: BlockIncidentRecoveryService,
  private val telemetry: BlockIncidentTelemetryPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun build(
    unit: UnitOfWork,
    workflowId: String?,
    invocationId: String?,
    blocked: Boolean,
  ): WorkIncidentSet {
    val live = BlockIncidentLiveReferenceResolver.resolve(unit, workflowId, invocationId, blocked)
    val incidents = unit.blockIncidents.listForWork(workflowId, invocationId, HISTORY_LIMIT + 1)
    val currentIncident =
      when (live) {
        is BlockIncidentLiveReferenceResolution.Verified ->
          unit.blockIncidents.find(live.incidentToken)
            ?: run {
              diagnostics.warning(
                "Work incident projection missing accepted record; work=${workflowId ?: invocationId}; " +
                  "token=${live.incidentToken}; reason=accepted_record_not_found",
              )
              null
            }
        is BlockIncidentLiveReferenceResolution.None -> null
        is BlockIncidentLiveReferenceResolution.Unavailable -> {
          diagnostics.warning(
            "Work incident projection identity unavailable; work=${workflowId ?: invocationId}; " +
              "reason=${live.reason}",
          )
          null
        }
      }
    val current =
      currentIncident
        ?.takeIf {
          unit.blockIncidentRecoveries.latest(it.occurrence.occurrenceToken)?.status !=
            BlockIncidentRecoveryAttemptStatus.RESUMED
        }
        ?.let { project(unit, it, assessmentKind = "current") }
    val history =
      (incidents + currentIncident?.let { listOf(it) }.orEmpty())
        .distinctBy { it.occurrence.occurrenceToken }
        .filterNot { it.occurrence.occurrenceToken == current?.incidentToken }
        .sortedWith(
          compareByDescending<BlockIncident> { it.occurrence.observedAt }
            .thenByDescending { it.occurrence.occurrenceToken },
        )
        .take(HISTORY_LIMIT)
        .map { project(unit, it, assessmentKind = "history") }
    return WorkIncidentSet(current, history)
  }

  private fun project(
    unit: UnitOfWork,
    incident: BlockIncident,
    assessmentKind: String,
  ): WorkIncidentProjection {
    val diagnosis = unit.blockIncidents.diagnoses(incident.occurrence.occurrenceToken).lastOrNull()
    val attempt = unit.blockIncidentRecoveries.latest(incident.occurrence.occurrenceToken)
    val assessment = recovery.eligibilityForProjection(unit, incident)
    val eligibility = (assessment as? BlockIncidentProjectionAssessment.Evaluated)?.eligibility
    recordAssessment(incident, assessmentKind, assessment)
    val report = reportProjection(unit, incident)
    val prerequisites =
      (eligibility as? BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites)
        ?.instructions?.map { WorkIncidentPrerequisite(it.kind.wireValue, it.instruction) }.orEmpty()
    val progress = deriveWorkIncidentProgress(incident.state.diagnosis, attempt?.status, eligibility)
    val noWorkaround = diagnosis?.noWorkaroundKnown ?: false
    val availability = telemetry.availability(incident.occurrence.occurrenceToken)
    val telemetryProjection =
      WorkIncidentTelemetryProjection(
        availability.state.wireValue,
        availability.installId,
        availability.incidentToken,
        availability.workflowId,
        availability.invocationId,
      )
    val preview = (assessment as? BlockIncidentProjectionAssessment.Evaluated)?.preview
    return WorkIncidentProjection(
      incidentToken = incident.occurrence.occurrenceToken,
      revision = incident.state.revision,
      progress = progress,
      summary = diagnosis?.summary ?: incident.occurrence.reason,
      cause = diagnosis?.cause,
      certainty = diagnosis?.certainty ?: BlockIncidentCertainty.UNKNOWN,
      responsibleCategory = diagnosis?.responsibleCategory ?: BlockIncidentResponsibleCategory.UNRESOLVED,
      nextAction = nextAction(incident, eligibility, noWorkaround, report, diagnosis),
      noWorkaroundKnown = noWorkaround,
      prerequisites = prerequisites,
      evidence =
        diagnosis?.evidence.orEmpty().map {
          WorkIncidentEvidence(it.kind, it.reference, it.summary, it.trusted)
        },
      degradations = diagnosis?.degradations.orEmpty(),
      recovery = recoveryProjection(eligibility, preview, diagnosis, attempt),
      report = report,
      telemetry = telemetryProjection,
      observedAt = incident.occurrence.observedAt,
      settledAt = diagnosis?.completedAt,
    )
  }

  private fun recordAssessment(
    incident: BlockIncident,
    assessmentKind: String,
    assessment: BlockIncidentProjectionAssessment,
  ) {
    when (assessment) {
      is BlockIncidentProjectionAssessment.TargetUnavailable ->
        diagnostics.warning(
          "Work incident recovery assessment unavailable; seam=work_detail_projection; " +
            "work=${incident.occurrence.workflowId ?: incident.occurrence.invocationId}; " +
            "incident=${incident.occurrence.occurrenceToken}; kind=$assessmentKind; " +
            "expected=target; reason=${assessment.failure}",
        )
      is BlockIncidentProjectionAssessment.FactsUnavailable ->
        diagnostics.warning(
          "Work incident recovery assessment unavailable; seam=work_detail_projection; " +
            "work=${incident.occurrence.workflowId ?: incident.occurrence.invocationId}; " +
            "incident=${incident.occurrence.occurrenceToken}; kind=$assessmentKind; expected=policy_facts; " +
            "reason=${assessment.reason.take(MAX_DIAGNOSTIC_REASON_CHARS)}",
        )
      BlockIncidentProjectionAssessment.NotDiagnosed, is BlockIncidentProjectionAssessment.Evaluated -> Unit
    }
  }

  private fun reportProjection(
    unit: UnitOfWork,
    incident: BlockIncident,
  ): WorkIncidentReportProjection {
    val reportRecord = unit.productReports.forIncident(incident.occurrence.occurrenceToken)
    return reportRecord?.let { WorkIncidentReportProjection(it.status, it.failure, it.issue?.url) }
      ?: WorkIncidentReportProjection(
        status =
          when (incident.state.report) {
            BlockIncidentReport.DELIVERED -> ProductReportStatus.DELIVERED
            BlockIncidentReport.PENDING -> ProductReportStatus.PENDING
            BlockIncidentReport.FAILED -> ProductReportStatus.FAILED
            else -> ProductReportStatus.LOCAL_ONLY
          },
        failureReason = null,
        issueUrl = null,
      )
  }

  private fun nextAction(
    incident: BlockIncident,
    eligibility: BlockIncidentRecoveryEligibilityResult?,
    noWorkaround: Boolean,
    report: WorkIncidentReportProjection,
    diagnosis: BlockIncidentDiagnosisRecord?,
  ): String =
    when {
      eligibility is BlockIncidentRecoveryEligibilityResult.Available -> eligibility.preview.label
      eligibility is BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites ->
        eligibility.instructions.firstOrNull()?.instruction.orEmpty()
      noWorkaround && report.status in setOf(ProductReportStatus.DELIVERED, ProductReportStatus.LINKED_EXISTING) ->
        "No known workaround. The block has been reported."
      noWorkaround -> "No known workaround."
      incident.state.diagnosis == BlockIncidentDiagnosis.PENDING ||
        incident.state.diagnosis == BlockIncidentDiagnosis.INVESTIGATING ->
        "Waiting for investigation."
      else -> diagnosis?.whatHappened ?: incident.occurrence.reason
    }

  private fun recoveryProjection(
    eligibility: BlockIncidentRecoveryEligibilityResult?,
    preview: BlockIncidentRecoveryPreview?,
    diagnosis: BlockIncidentDiagnosisRecord?,
    attempt: BlockIncidentRecoveryAttempt?,
  ) = WorkIncidentRecoveryProjection(
    eligibility = eligibility,
    previewLabel = preview?.label,
    effects = preview?.effects ?: diagnosis?.recoveryEffects,
    targetWorkflowId = preview?.targetWorkflowId,
    targetInvocationId = preview?.targetInvocationId,
    latestAttemptId = attempt?.attemptId,
    latestAttemptStatus = attempt?.status,
    failure = attempt?.failure,
  )

  private companion object {
    const val HISTORY_LIMIT = 10
    const val MAX_DIAGNOSTIC_REASON_CHARS = 160
  }
}

internal fun deriveWorkIncidentProgress(
  diagnosis: BlockIncidentDiagnosis,
  attempt: BlockIncidentRecoveryAttemptStatus?,
  eligibility: BlockIncidentRecoveryEligibilityResult?,
): WorkIncidentProgress =
  when {
    attempt == BlockIncidentRecoveryAttemptStatus.RESUMED -> WorkIncidentProgress.RESUMED
    attempt != null && attempt in RECOVERING_ATTEMPTS -> WorkIncidentProgress.RECOVERING
    attempt == BlockIncidentRecoveryAttemptStatus.FAILED -> WorkIncidentProgress.RECOVERY_FAILED
    eligibility is BlockIncidentRecoveryEligibilityResult.Available -> WorkIncidentProgress.RECOVERY_AVAILABLE
    eligibility is BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites -> WorkIncidentProgress.NEEDS_PREREQUISITES
    diagnosis == BlockIncidentDiagnosis.PENDING -> WorkIncidentProgress.INVESTIGATION_PENDING
    diagnosis == BlockIncidentDiagnosis.INVESTIGATING -> WorkIncidentProgress.INVESTIGATING
    diagnosis == BlockIncidentDiagnosis.COMPLETE -> WorkIncidentProgress.DIAGNOSIS_COMPLETE
    diagnosis == BlockIncidentDiagnosis.PARTIAL || diagnosis == BlockIncidentDiagnosis.UNAVAILABLE_AGENT ->
      WorkIncidentProgress.DIAGNOSIS_PARTIAL
    else -> WorkIncidentProgress.DIAGNOSIS_FAILED
  }
