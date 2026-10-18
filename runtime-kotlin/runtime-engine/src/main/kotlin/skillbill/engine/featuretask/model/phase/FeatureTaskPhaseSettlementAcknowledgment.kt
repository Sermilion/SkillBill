package skillbill.engine.featuretask.model.phase

import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

enum class FeatureTaskPhaseSettlementAckStatus(val wireValue: String) {
  OK("ok"),
  ERROR("error"),
}

data class FeatureTaskPhaseSettlementAcknowledgment(
  val status: FeatureTaskPhaseSettlementAckStatus,
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val kind: FeatureTaskPhaseSettlementKind,
  val envelope: FeatureTaskRuntimeWorkflowArtifactMap?,
  val diagnosticCode: String? = null,
)

data class FeatureTaskPhaseSettlementEnvelope(
  val envelope: FeatureTaskRuntimeWorkflowArtifactMap,
)
