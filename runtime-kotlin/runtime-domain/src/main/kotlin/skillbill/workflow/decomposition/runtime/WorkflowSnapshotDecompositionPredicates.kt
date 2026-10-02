package skillbill.workflow.decomposition.runtime

import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.model.persistence.hasGoalContinuationMarker

fun WorkflowStateSnapshot.decompositionRuntime(): DecompositionManifest? = artifacts.decompositionRuntime()

fun WorkflowStateSnapshot.hasDecompositionPlan(): Boolean =
  JsonCodec.anyToStringAnyMap(artifacts["plan"])?.get(DecompositionPlanningPayloadKeys.MODE) == "decompose"

fun WorkflowStateSnapshot.isGoalContinuationChildWorkflow(): Boolean = artifacts.hasGoalContinuationMarker()
