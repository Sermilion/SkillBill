package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionAuditResultMode
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

fun FeatureTaskRuntimeWorkflowArtifactMap.isPreviousVersionAcceptanceCriterionRepairState(): Boolean =
  isObject && this[Keys.CONTRACT_VERSION] == ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION

fun FeatureTaskRuntimeWorkflowArtifactMap.isPreviousVersionAcceptanceCriterionAuditResult(): Boolean =
  isObject && this[Keys.CONTRACT_VERSION] == ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION

fun FeatureTaskRuntimeWorkflowArtifactMap.upgradeRepairStateFromPrevious(): FeatureTaskRuntimeWorkflowArtifactMap? {
  val tracePending = this[Keys.STAGE] == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED.wireValue
  if (!isPreviousVersionAcceptanceCriterionRepairState() || containsKey(Keys.INTERACTION_TRACE) || tracePending) {
    return null
  }
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    this +
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION,
        Keys.INTERACTION_TRACE to emptyInteractionTraceWire(),
      ),
  )
}

fun FeatureTaskRuntimeWorkflowArtifactMap.upgradeAuditResultFromPrevious(): FeatureTaskRuntimeWorkflowArtifactMap? {
  val carriesCurrentOnlyFacts =
    containsKey(Keys.INTERACTIONS) || containsKey(Keys.RECHECKS) ||
      this[Keys.MODE] == AcceptanceCriterionAuditResultMode.INTERACTION_TRACE.wireValue
  if (!isPreviousVersionAcceptanceCriterionAuditResult() || carriesCurrentOnlyFacts) return null
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    this +
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
        Keys.INTERACTIONS to emptyList<Any?>(),
        Keys.RECHECKS to emptyList<Any?>(),
      ),
  )
}
