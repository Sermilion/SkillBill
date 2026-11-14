package skillbill.engine.goalrunner.launch

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentStore
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchAssignmentFactory
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

@Inject
public class GoalChildExecutionPlanPreparation(
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  private val strategies: PhaseStrategyLookup,
  private val agentRunLauncher: AgentRunLauncher,
  private val workflowAgentAssignmentStore: WorkflowAgentAssignmentStore,
) {
  internal fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    reviewMode: CodeReviewExecutionMode,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
    priorWorkflowId: String?,
  ): ValidatedFeatureTaskRuntimeExecutionPlan {
    val routing =
      (priorWorkflowId?.let(workflowAgentAssignmentStore::inspect) as? WorkflowAgentAssignmentInspection.Present)
        ?: (workflowAgentAssignmentStore.inspect(state.parentWorkflowId) as? WorkflowAgentAssignmentInspection.Present)
    val assignedQualityGate =
      if (priorWorkflowId == null) executionPlans.creationQualityGate(request.repoRoot, qualityGate) else qualityGate
    return executionPlans.resolveCreation(
      FeatureTaskRuntimeExecutionPlanCreationRequest(
        repoRoot = request.repoRoot,
        definition = SkeletonDefinition.GOAL_CHILD_REVISION_5,
        reviewMode = reviewMode,
        qualityGate = qualityGate,
        validationDepth = ValidationDepth.FULL,
        timeout = request.timeout,
        workflowId = priorWorkflowId,
        stepLaunchAssignments =
          FeatureTaskRuntimeStepLaunchAssignmentFactory.resolve(
            launcher = agentRunLauncher,
            lookup = strategies,
            facts =
              PhaseStrategySelectionFacts(
                SkeletonDefinition.GOAL_CHILD_REVISION_5,
                setOfNotNull(reviewMode, assignedQualityGate),
              ),
            inputs =
              StepLaunchAssignmentInputs(
                invokedAgentId = request.invokedAgentId,
                agentAssignment = request.agentAssignment,
                modelAssignment = request.modelAssignment,
                environmentKind = LaunchEnvironmentKind.GOVERNED_CHILD,
                workflowAgentAssignments = routing?.assignments,
              ),
          ),
      ),
    )
  }
}
