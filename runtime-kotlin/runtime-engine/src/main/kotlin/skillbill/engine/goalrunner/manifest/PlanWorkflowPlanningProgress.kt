package skillbill.engine.goalrunner.manifest

import skillbill.application.workflow.decomposition.findLatestPlanWorkflowId
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal data class PlanWorkflowPlanningProgress(
  val preplanRecorded: Boolean,
  val plannedSubtaskIds: Set<Int>,
)

internal fun GoalRunnerPersistenceSession.planWorkflowPlanningProgress(
  parentWorkflowId: String,
): PlanWorkflowPlanningProgress? {
  val issueKey = workflowStates.getFeatureTaskWorkflow(parentWorkflowId)?.issueKey ?: return null
  val repositoryIdentity = goalRunnerControls.controlState(parentWorkflowId).repositoryIdentity
  val planWorkflowId = workflowStates.findLatestPlanWorkflowId(issueKey, repositoryIdentity) ?: return null
  val snapshot = workflowStates.get(WorkflowFamily.TASK_RUNTIME, planWorkflowId) ?: return null
  val bundle =
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.value(snapshot.artifacts)
      ?.let(::decodePlanBundleFromArtifact)
  val records = decodePhaseRecords(snapshot.artifacts)
  val preplan = records[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN]
  return PlanWorkflowPlanningProgress(
    preplanRecorded = preplan?.outputArtifact != null,
    plannedSubtaskIds =
      bundle?.subtasks.orEmpty()
        .filter { it.detailsSha256 != null && records.hasSettledUnit(it.id) }
        .mapTo(mutableSetOf()) { it.id },
  )
}

private fun Map<String, FeatureTaskRuntimePhaseRecord>.hasSettledUnit(subtaskId: Int): Boolean {
  val unitRecord = this[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtaskId)]
  return unitRecord?.status == WorkflowStepStatus.COMPLETED && !unitRecord.outputArtifact.isNullOrBlank()
}
