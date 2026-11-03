package skillbill.workflow.decomposition.runtime

import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.occupiesGoalIssueKey
import skillbill.workflow.model.workflowStatus

fun DecompositionManifest.isActiveGoalRuntime(): Boolean =
  isActiveGoalRuntime(
    status,
    subtasks.any {
      it.status.decompositionStatus() !in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
    },
  )

fun DecompositionManifest.isOccupyingActiveGoalRuntime(workflowStatus: String?): Boolean =
  isOccupyingActiveGoalRuntime(
    workflowStatus,
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

fun isOccupyingActiveGoalRuntime(
  workflowStatus: String?,
  manifestStatus: String,
  hasUnsettledSubtasks: Boolean,
): Boolean =
  workflowStatus.workflowStatus().occupiesGoalIssueKey() &&
    isActiveGoalRuntime(manifestStatus, hasUnsettledSubtasks)
