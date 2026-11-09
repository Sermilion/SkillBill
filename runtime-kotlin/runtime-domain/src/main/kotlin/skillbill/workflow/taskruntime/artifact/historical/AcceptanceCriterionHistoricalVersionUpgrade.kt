package skillbill.workflow.taskruntime.artifact.historical

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
import skillbill.workflow.taskruntime.artifact.emptyInteractionTraceWire
import skillbill.workflow.taskruntime.artifact.isPreviousVersionAcceptanceCriterionAuditResult
import skillbill.workflow.taskruntime.artifact.isPreviousVersionAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

fun FeatureTaskRuntimeWorkflowArtifactMap.upgradeRepairStateFromPrevious(): FeatureTaskRuntimeWorkflowArtifactMap? {
  if (!isPreviousVersionAcceptanceCriterionRepairState()) return null
  toHistoricalRepairState("repair-state-original-upgrade")
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    this +
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION,
        Keys.INTERACTION_TRACE to emptyInteractionTraceWire(),
      ),
  ).also { it.toHistoricalRepairState("repair-state-interaction-upgrade") }
}

fun FeatureTaskRuntimeWorkflowArtifactMap.upgradeAuditResultFromPrevious(): FeatureTaskRuntimeWorkflowArtifactMap? {
  if (!isPreviousVersionAcceptanceCriterionAuditResult()) return null
  toHistoricalAuditResult("audit-result-original-upgrade")
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    this +
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION,
        Keys.INTERACTIONS to emptyList<Any?>(),
        Keys.RECHECKS to emptyList<Any?>(),
      ),
  ).also { it.toHistoricalAuditResult("audit-result-interaction-upgrade") }
}
