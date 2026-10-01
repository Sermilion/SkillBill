package skillbill.engine.recovery

import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus

internal enum class DurableChildRecoveryClass(val wireValue: String) {
  ABSENT("absent"),
  ACTIVE("active"),
  RESUMABLE("resumable"),
  INCOMPATIBLE_TERMINAL("incompatible_terminal"),
}

internal fun classifyDurableChild(progress: GoalRunnerWorkflowProgress?): DurableChildRecoveryClass =
  when (progress?.workflowStatus) {
    null -> DurableChildRecoveryClass.ABSENT
    WorkflowStatus.RUNNING -> DurableChildRecoveryClass.ACTIVE
    WorkflowStatus.PENDING, WorkflowStatus.PAUSED -> DurableChildRecoveryClass.RESUMABLE
    WorkflowStatus.BLOCKED, WorkflowStatus.FAILED, WorkflowStatus.ABANDONED, WorkflowStatus.TIMED_OUT,
    WorkflowStatus.COMPLETED,
    ->
      DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL
  }

internal fun scopedChildRecoveryCommand(
  issueKey: String,
  subtaskId: Int,
): String = "skill-bill goal reset $issueKey --subtask $subtaskId --delete-child-workflow"

internal fun recommendedDurableChildRecoveryCommand(
  issueKey: String,
  subtaskId: Int,
  subtaskStatus: DecompositionStatus?,
  childProgress: GoalRunnerWorkflowProgress?,
): String =
  if (
    classifyDurableChild(childProgress) == DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL &&
    subtaskStatus == DecompositionStatus.BLOCKED
  ) {
    scopedChildRecoveryCommand(issueKey, subtaskId)
  } else {
    hardResetRecoveryCommand(issueKey)
  }

internal fun staleChildPlanningRecoveryCommand(
  issueKey: String,
  subtaskId: Int,
): String = "skill-bill goal replan $issueKey --subtask $subtaskId"

private fun hardResetRecoveryCommand(issueKey: String): String = "skill-bill goal reset $issueKey --hard --yes"
