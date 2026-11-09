package skillbill.workflow.taskruntime.artifact.historical

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeImplementationProgressSchema
import skillbill.workflow.taskruntime.artifact.asImplementationProgressWireMap
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.implementationFields
import skillbill.workflow.taskruntime.artifact.repairWireFields
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.artifact.toImplementationProgress
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionAccountingPolicy
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as RepairKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationProgressPayloadKeys as ImplKeys

fun AcceptanceCriterionRepairState.asEngineRepairWireMap(): FeatureTaskRuntimeWorkflowArtifactMap {
  if (accountingPolicy == AcceptanceCriterionAccountingPolicy.VERIFIED_EVIDENCE) return asRepairWireMap()
  if (AcceptanceCriterionRepairState.restore(this) == null) {
    throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "Incoherent pre-adoption state.")
  }
  val fields = repairWireFields().toMutableMap()
  fields[RepairKeys.CONTRACT_VERSION] = ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
  fields.remove(ProgressKeys.REPAIR_BUDGETS)
  fields.remove(ProgressKeys.REPAIR_ACCOUNTING)
  fields.remove(ProgressKeys.COMPLETED_MUTATIONS)
  fields.remove(ProgressKeys.INDEPENDENT_CHECKS)
  fields[RepairKeys.RESERVATION] = (fields[RepairKeys.RESERVATION] as? Map<*, *>)?.minus(RepairKeys.REASONING_REQUIRED)
  return FeatureTaskRuntimeWorkflowArtifactMap.from(fields).also { it.toHistoricalRepairState("serial-repair") }
}

fun FeatureTaskRuntimeWorkflowArtifactMap.toEngineRepairState(sourceLabel: String): AcceptanceCriterionRepairState {
  if (this[RepairKeys.CONTRACT_VERSION] == ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION) {
    return toAcceptanceCriterionRepairState(sourceLabel)
  }
  if (this[RepairKeys.CONTRACT_VERSION] != ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION) {
    throw invalidAcceptanceCriterionRepairStateSchema(
      sourceLabel,
      "Run the supported historical version recovery first.",
    )
  }
  val historical = toHistoricalRepairState(sourceLabel)
  if (!historical.completedMutations.isNullOrEmpty() || !historical.independentChecks.isNullOrEmpty()) {
    throw invalidAcceptanceCriterionRepairStateSchema(
      sourceLabel,
      "Retained historical evidence requires the deferred evidence migration. Original records must be preserved.",
    )
  }
  return historical.state.copy(accountingPolicy = AcceptanceCriterionAccountingPolicy.PRE_ADOPTION)
}

fun FeatureTaskRuntimeImplementationProgress.asEngineProgressWireMap(): FeatureTaskRuntimeWorkflowArtifactMap {
  if (accountingPolicy == AcceptanceCriterionAccountingPolicy.VERIFIED_EVIDENCE) {
    return asImplementationProgressWireMap()
  }
  val fields = implementationFields().toMutableMap()
  fields[ImplKeys.CONTRACT_VERSION] = FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
  fields.remove(ProgressKeys.REPAIR_BUDGETS)
  fields.remove(ProgressKeys.REPAIR_ACCOUNTING)
  fields.remove(ProgressKeys.WORKER_RESERVATION_ID)
  fields.remove(ProgressKeys.COMPLETED_MUTATIONS)
  fields.remove(ProgressKeys.INDEPENDENT_CHECKS)
  return FeatureTaskRuntimeWorkflowArtifactMap.from(fields).also {
    it.toHistoricalImplementationProgress("implementation-progress")
  }
}

fun FeatureTaskRuntimeWorkflowArtifactMap.toEngineImplementationProgress(
  sourceLabel: String,
): FeatureTaskRuntimeImplementationProgress {
  if (this[ImplKeys.CONTRACT_VERSION] == FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION) {
    return toImplementationProgress(sourceLabel)
  }
  val historical = toHistoricalImplementationProgress(sourceLabel)
  if (!historical.completedMutations.isNullOrEmpty() || !historical.independentChecks.isNullOrEmpty()) {
    throw invalidFeatureTaskRuntimeImplementationProgressSchema(
      sourceLabel,
      "Retained historical evidence requires the deferred evidence migration. Original records must be preserved.",
    )
  }
  return FeatureTaskRuntimeImplementationProgress(
    historical.criterionIds,
    historical.currentIndex,
    historical.workerCount,
    historical.launchCount,
    accountingPolicy = AcceptanceCriterionAccountingPolicy.PRE_ADOPTION,
  )
}
