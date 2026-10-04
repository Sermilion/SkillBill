package skillbill.engine.goalrunner.planning.model

import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome

data class GoalPlanningAttemptRecord(
  val parentWorkflowId: String,
  val issueKey: String,
  val phaseId: String,
  val subtaskId: Int,
  val attempt: Int,
  val outcome: GoalProgressOutcome,
  val eventKind: GoalProgressEventKind = GoalProgressEventKind.OPERATION_COMPLETED,
)
