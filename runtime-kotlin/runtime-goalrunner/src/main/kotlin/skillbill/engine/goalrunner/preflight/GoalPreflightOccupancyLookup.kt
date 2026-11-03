package skillbill.engine.goalrunner.preflight

import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus

internal fun adjustGoalPreflightContinuationLookup(
  lookup: FeatureTaskContinuationLookupResult,
  occupying: GoalRunnerManifestState?,
  occupyingWorkflowStatus: WorkflowStatus?,
): FeatureTaskContinuationLookupResult {
  if (occupying != null) {
    return occupying.occupyingLookup(occupyingWorkflowStatus, lookup)
  }
  if (lookup is FeatureTaskContinuationLookupResult.TerminalOnly && lookup.reusableTerminalHistory()) {
    return FeatureTaskContinuationLookupResult.NoMatch
  }
  return lookup
}

private fun GoalRunnerManifestState.occupyingLookup(
  occupyingWorkflowStatus: WorkflowStatus?,
  lookup: FeatureTaskContinuationLookupResult,
): FeatureTaskContinuationLookupResult {
  if (occupyingWorkflowStatus == WorkflowStatus.FAILED) {
    return FeatureTaskContinuationLookupResult.TerminalOnly(listOf(occupyingCandidate(WorkflowStatus.FAILED)))
  }
  return when (lookup) {
    is FeatureTaskContinuationLookupResult.Resumable,
    is FeatureTaskContinuationLookupResult.AlreadyRunning,
    is FeatureTaskContinuationLookupResult.GoalContinuation,
    is FeatureTaskContinuationLookupResult.NeedsIdentityRepair,
    -> lookup
    is FeatureTaskContinuationLookupResult.TerminalOnly ->
      if (lookup.reusableTerminalHistory()) {
        rebuildFromOccupyingStatus(occupyingWorkflowStatus)
      } else {
        lookup
      }
    FeatureTaskContinuationLookupResult.NoMatch -> rebuildFromOccupyingStatus(occupyingWorkflowStatus)
  }
}

private fun GoalRunnerManifestState.rebuildFromOccupyingStatus(
  occupyingWorkflowStatus: WorkflowStatus?,
): FeatureTaskContinuationLookupResult {
  val status = occupyingWorkflowStatus ?: WorkflowStatus.PAUSED
  return when (status) {
    WorkflowStatus.RUNNING ->
      FeatureTaskContinuationLookupResult.AlreadyRunning(occupyingCandidate(status))
    WorkflowStatus.FAILED ->
      FeatureTaskContinuationLookupResult.TerminalOnly(listOf(occupyingCandidate(status)))
    WorkflowStatus.COMPLETED,
    WorkflowStatus.ABANDONED,
    -> FeatureTaskContinuationLookupResult.NoMatch
    else -> FeatureTaskContinuationLookupResult.Resumable(occupyingCandidate(status))
  }
}

private fun FeatureTaskContinuationLookupResult.TerminalOnly.reusableTerminalHistory(): Boolean {
  if (candidates.isEmpty()) return false
  val latest = candidates.last().status.workflowStatus()
  return latest == WorkflowStatus.COMPLETED || latest == WorkflowStatus.ABANDONED
}

private fun GoalRunnerManifestState.occupyingCandidate(status: WorkflowStatus): FeatureTaskContinuationCandidate =
  FeatureTaskContinuationCandidate(
    executionIdentity =
      FeatureTaskExecutionIdentity(
        workflowId = parentWorkflowId,
        normalizedIssueKey = manifest.issueKey,
        repositoryIdentity = controlState.repositoryIdentity.orEmpty(),
        governedSpecPath = manifest.parentSpecPath,
        mode = FeatureTaskWorkflowMode.RUNTIME,
      ),
    workflowId = parentWorkflowId,
    mode = FeatureTaskWorkflowMode.RUNTIME,
    status = status.wireValue,
    currentStep = "plan",
    governedSpecPath = manifest.parentSpecPath,
    updatedAt = null,
    liveness = null,
    summary = "Occupying goal parent $parentWorkflowId with status '${status.wireValue}'",
  )
