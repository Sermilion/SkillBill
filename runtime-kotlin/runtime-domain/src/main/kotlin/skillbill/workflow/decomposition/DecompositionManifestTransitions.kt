package skillbill.workflow.decomposition

import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.DecompositionSubtaskAction
import skillbill.workflow.model.decompositionStatus

fun intentFor(
  subtaskId: Int,
  status: String?,
): CurrentSubtaskIntent =
  when (status.decompositionStatus()) {
    DecompositionStatus.BLOCKED ->
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.BLOCKED.wireValue)
    DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED ->
      CurrentSubtaskIntent(subtaskId = 0, action = DecompositionSubtaskAction.COMPLETE.wireValue)
    DecompositionStatus.IN_PROGRESS ->
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.RESUME.wireValue)
    else -> CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.START.wireValue)
  }

fun DecompositionManifest.withParentStatus(): DecompositionManifest {
  val parentStatus =
    when {
      subtasks.all {
        it.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
      } -> DecompositionStatus.COMPLETE.wireValue
      subtasks.any { it.status.decompositionStatus() == DecompositionStatus.BLOCKED } ->
        DecompositionStatus.BLOCKED.wireValue
      subtasks.any {
        it.status.decompositionStatus() in
          setOf(
            DecompositionStatus.IN_PROGRESS,
            DecompositionStatus.COMPLETE,
            DecompositionStatus.SKIPPED,
          ) || it.hasStarted()
      } -> DecompositionStatus.IN_PROGRESS.wireValue
      else -> DecompositionStatus.PENDING.wireValue
    }
  return copy(status = parentStatus)
}

fun DecompositionManifest.withBlockedSubtask(
  subtaskId: Int,
  reason: String,
  lastResumableStep: String,
): DecompositionManifest =
  copy(
    status = DecompositionStatus.BLOCKED.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.RESUME.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = DecompositionStatus.BLOCKED.wireValue,
            blockedReason = reason.ifBlank { "Subtask $subtaskId is blocked." },
            lastResumableStep = lastResumableStep,
          )
        } else {
          subtask
        }
      },
  )

fun DecompositionManifest.withRetriedSubtask(
  subtaskId: Int,
  workflowId: String,
  lastResumableStep: String,
): DecompositionManifest {
  require(subtasks.any { it.id == subtaskId }) {
    "Cannot retry unknown decomposition subtask '$subtaskId'."
  }
  return copy(
    status = DecompositionStatus.IN_PROGRESS.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.RESUME.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = DecompositionStatus.IN_PROGRESS.wireValue,
            workflowId = workflowId,
            blockedReason = null,
            lastResumableStep = lastResumableStep,
          )
        } else {
          subtask
        }
      },
  )
}

fun DecompositionManifest.withAttemptedSubtask(subtaskId: Int): DecompositionManifest =
  copy(
    status = DecompositionStatus.IN_PROGRESS.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.RESUME.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId && subtask.status.decompositionStatus() in
          setOf(
            DecompositionStatus.BLOCKED,
            DecompositionStatus.PENDING,
          )
        ) {
          subtask.copy(
            status = DecompositionStatus.IN_PROGRESS.wireValue,
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
      currentSubtaskIntent =
        CurrentSubtaskIntent(subtaskId = 0, action = DecompositionSubtaskAction.COMPLETE.wireValue),
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
    status = DecompositionStatus.BLOCKED.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.BLOCKED.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = DecompositionStatus.BLOCKED.wireValue,
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
    status = DecompositionStatus.IN_PROGRESS.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.RESUME.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = DecompositionStatus.IN_PROGRESS.wireValue,
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

fun DecompositionManifest.withValidationQualityRetrySubtask(subtaskId: Int): DecompositionManifest =
  copy(
    status = DecompositionStatus.IN_PROGRESS.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.RESUME.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(status = DecompositionStatus.IN_PROGRESS.wireValue, blockedReason = null)
        } else {
          subtask
        }
      },
  )

fun DecompositionManifest.withBranchSetupBlockedSubtask(
  subtaskId: Int,
  reason: String,
): DecompositionManifest =
  copy(
    status = DecompositionStatus.BLOCKED.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.BLOCKED.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = DecompositionStatus.BLOCKED.wireValue,
            blockedReason = reason,
            lastResumableStep = "create_branch",
          )
        } else {
          subtask
        }
      },
  )

fun DecompositionManifest.withBlockedSelection(
  subtaskId: Int,
  reason: String,
): DecompositionManifest =
  copy(
    status = DecompositionStatus.BLOCKED.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.BLOCKED.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = DecompositionStatus.BLOCKED.wireValue,
            blockedReason = reason,
            lastResumableStep = subtask.lastResumableStep ?: "preplan",
          )
        } else {
          subtask
        }
      },
  )

fun DecompositionManifest.branchForFinalPullRequest(): String = stackBranches.lastOrNull()?.branch.orEmpty()

fun DecompositionManifest.withStartedSubtask(
  subtaskId: Int,
  workflowId: String,
  branch: String,
): DecompositionManifest =
  copy(
    status = DecompositionStatus.IN_PROGRESS.wireValue,
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.RESUME.wireValue),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = DecompositionStatus.IN_PROGRESS.wireValue,
            workflowId = workflowId,
            branch = branch.takeIf(String::isNotBlank) ?: subtask.branch,
            lastResumableStep = "preplan",
          )
        } else {
          subtask
        }
      },
  )

fun DecompositionManifest.withCommittedSubtask(
  subtaskId: Int,
  commitSha: String,
): DecompositionManifest =
  copy(subtasks = subtasks.map { if (it.id == subtaskId) it.copy(commitSha = commitSha) else it })

fun DecompositionManifest.branchForSubtask(subtaskId: Int): String =
  when (executionModel) {
    DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> featureBranch.orEmpty()
    DecompositionExecutionModel.STACKED_BRANCHES ->
      stackBranches.firstOrNull { it.subtaskId == subtaskId }?.branch.orEmpty()
  }

fun DecompositionManifest.baseForSubtask(subtaskId: Int): String? =
  when (executionModel) {
    DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> baseBranch
    DecompositionExecutionModel.STACKED_BRANCHES ->
      stackBranches.firstOrNull { it.subtaskId == subtaskId }?.baseBranch ?: baseBranch
  }

fun DecompositionManifest.withPreservedRuntimeState(existing: DecompositionManifest?): DecompositionManifest {
  if (existing == null) {
    return this
  }
  val existingById = existing.subtasks.associateBy(DecompositionSubtask::id)
  return copy(
    status = existing.status,
    subtasks =
      subtasks.map { planned ->
        val previous = existingById[planned.id]
        if (previous == null) {
          planned
        } else {
          planned.copy(
            status = previous.status,
            branch = previous.branch,
            commitSha = previous.commitSha,
            workflowId = previous.workflowId,
            blockedReason = previous.blockedReason,
            lastResumableStep = previous.lastResumableStep,
          )
        }
      },
    currentSubtaskIntent = existing.currentSubtaskIntent,
  )
}
