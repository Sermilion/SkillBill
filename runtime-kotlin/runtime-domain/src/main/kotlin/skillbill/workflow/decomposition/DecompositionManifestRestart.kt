package skillbill.workflow.decomposition

import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.DecompositionSubtaskAction
import skillbill.workflow.model.decompositionStatus

fun DecompositionManifest.isAtUnlaunchedBoundary(): Boolean {
  val currentSubtask = subtasks.firstOrNull { it.id == currentSubtaskIntent.subtaskId }
  val currentAction = DecompositionSubtaskAction.fromWire(currentSubtaskIntent.action)
  val activeChildExists =
    subtasks.any { subtask ->
      subtask.status.decompositionStatus() == DecompositionStatus.IN_PROGRESS &&
        subtask.workflowId?.isNotBlank() == true
    }
  val unselected = currentSubtaskIntent.subtaskId == 0 && currentAction == DecompositionSubtaskAction.NONE
  val selectedButNotLaunched =
    currentSubtask?.let { subtask ->
      subtask.status.decompositionStatus() == DecompositionStatus.PENDING &&
        subtask.workflowId.isNullOrBlank() &&
        currentAction == DecompositionSubtaskAction.START
    } == true
  return !activeChildExists && (unselected || selectedButNotLaunched)
}

fun DecompositionManifest.resetManifest(hard: Boolean): DecompositionManifest {
  val resetSubtasks =
    subtasks.map { subtask ->
      when {
        hard -> subtask.resetToPending()
        subtask.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED) ->
          subtask.copy(
            blockedReason = null,
            lastResumableStep = null,
          )
        !subtask.workflowId.isNullOrBlank() ->
          subtask.copy(
            status = DecompositionStatus.IN_PROGRESS.wireValue,
            blockedReason = null,
          )
        else -> subtask.resetToPending()
      }
    }
  return copy(
    currentSubtaskIntent = restartIntent(resetSubtasks),
    subtasks = resetSubtasks,
  ).withParentStatus()
}

fun restartIntent(subtasks: List<DecompositionSubtask>): CurrentSubtaskIntent {
  if (subtasks.all {
      it.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
    }
  ) {
    return CurrentSubtaskIntent(subtaskId = 0, action = DecompositionSubtaskAction.COMPLETE.wireValue)
  }
  subtasks.firstOrNull { it.status.decompositionStatus() == DecompositionStatus.IN_PROGRESS }?.let { resumable ->
    return CurrentSubtaskIntent(subtaskId = resumable.id, action = DecompositionSubtaskAction.RESUME.wireValue)
  }
  val subtasksById = subtasks.associateBy(DecompositionSubtask::id)
  val nextRunnable =
    subtasks.firstOrNull { subtask ->
      subtask.status.decompositionStatus() == DecompositionStatus.PENDING &&
        subtask.dependencies.all { dependency ->
          val dependencySubtask = subtasksById[dependency.subtaskId]
          dependencySubtask?.status.decompositionStatus() in
            setOf(
              DecompositionStatus.COMPLETE,
              DecompositionStatus.SKIPPED,
            ) || (dependency.optional && dependency.skipped)
        }
    } ?: subtasks.firstOrNull { it.status.decompositionStatus() == DecompositionStatus.PENDING }
  return CurrentSubtaskIntent(
    subtaskId = nextRunnable?.id ?: 0,
    action =
      if (nextRunnable == null) {
        DecompositionSubtaskAction.COMPLETE.wireValue
      } else {
        DecompositionSubtaskAction.START.wireValue
      },
  )
}

fun replanIntent(subtask: DecompositionSubtask): CurrentSubtaskIntent {
  val action =
    when {
      subtask.status.decompositionStatus() == DecompositionStatus.IN_PROGRESS ||
        !subtask.workflowId.isNullOrBlank() -> DecompositionSubtaskAction.RESUME
      else -> DecompositionSubtaskAction.START
    }
  return CurrentSubtaskIntent(subtaskId = subtask.id, action = action.wireValue)
}

fun DecompositionManifest.afterIncompatibleChildDeletion(subtaskId: Int): DecompositionManifest =
  copy(
    currentSubtaskIntent =
      CurrentSubtaskIntent(subtaskId = subtaskId, action = DecompositionSubtaskAction.START.wireValue),
    subtasks = subtasks.map { subtask -> if (subtask.id != subtaskId) subtask else subtask.resetToPending() },
  ).withParentStatus()

fun DecompositionManifest.afterReplanChildDeletion(subtaskIds: List<Int>): DecompositionManifest {
  if (subtaskIds.isEmpty()) return this
  return copy(
    subtasks = subtasks.map { subtask -> if (subtask.id !in subtaskIds) subtask else subtask.resetToPending() },
  ).withParentStatus()
}

internal fun DecompositionSubtask.resetToPending(): DecompositionSubtask =
  copy(
    status = DecompositionStatus.PENDING.wireValue,
    branch = null,
    commitSha = null,
    workflowId = null,
    blockedReason = null,
    lastResumableStep = null,
  )
