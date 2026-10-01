package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.expectedFeatureTaskExecutionIdentity
import skillbill.engine.featuretask.lifecycle.execution.governedFeatureTaskSpecPath
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeRunEntry(
  private val workflowService: WorkflowService,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  private val workerCoordinator: FeatureTaskRuntimeWorkerCoordinator,
  private val runInvariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val runner: FeatureTaskRuntimeRunner,
  private val repositories: RepositoryEnclosingRootPort,
) {
  fun run(
    input: FeatureTaskRuntimeRunInput,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
  ): FeatureTaskRuntimeRunReport {
    val goalContinuation = input.goalContinuation
    val scope = routeScope(goalContinuation != null)
    val workflowId = input.explicitWorkflowId ?: open(input, scope, onOpenFailure)
    val specPath = Path.of(input.specPath)
    val effectiveInputs =
      executionPlans.resolveInputs(
        input.repoRoot,
        goalContinuation?.qualityGateSelection,
        goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
        input.timeout,
        workflowId,
      )
    val identity =
      repositories.expectedFeatureTaskExecutionIdentity(workflowId, input.issueKey, input.repoRoot, specPath, scope)
    return workerCoordinator.runOwned(
      workflowId,
      effectiveInputs,
      identity,
      requestedReviewSelection =
        (goalContinuation?.codeReviewMode ?: input.requestedCodeReviewMode)
          ?.let { RuntimeReviewSelection.valueOf(it.name) },
    ) { admittedExecution ->
      val sourceInvariants = runInvariantsSource.read(specPath)
      runner.run(
        FeatureTaskRuntimeRunRequest(
          issueKey = input.issueKey,
          workflowId = workflowId,
          admittedExecution = admittedExecution,
          sessionId =
            "${FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultSessionPrefix}-$workflowId",
          runInvariants =
            sourceInvariants.copy(
              codeReviewMode = admittedExecution.reviewMode ?: sourceInvariants.codeReviewMode,
              agentAddonSelection = input.agentAddonSelection.persisted,
            ),
          invokedAgentId = input.invokedAgentId,
          agentAssignment = input.agentAssignment,
          modelAssignment = input.modelAssignment,
          compactionSettings = input.compactionSettings,
          environment = input.environment,
          repoRoot = input.repoRoot,
          timeout = input.timeout,
          requestedCodeReviewMode = input.requestedCodeReviewMode,
          goalContinuation = goalContinuation,
          operatorDecision = input.operatorDecision,
          agentAddonSelection = input.agentAddonSelection,
          eventSink = input.eventSink,
        ),
      )
    }
  }

  private fun open(
    input: FeatureTaskRuntimeRunInput,
    scope: FeatureTaskRouteScope,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
  ): String {
    val goalContinuation = input.goalContinuation
    val specPath = Path.of(input.specPath)
    val opened =
      workflowService.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          sessionId = "",
          currentStepId = null,
          issueKey = input.issueKey,
          repositoryIdentity = repositories.repositoryIdentity(input.repoRoot),
          governedSpecPath = repositories.governedFeatureTaskSpecPath("unassigned", input.repoRoot, specPath),
          routeScope = scope,
          executionPlan =
            executionPlans.resolveCreation(
              FeatureTaskRuntimeExecutionPlanCreationRequest(
                repoRoot = input.repoRoot,
                definition = SkeletonDefinition.forRun(goalContinuation != null),
                reviewMode =
                  goalContinuation?.codeReviewMode ?: input.requestedCodeReviewMode
                    ?: runInvariantsSource.read(specPath).codeReviewMode,
                qualityGate = goalContinuation?.qualityGateSelection,
                validationDepth = goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
                timeout = input.timeout,
              ),
            ),
        ),
      )
    return when (opened) {
      is WorkflowOpenResult.Ok -> opened.workflowId
      is WorkflowOpenResult.Error -> onOpenFailure(opened)
    }
  }

  private fun routeScope(goalChild: Boolean): FeatureTaskRouteScope =
    if (goalChild) FeatureTaskRouteScope.GOAL_CHILD else FeatureTaskRouteScope.STANDALONE
}
