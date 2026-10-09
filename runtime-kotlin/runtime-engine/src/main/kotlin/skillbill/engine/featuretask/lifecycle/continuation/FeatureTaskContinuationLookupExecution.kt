package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.application.workflow.decomposition.goalContinuationFor
import skillbill.application.workflow.decomposition.isPlanWorkflow
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupQuery
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.error.shellcontent.invalidFeatureTaskExecutionIdentitySchema
import skillbill.goalrunner.model.GoalContinuation
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

fun executeFeatureTaskContinuationLookup(
  query: FeatureTaskContinuationLookupQuery,
  unitOfWork: UnitOfWork,
  project: (
    FeatureTaskWorkflowCandidate,
    FeatureTaskRuntimeWorkerOwnership?,
    FeatureTaskRouteScope,
  ) -> FeatureTaskContinuationCandidate,
  classify: (List<FeatureTaskContinuationCandidate>) -> FeatureTaskContinuationLookupResult,
): FeatureTaskContinuationLookupResult {
  val normalizedIssueKey =
    FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
      query.issueKey,
      query.repositoryIdentity,
    )
  val planDefinition = query.admittedDefinition == SkeletonDefinition.PLAN
  val candidates =
    when (query.routeScope) {
      FeatureTaskRouteScope.STANDALONE ->
        unitOfWork.workflowStates.findStandaloneFeatureTaskCandidates(
          normalizedIssueKey,
          query.repositoryIdentity,
        )
      FeatureTaskRouteScope.GOAL_CHILD ->
        unitOfWork.workflowStates.findGoalChildFeatureTaskCandidates(
          normalizedIssueKey,
          query.repositoryIdentity,
        )
    }.filter { candidate -> candidate.workflow.isPlanWorkflow() == planDefinition }
  val selected =
    query.workflowId?.let { selector ->
      listOf(
        candidates.singleOrNull { it.workflow.workflowId == selector }
          ?: throw invalidFeatureTaskExecutionIdentitySchema(
            "lookup request",
            "workflow selector '$selector' does not match this issue and repository",
          ),
      )
    } ?: candidates
  val identityLess = selected.firstOrNull { it.identity == null }
  if (identityLess != null) {
    return FeatureTaskContinuationLookupResult.NeedsIdentityRepair(
      workflowId = identityLess.workflow.workflowId,
      summary =
        "Workflow '${identityLess.workflow.workflowId}' has no immutable execution identity; " +
          "run `skill-bill feature-task repair-identity` for that workflow id before continuing.",
    )
  }
  val validated =
    selected.map {
      project(
        it,
        unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(it.workflow.workflowId),
        query.routeScope,
      )
    }
  val classified = classify(validated)
  val unselectedFeatureTaskLookup =
    query.workflowId == null && query.routeScope == FeatureTaskRouteScope.STANDALONE && !planDefinition
  if (classified != FeatureTaskContinuationLookupResult.NoMatch || !unselectedFeatureTaskLookup) {
    return classified
  }
  return unitOfWork.workflowStates.goalContinuationFor(
    normalizedIssueKey,
    query.repositoryIdentity,
  )?.let(FeatureTaskContinuationLookupResult::GoalContinuation) ?: classified
}
