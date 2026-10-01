package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.observability.continuation
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

fun goalContinuationConflict(
  request: FeatureTaskRuntimeRunFacts,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
): String? =
  listOfNotNull(
    requestedReviewModeConflict(request, durable),
    request.goalContinuation?.let { supplied -> suppliedGoalContinuationConflict(supplied, durable, baseline) },
  ).firstOrNull()

private fun requestedReviewModeConflict(
  request: FeatureTaskRuntimeRunFacts,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
): String? =
  request.requestedCodeReviewMode
    ?.takeIf { it != durable.codeReviewMode }
    ?.let {
      "Cannot change code-review mode on a goal child resume; its durable continuation policy is " +
        "'${durable.codeReviewMode.wireValue}'."
    }

private fun suppliedGoalContinuationConflict(
  supplied: FeatureTaskRuntimeGoalContinuationContext,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
): String? =
  listOfNotNull(
    suppliedIdentityConflict(supplied, durable),
    supplied.codeReviewMode?.takeIf { it != durable.codeReviewMode }?.let {
      "The supplied goal-continuation code-review mode conflicts with its durable child policy."
    },
    durable.validationDepth?.takeIf { it != supplied.validationDepth }?.let {
      "The supplied goal-continuation validation depth conflicts with its durable child policy."
    },
    durable.qualityGateSelection?.takeIf { it != supplied.qualityGateSelection }?.let {
      "The supplied goal-continuation quality gate selection conflicts with its durable child policy."
    },
    requireNotNull(supplied.reviewBaseline).takeIf { it != baseline }?.let {
      "The supplied goal-continuation review baseline conflicts with its durable child policy."
    },
  ).firstOrNull()

private fun suppliedIdentityConflict(
  supplied: FeatureTaskRuntimeGoalContinuationContext,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
): String? =
  if (suppliedIdentityMatchesDurable(supplied, durable)) {
    null
  } else {
    "The supplied goal-continuation identity conflicts with its durable child policy."
  }

private fun suppliedIdentityMatchesDurable(
  supplied: FeatureTaskRuntimeGoalContinuationContext,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
): Boolean =
  supplied.parentIssueKey == durable.issueKey &&
    supplied.subtaskId == durable.subtaskId &&
    supplied.goalBranch == durable.goalBranch &&
    supplied.suppressPr == durable.suppressPr &&
    supplied.parentWorkflowId == durable.parentWorkflowId

fun newGoalContinuationConflict(
  request: FeatureTaskRuntimeRunFacts,
  selectedReviewMode: CodeReviewExecutionMode,
): String? {
  requireNotNull(request.goalContinuation)
  return request.requestedCodeReviewMode?.takeIf { it != selectedReviewMode }?.let {
    "The supplied goal-continuation code-review mode conflicts with the requested child review policy."
  }
}

fun goalContinuationPolicyBlockedReport(
  request: FeatureTaskRuntimeRunFacts,
  runInvariants: FeatureTaskRuntimeRunInvariants,
  reason: String,
): FeatureTaskRuntimeRunReport.Blocked =
  FeatureTaskRuntimeRunReport.Blocked(
    issueKey = request.issueKey,
    workflowId = request.workflowId,
    featureSize = runInvariants.featureSize.name,
    lastIncompletePhase = FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultInitialStepId,
    blockedReason = reason,
    completedPhaseIds = emptyList(),
    resolvedBranch = null,
  )
