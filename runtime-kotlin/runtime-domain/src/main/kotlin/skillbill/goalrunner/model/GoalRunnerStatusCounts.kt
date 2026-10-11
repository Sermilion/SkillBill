package skillbill.goalrunner.model

import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

data class GoalRunnerStatusCounts(val complete: Int = 0, val pending: Int = 0, val blocked: Int = 0) {
  fun adding(
    status: DecompositionStatus?,
    delta: Int = 1,
  ): GoalRunnerStatusCounts =
    when (status) {
      DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED, DecompositionStatus.COMPLETED_NO_CHANGE ->
        copy(
          complete = complete + delta,
        )
      DecompositionStatus.BLOCKED -> copy(blocked = blocked + delta)
      else -> copy(pending = pending + delta)
    }

  internal fun reclassify(
    subtask: DecompositionSubtask,
    projected: DecompositionStatus?,
  ): GoalRunnerStatusCounts = adding(subtask.status.decompositionStatus(), -1).adding(projected)

  companion object {
    fun fromSubtasks(subtasks: Iterable<DecompositionSubtask>): GoalRunnerStatusCounts =
      subtasks.fold(GoalRunnerStatusCounts()) { counts, subtask -> counts.adding(subtask.status.decompositionStatus()) }
  }
}
