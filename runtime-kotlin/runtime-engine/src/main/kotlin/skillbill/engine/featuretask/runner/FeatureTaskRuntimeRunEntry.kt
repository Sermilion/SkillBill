package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeResumeLaunchAssignment
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchAssignmentFactory
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.lifecycle.execution.expectedFeatureTaskExecutionIdentity
import skillbill.engine.featuretask.lifecycle.execution.governedFeatureTaskSpecPath
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairPersistence
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.model.skeleton.boundedSafeModelIdentity
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

private const val INTAKE_PASS_CRITERION = "Intake writes or keeps the parent spec before run invariants are read."

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
  private val repairPersistence: AcceptanceCriterionRepairPersistence,
) {
  private companion object {
    const val MAX_DIAGNOSTIC_ID = 128
  }

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
    ) { admittedExecution, acquiredWorker ->
      val requestedQaSelection = input.qaSelection ?: goalContinuation?.qaSelection
      if (requestedQaSelection != null && requestedQaSelection != admittedExecution.plan.qaSelection) {
        return@runOwned changedQaSelectionRefusal(input, acquiredWorker, specPath, admittedExecution)
      }
      if (input.explicitWorkflowId != null) {
        warnIfResumeAssignmentDiffers(input, admittedExecution)
      }
      val definition = resolveRunDefinition(input.definition, goalContinuation != null)
      if (PhaseSlot.INTAKE in definition.slots && !intakeCompleted(workflowId)) {
        val intakeReport =
          runner.run(
            runRequest(input, workflowId, admittedExecution, intakePassInvariants(input, specPath, admittedExecution))
              .copy(stopAfterIntake = true),
          )
        if (!intakeCompleted(workflowId)) return@runOwned intakeReport
      }
      val sourceRead = runInvariantsSource.read(specPath)
      val sourceInvariants = sourceRead.requireInvariants()
      val repairState = inspectRepairState(input, acquiredWorker, sourceRead, admittedExecution)
      if (repairState?.historicalAttributionUnavailable() == true) {
        return@runOwned historicalRepairRefusal(input, acquiredWorker, sourceInvariants)
      }
      runner.run(
        runRequest(
          input,
          workflowId,
          admittedExecution,
          sourceInvariants.copy(
            codeReviewMode = admittedExecution.reviewMode ?: sourceInvariants.codeReviewMode,
            agentAddonSelection = input.agentAddonSelection.persisted,
          ),
        ).copy(acceptanceCriterionRepair = repairState),
        acquiredWorker,
      )
    }
  }

  private fun changedQaSelectionRefusal(
    input: FeatureTaskRuntimeRunInput,
    worker: AcquiredFeatureTaskRuntimeWorker,
    specPath: Path,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
  ): FeatureTaskRuntimeRunReport.Blocked =
    FeatureTaskRuntimeRunReport.Blocked(
      issueKey = input.issueKey,
      workflowId = worker.workflowId,
      featureSize = runInvariantsSource.read(specPath).requireInvariants().featureSize.name,
      lastIncompletePhase = worker.currentStepId,
      blockedReason =
        "Cannot change QA selection on resume; the recorded selection is " +
          "'${admittedExecution.plan.qaSelection?.wireValue ?: "not composed"}'.",
      completedPhaseIds = emptyList(),
      resolvedBranch = null,
    )

  private fun runRequest(
    input: FeatureTaskRuntimeRunInput,
    workflowId: String,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
    invariants: FeatureTaskRuntimeRunInvariants,
  ): FeatureTaskRuntimeRunRequest =
    FeatureTaskRuntimeRunRequest(
      issueKey = input.issueKey,
      workflowId = workflowId,
      admittedExecution = admittedExecution,
      sessionId = "${FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultSessionPrefix}-$workflowId",
      runInvariants = invariants,
      invokedAgentId = input.invokedAgentId,
      agentAssignment = input.agentAssignment,
      modelAssignment = input.modelAssignment,
      compactionSettings = input.compactionSettings,
      environment = input.environment,
      repoRoot = input.repoRoot,
      timeout = input.timeout,
      requestedCodeReviewMode = input.requestedCodeReviewMode,
      goalContinuation = input.goalContinuation,
      operatorDecision = input.operatorDecision,
      agentAddonSelection = input.agentAddonSelection,
      eventSink = input.eventSink,
      skeletonDefinition = input.definition,
      protectedSpecSha256 = input.protectedSpecSha256,
      planIntake = input.planIntake,
    )

  private fun intakeCompleted(workflowId: String): Boolean = runner.intakeCompleted(workflowId)

  private fun intakePassInvariants(
    input: FeatureTaskRuntimeRunInput,
    specPath: Path,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
  ): FeatureTaskRuntimeRunInvariants =
    FeatureTaskRuntimeRunInvariants(
      specReference = specPath.toString(),
      acceptanceCriteria = listOf(INTAKE_PASS_CRITERION),
      mandatesAndOverrides = emptyList(),
      codeReviewMode = admittedExecution.reviewMode ?: CodeReviewExecutionMode.AUTO,
      agentAddonSelection = input.agentAddonSelection.persisted,
    )

  private fun inspectRepairState(
    input: FeatureTaskRuntimeRunInput,
    worker: AcquiredFeatureTaskRuntimeWorker,
    sourceRead: FeatureTaskRuntimeRunInvariantsRead,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
  ): AcceptanceCriterionRepairRunState? {
    val auditStepIds =
      admittedExecution.plan.dispatchStrategyByStep
        .filterValues { it.slot == PhaseSlot.AUDIT }
        .keys
    if (auditStepIds.isEmpty()) return null
    val criteria = sourceRead.requireInvariants().acceptanceCriteria
    val specDigest =
      (sourceRead as? FeatureTaskRuntimeRunInvariantsRead.Read)?.sourceDigest ?: input.protectedSpecSha256
    val hydration = repairPersistence.inspectExisting(worker, criteria, specDigest)
    val binding =
      specDigest?.let {
        AcceptanceCriterionRepairBinding(
          catalogId = sha256Hex(JsonCodec.valueToJsonString(criteria)),
          executionId = worker.workflowId,
          specDigest = it,
          authoredCriteria = criteria,
          auditStepIds = auditStepIds,
        )
      }
    if (hydration is AcceptanceCriterionRepairHydration.Present) {
      repairPersistence.recoverProjection(input.repoRoot, worker.workflowId, requireNotNull(binding))
    }
    return AcceptanceCriterionRepairRunState(hydration, binding)
  }

  private fun historicalRepairRefusal(
    input: FeatureTaskRuntimeRunInput,
    worker: AcquiredFeatureTaskRuntimeWorker,
    sourceInvariants: FeatureTaskRuntimeRunInvariants,
  ): FeatureTaskRuntimeRunReport.Blocked {
    val reason =
      "Serial acceptance repair cannot start because this workflow has earlier audit or repair activity, " +
        "but no per-criterion attribution record. The aggregate attempts and saved prose remain intact. " +
        "An operator must decide separately whether prospective per-criterion accounting may begin; " +
        "a generic retry does not make that decision."
    diagnostics.warning(
      "seam=serial_acceptance_repair record_kind=refusal workflow=${worker.workflowId.take(MAX_DIAGNOSTIC_ID)} " +
        "value_expected=criterion_attribution value_used=unavailable",
    )
    return FeatureTaskRuntimeRunReport.Blocked(
      issueKey = input.issueKey,
      workflowId = worker.workflowId,
      featureSize = sourceInvariants.featureSize.name,
      lastIncompletePhase = worker.currentStepId,
      blockedReason = reason,
      completedPhaseIds = emptyList(),
      resolvedBranch = null,
    )
  }

  private fun open(
    input: FeatureTaskRuntimeRunInput,
    scope: FeatureTaskRouteScope,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
  ): String {
    val goalContinuation = input.goalContinuation
    val specPath = Path.of(input.specPath)
    val definition = resolveRunDefinition(input.definition, goalContinuation != null)
    val reviewMode =
      goalContinuation?.codeReviewMode ?: input.requestedCodeReviewMode
        ?: if (PhaseSlot.INTAKE in definition.slots) {
          CodeReviewExecutionMode.AUTO
        } else {
          runInvariantsSource.read(specPath).requireInvariants().codeReviewMode
        }
    val qualityGate = goalContinuation?.qualityGateSelection
    val qaSelection = input.qaSelection ?: goalContinuation?.qaSelection ?: FeatureTaskRuntimeQaSelection.AUTO
    val facts =
      PhaseStrategySelectionFacts(
        definition,
        setOfNotNull(
          reviewMode,
          executionPlans.creationQualityGate(input.repoRoot, qualityGate),
          qaSelection,
        ),
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
                qaSelection = qaSelection,
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
    val definition = resolveRunDefinition(input.definition, goalContinuation != null)
    val reviewMode = admittedExecution.reviewMode ?: input.requestedCodeReviewMode
    val qualityGate = admittedExecution.plan.qualityGateSelection
    val facts =
      PhaseStrategySelectionFacts(
        definition,
        setOfNotNull(reviewMode, qualityGate, admittedExecution.plan.qaSelection),
      )
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
    val used =
      boundedAssignmentIdentity(
        FeatureTaskRuntimeResumeLaunchAssignment.usedAssignments(
          admittedExecution.plan.stepLaunchAssignments,
          resolved,
        ) { stepId ->
          FeatureTaskRuntimeAgentResolver.resolve(stepId, input.agentAssignment, input.invokedAgentId).resolvedAgentId
        },
      )
    if (expected != current) {
      diagnostics.warning(
        "seam=execution_plan_resume value_expected=$expected value_used=$used current=$current",
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
