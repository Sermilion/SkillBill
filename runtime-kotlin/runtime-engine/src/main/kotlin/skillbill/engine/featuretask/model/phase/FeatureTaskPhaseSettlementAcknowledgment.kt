package skillbill.engine.featuretask.model.phase

import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

public enum class FeatureTaskPhaseSettlementAckStatus(public val wireValue: String) {
  OK("ok"),
  ERROR("error"),
}

public data class FeatureTaskPhaseSettlementAcknowledgment(
  public val status: FeatureTaskPhaseSettlementAckStatus,
  public val workflowId: String,
  public val phaseId: String,
  public val attempt: Int,
  public val kind: FeatureTaskPhaseSettlementKind,
  public val envelope: FeatureTaskRuntimeWorkflowArtifactMap?,
  public val diagnosticCode: String? = null,
)

public data class FeatureTaskPhaseSettlementEnvelope(
  public val envelope: FeatureTaskRuntimeWorkflowArtifactMap,
)
