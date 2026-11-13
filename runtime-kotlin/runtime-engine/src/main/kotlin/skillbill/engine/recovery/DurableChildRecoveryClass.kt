package skillbill.engine.recovery

import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus

public enum class DurableChildRecoveryClass(public val wireValue: String) {
  ABSENT("absent"),
  ACTIVE("active"),
  RESUMABLE("resumable"),
  INCOMPATIBLE_TERMINAL("incompatible_terminal"),
}

public fun classifyDurableChild(progress: WorkflowStatus?): DurableChildRecoveryClass =
  when (progress) {
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
): String = "sona goal reset $issueKey --subtask $subtaskId --delete-child-workflow"

public fun recommendedDurableChildRecoveryCommand(
  issueKey: String,
  subtaskId: Int,
  subtaskStatus: DecompositionStatus?,
  childProgress: WorkflowStatus?,
): String =
  if (
    classifyDurableChild(childProgress) == DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL &&
    subtaskStatus == DecompositionStatus.BLOCKED
  ) {
    scopedChildRecoveryCommand(issueKey, subtaskId)
  } else {
    hardResetRecoveryCommand(issueKey)
  }

public fun staleChildPlanningRecoveryCommand(
  issueKey: String,
  subtaskId: Int,
): String = "sona goal replan $issueKey --subtask $subtaskId"

private fun hardResetRecoveryCommand(issueKey: String): String = "sona goal reset $issueKey --hard --yes"
