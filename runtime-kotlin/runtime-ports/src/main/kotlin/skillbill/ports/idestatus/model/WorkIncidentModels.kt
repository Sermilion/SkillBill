package skillbill.ports.idestatus.model

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCertainty
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDegradation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibilityResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus
import java.time.Instant

enum class WorkIncidentProgress(val wireValue: String) {
  INVESTIGATION_PENDING("investigation_pending"),
  INVESTIGATING("investigating"),
  DIAGNOSIS_COMPLETE("diagnosis_complete"),
  DIAGNOSIS_PARTIAL("diagnosis_partial"),
  DIAGNOSIS_FAILED("diagnosis_failed"),
  NEEDS_PREREQUISITES("needs_prerequisites"),
  RECOVERY_AVAILABLE("recovery_available"),
  RECOVERING("recovering"),
  RECOVERY_FAILED("recovery_failed"),
  RESUMED("resumed"),
}

data class WorkIncidentSet(
  val incident: WorkIncidentProjection?,
  val incidentHistory: List<WorkIncidentProjection>,
)

data class WorkIncidentPrerequisite(val kind: String, val instruction: String)

data class WorkIncidentEvidence(
  val kind: BlockIncidentDiagnosisEvidenceKind,
  val referenceId: String,
  val summary: String,
  val trusted: Boolean,
)

data class WorkIncidentRecoveryProjection(
  val eligibility: BlockIncidentRecoveryEligibilityResult?,
  val previewLabel: String?,
  val effects: String?,
  val targetWorkflowId: String?,
  val targetInvocationId: String?,
  val latestAttemptId: String?,
  val latestAttemptStatus: BlockIncidentRecoveryAttemptStatus?,
  val failure: BlockIncidentRecoveryFailure?,
)

data class WorkIncidentReportProjection(
  val status: ProductReportStatus,
  val failureReason: ProductReportFailure?,
  val issueUrl: String?,
)

data class WorkIncidentTelemetryProjection(
  val availability: String,
  val installId: String?,
  val incidentToken: String,
  val workflowId: String?,
  val invocationId: String?,
)

data class WorkIncidentProjection(
  val incidentToken: String,
  val revision: Long,
  val progress: WorkIncidentProgress,
  val summary: String,
  val cause: String?,
  val certainty: BlockIncidentCertainty,
  val responsibleCategory: BlockIncidentResponsibleCategory,
  val nextAction: String,
  val noWorkaroundKnown: Boolean,
  val prerequisites: List<WorkIncidentPrerequisite>,
  val evidence: List<WorkIncidentEvidence>,
  val degradations: Set<BlockIncidentDegradation>,
  val recovery: WorkIncidentRecoveryProjection,
  val report: WorkIncidentReportProjection,
  val telemetry: WorkIncidentTelemetryProjection,
  val observedAt: Instant,
  val settledAt: Instant?,
)
