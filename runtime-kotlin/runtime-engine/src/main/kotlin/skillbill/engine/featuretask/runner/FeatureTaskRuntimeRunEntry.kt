package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchAssignmentFactory
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.lifecycle.execution.expectedFeatureTaskExecutionIdentity
import skillbill.engine.featuretask.lifecycle.execution.governedFeatureTaskSpecPath
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.model.skeleton.boundedSafeModelIdentity
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
  private val strategies: PhaseStrategyLookup,
  private val agentRunLauncher: AgentRunLauncher,
  private val diagnostics: RuntimeDiagnostics,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
) {
  fun run(
    input: FeatureTaskRuntimeRunInput,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
  ): FeatureTaskRuntimeRunReport {
    val goalContinuation = input.goalContinuation
    val scope = routeScope(goalContinuation != null)
    val workflowId = input.explicitWorkflowId ?: open(input, scope, onOpenFailure)
    statusPublisherFactory.registerWorkflow(
      repoRoot = input.repoRoot,
      issueKey = input.issueKey,
      workflowId = workflowId,
    )
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
      if (input.explicitWorkflowId != null) {
        warnIfResumeAssignmentDiffers(input, admittedExecution)
      }
      val sourceInvariants = runInvariantsSource.read(specPath).requireInvariants()
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
          skeletonDefinition = input.definition,
          protectedSpecSha256 = input.protectedSpecSha256,
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
    val definition = input.definition ?: SkeletonDefinition.forRun(goalContinuation != null)
    val reviewMode =
      goalContinuation?.codeReviewMode ?: input.requestedCodeReviewMode
        ?: runInvariantsSource.read(specPath).requireInvariants().codeReviewMode
    val qualityGate = goalContinuation?.qualityGateSelection
    val facts =
      PhaseStrategySelectionFacts(
        definition,
        setOfNotNull(reviewMode, executionPlans.creationQualityGate(input.repoRoot, qualityGate)),
      )
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
          initialArtifacts = input.openArtifacts,
          executionPlan =
            executionPlans.resolveCreation(
              FeatureTaskRuntimeExecutionPlanCreationRequest(
                repoRoot = input.repoRoot,
                definition = definition,
                reviewMode = reviewMode,
                qualityGate = qualityGate,
                validationDepth = goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
                timeout = input.timeout,
                stepLaunchAssignments =
                  FeatureTaskRuntimeStepLaunchAssignmentFactory.resolve(
                    launcher = agentRunLauncher,
                    lookup = strategies,
                    facts = facts,
                    inputs =
                      StepLaunchAssignmentInputs(
                        invokedAgentId = input.invokedAgentId,
                        agentAssignment = input.agentAssignment,
                        modelAssignment = input.modelAssignment,
                      ),
                  ),
              ),
            ),
        ),
      )
    return when (opened) {
      is WorkflowOpenResult.Ok -> opened.workflowId
      is WorkflowOpenResult.Error -> onOpenFailure(opened)
    }
  }

  private fun warnIfResumeAssignmentDiffers(
    input: FeatureTaskRuntimeRunInput,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
  ) {
    val goalContinuation = input.goalContinuation
    val definition = input.definition ?: SkeletonDefinition.forRun(goalContinuation != null)
    val reviewMode = admittedExecution.reviewMode ?: input.requestedCodeReviewMode
    val qualityGate = admittedExecution.plan.qualityGateSelection
    val facts = PhaseStrategySelectionFacts(definition, setOfNotNull(reviewMode, qualityGate))
    val resolved =
      FeatureTaskRuntimeStepLaunchAssignmentFactory.resolve(
        launcher = agentRunLauncher,
        lookup = strategies,
        facts = facts,
        inputs =
          StepLaunchAssignmentInputs(
            invokedAgentId = input.invokedAgentId,
            agentAssignment = input.agentAssignment,
            modelAssignment = input.modelAssignment,
            environmentKind =
              when (admittedExecution.identity.routeScope) {
                FeatureTaskRouteScope.GOAL_CHILD -> LaunchEnvironmentKind.GOVERNED_CHILD
                FeatureTaskRouteScope.STANDALONE -> LaunchEnvironmentKind.INHERITED
              },
          ),
      )
    val expected = boundedAssignmentIdentity(admittedExecution.plan.stepLaunchAssignments)
    val current = boundedAssignmentIdentity(resolved)
    if (expected != current) {
      diagnostics.warning(
        "seam=execution_plan_resume value_expected=$expected value_used=$expected current=$current",
      )
    }
  }

  private fun boundedAssignmentIdentity(assignments: Map<String, StepLaunchAssignment>): String =
    assignments.entries.sortedBy { it.key }.joinToString(";") { (stepId, assignment) ->
      val model = assignment.launch.effectiveModel?.let(::boundedSafeModelIdentity) ?: "none"
      "$stepId=$model/${assignment.profile.wireValue}"
    }.ifBlank { "none" }

  private fun routeScope(goalChild: Boolean): FeatureTaskRouteScope =
    if (goalChild) FeatureTaskRouteScope.GOAL_CHILD else FeatureTaskRouteScope.STANDALONE
}

private fun FeatureTaskRuntimeRunInvariantsRead.requireInvariants() =
  when (this) {
    is FeatureTaskRuntimeRunInvariantsRead.Read -> invariants
    is FeatureTaskRuntimeRunInvariantsRead.Rejected -> throw IllegalArgumentException(reason)
  }
