package skillbill.workflow.decomposition.runtime

import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

fun DecompositionManifest.isActiveGoalRuntime(): Boolean =
  isActiveGoalRuntime(
    status,
    subtasks.any {
      it.status.decompositionStatus() !in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
    },
  )

fun isActiveGoalRuntime(
  manifestStatus: String,
  hasUnsettledSubtasks: Boolean,
): Boolean =
  manifestStatus.decompositionStatus() !in
    setOf(
      DecompositionStatus.COMPLETE,
      DecompositionStatus.SKIPPED,
    ) && hasUnsettledSubtasks
