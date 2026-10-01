package skillbill.application.workflow.decomposition

import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.workflowStatus

private val GOAL_TERMINAL_MANIFEST_STATUSES = setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)

fun WorkflowStateRepository.goalContinuationFor(
  issueKey: String,
  repositoryIdentity: String,
): GoalContinuationCandidate? {
  val record =
    findDecomposedParentWorkflow(issueKey)
      ?.takeIf { it.workflowStatus.workflowStatus() !in IMPLEMENT_TERMINAL_STATUSES }
      ?: return null
  val manifest =
    record.toSnapshot().decompositionRuntime()
      ?.takeIf { it.status.decompositionStatus() !in GOAL_TERMINAL_MANIFEST_STATUSES }
      ?: return null
  val boundToThisRepository =
    findGoalChildFeatureTaskCandidates(issueKey, repositoryIdentity).isNotEmpty() ||
      countGoalChildIdentities(issueKey) == 0
  if (!boundToThisRepository) return null
  val running = record.workflowStatus.workflowStatus() == WorkflowStatus.RUNNING
  return GoalContinuationCandidate(
    parentWorkflowId = record.workflowId,
    issueKey = manifest.issueKey,
    status = record.workflowStatus,
    currentSubtaskId = manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 },
    currentAction = manifest.currentSubtaskIntent.action,
    completeCount = manifest.subtasks.count { it.status.decompositionStatus() == DecompositionStatus.COMPLETE },
    pendingCount = manifest.subtasks.count { it.status.decompositionStatus() !in GOAL_TERMINAL_MANIFEST_STATUSES },
    blockedCount = manifest.subtasks.count { it.status.decompositionStatus() == DecompositionStatus.BLOCKED },
    updatedAt = record.updatedAt,
    summary =
      if (running) {
        "A goal run for '${manifest.issueKey}' is already in progress; check it before starting another."
      } else {
        "A prepared goal for '${manifest.issueKey}' owns durable state; continue it instead of starting new work."
      },
  )
}
