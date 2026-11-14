package skillbill.engine.goalrunner.qa

import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolution
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityHydratedAddonInput
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection

internal fun earlySkipReason(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  gitOperations: WorkflowGitOperations,
  capabilities: QaCapabilityResolver,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
): GoalQaSkipDecision? {
  if (childrenAlreadyRanQa(outcomeStore, state)) {
    return GoalQaSkipDecision(GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA)
  }
  val prior = outcomeStore.recordedGoalQaOutcome(state.parentWorkflowId)
  if (prior?.outcome == StandaloneQaOutcome.PASSED) {
    val current = gitOperations.repositoryTreeIdentity(request.repoRoot)
    if (current is WorkflowGitOperationResult.Ok && current.value.trim() == prior.testedTreeIdentity) {
      return GoalQaSkipDecision(GoalQaSkipReason.ALREADY_SETTLED_ON_TREE, prior.testedTreeIdentity)
    }
  }
  return if (capabilities.resolve(request.repoRoot) is QaCapabilityResolution.Absent) {
    GoalQaSkipDecision(GoalQaSkipReason.UNSUPPORTED_TARGET)
  } else {
    null
  }
}

private fun childrenAlreadyRanQa(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  state: GoalRunnerManifestState,
): Boolean {
  val workflows =
    outcomeStore.resolvedGoalQualityChildIds(
      state.parentWorkflowId,
      state.manifest.subtasks.map { it.id to it.workflowId },
    )
  return workflows.isNotEmpty() &&
    workflows.all { workflowId ->
      outcomeStore.hasQaComposedPlan(workflowId) &&
        outcomeStore.recordedQaOutcome(workflowId) is GoalSubtaskQaSummary.Recorded
    }
}

internal fun governingInputs(
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  parentInvariants: FeatureTaskRuntimeRunInvariantsRead,
): GoalQualityExecutionInputs {
  val read = parentInvariants as? FeatureTaskRuntimeRunInvariantsRead.Read
  val rejected = parentInvariants as? FeatureTaskRuntimeRunInvariantsRead.Rejected
  return GoalQualityExecutionInputs(
    issueKey = request.issueKey,
    intake = request.intake,
    repoRoot = request.repoRoot.toAbsolutePath().normalize().toString(),
    invokedAgentId = request.invokedAgentId,
    configuredAgentOverrideId = request.configuredAgentOverrideId,
    timeoutMillis = request.timeout?.inWholeMilliseconds?.coerceAtLeast(1),
    qaSelection = request.qaSelection ?: FeatureTaskRuntimeQaSelection.AUTO,
    parentSpecPath = state.manifest.parentSpecPath,
    acceptanceCriteria = read?.invariants?.acceptanceCriteria,
    acceptanceCriteriaReadFailure = rejected?.reason,
    branch = state.manifest.featureBranch.orEmpty().ifBlank { state.manifest.branchForFinalPullRequest() },
    modelDirectives = request.modelAssignment.perPhaseDirectives,
    modelMatrix = request.modelAssignment.matrix,
    agentAssignmentsWorkflowId =
      request.workflowAgentAssignments
        ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
        ?.admissionWorkflowId,
    agentAssignmentsFingerprint =
      request.workflowAgentAssignments
        ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
        ?.let { request.workflowAgentAssignmentsFingerprint },
    addons =
      request.agentAddonSelection.entries.map { entry ->
        GoalQualityHydratedAddonInput(entry.persisted, entry.description, entry.content)
      },
  )
}
