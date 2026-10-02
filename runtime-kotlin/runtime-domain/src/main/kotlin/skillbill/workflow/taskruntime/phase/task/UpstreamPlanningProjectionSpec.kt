package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDelivery
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef

internal data class UpstreamPlanningProjectionSpec(
  val consumerPhaseId: String,
  val sourceRef: FeatureTaskRuntimeHandoffSourceRef,
  val projectionName: String,
  val projectionContractId: String,
  val declaredFieldNames: List<String>,
  val delivery: PhaseHandoffProjectionDelivery,
  val projectionContractVersion: String = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VERSION,
)
