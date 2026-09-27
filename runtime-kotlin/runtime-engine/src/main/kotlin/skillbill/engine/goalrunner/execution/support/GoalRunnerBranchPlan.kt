package skillbill.engine.goalrunner.execution.support

import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

internal data class GoalRunnerBranchPlan(
  val branch: String,
  val baseBranch: String,
  val validateBase: Boolean,
)

fun DecompositionManifest.withAttemptedSubtask(subtaskId: Int): DecompositionManifest =
  copy(
    status = "in_progress",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId && subtask.status.decompositionStatus() in
          setOf(
            DecompositionStatus.BLOCKED,
            DecompositionStatus.PENDING,
          )
        ) {
          subtask.copy(
            status = "in_progress",
            blockedReason = null,
            lastResumableStep = if (subtask.workflowId.isNullOrBlank()) null else subtask.lastResumableStep,
          )
        } else {
          subtask
        }
      },
  )

fun DecompositionManifest.withWorkflowId(
  subtaskId: Int,
  workflowId: String,
): DecompositionManifest =
  copy(
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) subtask.copy(workflowId = workflowId) else subtask
      },
  )

fun DecompositionManifest.knownWorkflowId(
  subtaskId: Int,
  outcome: GoalRunnerReconciledOutcome.Stop,
): String? = outcome.workflowId ?: subtasks.firstOrNull { it.id == subtaskId }?.workflowId?.takeIf(String::isNotBlank)

fun DecompositionManifest.withCompletedSubtask(
  subtaskId: Int,
  outcome: GoalRunnerReconciledOutcome.Complete,
): DecompositionManifest {
  val updated =
    copy(
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
      subtasks =
        subtasks.map { subtask ->
          if (subtask.id == subtaskId) {
            subtask.copy(
              status = DecompositionStatus.COMPLETE.wireValue,
              workflowId = outcome.workflowId,
              commitSha = outcome.commitSha,
              blockedReason = null,
              lastResumableStep = outcome.lastResumableStep,
            )
          } else {
            subtask
          }
        },
    )
  return if (updated.subtasks.all {
      it.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
    }
  ) {
    updated.copy(status = DecompositionStatus.COMPLETE.wireValue)
  } else {
    updated.copy(status = DecompositionStatus.IN_PROGRESS.wireValue)
  }
}

fun DecompositionManifest.withStoppedSubtask(
  subtaskId: Int,
  outcome: GoalRunnerReconciledOutcome.Stop,
  knownWorkflowId: String? = outcome.workflowId,
): DecompositionManifest =
  copy(
    status = "blocked",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked"),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = "blocked",
            workflowId = knownWorkflowId ?: subtask.workflowId,
            commitSha = outcome.commitSha ?: subtask.commitSha,
            blockedReason = outcome.blockedReason,
            lastResumableStep = outcome.lastResumableStep,
          )
        } else {
          subtask
        }
      },
  )

fun DecompositionManifest.withResumableSubtask(
  subtaskId: Int,
  outcome: GoalRunnerReconciledOutcome.Stop,
  knownWorkflowId: String? = outcome.workflowId,
): DecompositionManifest =
  copy(
    status = "in_progress",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume"),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = "in_progress",
            workflowId = knownWorkflowId ?: subtask.workflowId,
            commitSha = outcome.commitSha ?: subtask.commitSha,
            blockedReason = null,
            lastResumableStep = outcome.lastResumableStep,
          )
        } else {
          subtask
        }
      },
  )
