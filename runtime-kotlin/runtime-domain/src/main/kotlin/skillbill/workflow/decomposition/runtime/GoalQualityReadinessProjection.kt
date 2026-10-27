package skillbill.workflow.decomposition.runtime

import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch

fun WorkflowArtifactPatch.withHistoricalGoalQualityReadiness(): WorkflowArtifactPatch =
  requireNotNull(
    WorkflowArtifactPatch.from(
      this +
        DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.entry(
          mapOf(
            GoalQualityExecutionPayloadKeys.CONTRACT_VERSION to GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
            GoalQualityExecutionPayloadKeys.READINESS to GoalQualityReadiness.HISTORICAL_AMBIGUITY.wireValue,
            GoalQualityExecutionPayloadKeys.EXECUTION to null,
          ),
        ),
    ),
  )
