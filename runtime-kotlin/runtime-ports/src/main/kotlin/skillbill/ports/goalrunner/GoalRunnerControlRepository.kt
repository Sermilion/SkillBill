package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy

interface GoalRunnerControlRepository {
  fun controlState(parentWorkflowId: String): GoalRunnerControlState

  fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState

  fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState

  fun clearControlState(parentWorkflowId: String)

  fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy?

  fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunnerReviewPolicy

  fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance>

  fun outOfBandAcceptancesForSubtasks(
    parentWorkflowId: String,
    subtaskIds: Set<Int>,
  ): Map<Int, GoalRunnerOutOfBandAcceptance> = outOfBandAcceptances(parentWorkflowId).filterKeys(subtaskIds::contains)

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance

  fun clearOutOfBandAcceptances(parentWorkflowId: String)
}
