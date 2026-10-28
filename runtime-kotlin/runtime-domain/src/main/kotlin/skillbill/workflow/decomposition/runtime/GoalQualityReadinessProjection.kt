package skillbill.workflow.decomposition.runtime

import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch

fun WorkflowArtifactPatch.withImportedGoalQualityReadiness(
  manifest: DecompositionManifest,
  completedPlanWorkflowId: String?,
): WorkflowArtifactPatch =
  requireNotNull(
    WorkflowArtifactPatch.from(
      this +
        DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.entry(
          mapOf(
            GoalQualityExecutionPayloadKeys.CONTRACT_VERSION to GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
            GoalQualityExecutionPayloadKeys.READINESS to
              importedGoalQualityReadiness(manifest, completedPlanWorkflowId).wireValue,
            GoalQualityExecutionPayloadKeys.EXECUTION to null,
          ),
        ),
    ),
  )

fun importedGoalQualityReadiness(
  manifest: DecompositionManifest,
  completedPlanWorkflowId: String?,
): GoalQualityReadiness =
  if (completedPlanWorkflowId != null && manifest.subtasks.none { it.hasStarted() }) {
    GoalQualityReadiness.CLEAN_UNSTARTED
  } else {
    GoalQualityReadiness.HISTORICAL_AMBIGUITY
  }
