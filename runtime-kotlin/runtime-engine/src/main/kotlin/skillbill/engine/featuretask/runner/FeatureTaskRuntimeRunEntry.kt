package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.incident.BlockIncidentEntryReconciler
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeResumeLaunchAssignment
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskExecutionIdentityResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchResolver
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairVersionRecoveryResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.persist.acceptance.AcceptanceCriterionRepairRunPreparation
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.error.shellcontent.invalidInteractionScenarioCatalog
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.spec.InteractionScenarioSectionParser
import skillbill.review.spec.model.InteractionScenario
import skillbill.review.spec.model.InteractionScenarioParseResult
import skillbill.review.spec.model.InteractionScenarioSpecContext
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.model.skeleton.boundedSafeModelIdentity
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

private sealed interface RepairInspection {
  data class Ready(val runState: AcceptanceCriterionRepairRunState?) : RepairInspection

  data class Refused(val reason: AcceptanceCriterionRepairRefusal) : RepairInspection
}

private const val INTAKE_PASS_CRITERION = "Intake writes or keeps the parent spec before run invariants are read."

@Inject
class FeatureTaskRuntimeRunEntry(
  private val workflowService: WorkflowService,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  private val workerCoordinator: FeatureTaskRuntimeWorkerCoordinator,
  private val runInvariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val runner: FeatureTaskRuntimeRunner,
  private val identities: FeatureTaskExecutionIdentityResolver,
  private val stepLaunches: FeatureTaskRuntimeStepLaunchResolver,
  private val diagnostics: RuntimeDiagnostics,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
  private val repairPreparation: AcceptanceCriterionRepairRunPreparation,
  private val blockIncidentReconciler: BlockIncidentEntryReconciler,
) {
  private companion object {
    const val MAX_DIAGNOSTIC_ID = 128
  }

  fun run(
    input: FeatureTaskRuntimeRunInput,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
  ): FeatureTaskRuntimeRunReport =
    blockIncidentReconciler.guardEntry({ it is FeatureTaskRuntimeRunReport.Blocked }) {
      runAfterReconciliation(input, onOpenFailure)
    }

  private fun runAfterReconciliation(
    input: FeatureTaskRuntimeRunInput,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
  ): FeatureTaskRuntimeRunReport {
    val request = planningResumeInput(input)
    val goalContinuation = request.goalContinuation
    val scope = routeScope(goalContinuation != null)
    val workflowId = request.explicitWorkflowId ?: open(request, scope, onOpenFailure)
    statusPublisherFactory.registerWorkflow(
      repoRoot = request.repoRoot,
      issueKey = request.issueKey,
      workflowId = workflowId,
    )
    val specPath = Path.of(request.specPath)
    val effectiveInputs =
      executionPlans.resolveInputs(
        request.repoRoot,
        goalContinuation?.qualityGateSelection,
        goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
        request.timeout,
        workflowId,
      )
    val identity = identities.expected(workflowId, request.issueKey, request.repoRoot, specPath, scope)
    return workerCoordinator.runOwned(
      workflowId,
      effectiveInputs,
      identity,
      requestedReviewSelection =
        (goalContinuation?.codeReviewMode ?: request.requestedCodeReviewMode)
          ?.let { RuntimeReviewSelection.valueOf(it.name) },
    ) { admittedExecution, acquiredWorker ->
      val recovery = repairPreparation.recoverSupersededVersion(acquiredWorker)
      if (recovery is AcceptanceCriterionRepairVersionRecoveryResult.Refused) {
        return@runOwned versionRecoveryRefusal(request, acquiredWorker, specPath, recovery)
      }
      val requestedQaSelection = if (goalContinuation != null) goalContinuation.qaSelection else request.qaSelection
      if (requestedQaSelection != null && requestedQaSelection != admittedExecution.plan.qaSelection) {
        return@runOwned changedQaSelectionRefusal(request, acquiredWorker, specPath, admittedExecution)
      }
      if (request.explicitWorkflowId != null) {
        warnIfResumeAssignmentDiffers(request, admittedExecution)
      }
      val definition = admittedDefinition(request, admittedExecution)
      if (PhaseSlot.INTAKE in definition.slots && !intakeCompleted(workflowId)) {
        val invariants = intakePassInvariants(request, specPath, admittedExecution)
        val intakeReport =
          runner.run(runRequest(request, workflowId, admittedExecution, invariants).copy(stopAfterIntake = true))
        if (!intakeCompleted(workflowId)) return@runOwned intakeReport
      }
      runWithRepairState(request, specPath, admittedExecution, acquiredWorker)
    }
  }

  private fun admittedDefinition(
    request: FeatureTaskRuntimeRunInput,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): SkeletonDefinition =
    if (request.goalContinuation != null) {
      SkeletonDefinition.byIdAndRevision(admitted.plan.definitionId, admitted.plan.definitionSemanticRevision)
    } else {
      resolveRunDefinition(request.definition, goalContinuation = false)
    }

  private fun runWithRepairState(
    request: FeatureTaskRuntimeRunInput,
    specPath: Path,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
    acquiredWorker: AcquiredFeatureTaskRuntimeWorker,
  ): FeatureTaskRuntimeRunReport {
    val sourceRead = runInvariantsSource.read(specPath)
    val sourceInvariants = sourceRead.requireInvariants()
    val repairState =
      when (val inspection = inspectRepairState(request, acquiredWorker, sourceRead, admittedExecution)) {
        is RepairInspection.Refused ->
          return tracePreparationRefusal(request, acquiredWorker, sourceInvariants, inspection.reason)
        is RepairInspection.Ready -> inspection.runState
      }
    if (repairState?.historicalAttributionUnavailable() == true) {
      return historicalRepairRefusal(request, acquiredWorker, sourceInvariants)
    }
    return runner.run(
      runRequest(
        request,
        acquiredWorker.workflowId,
        admittedExecution,
        sourceInvariants.copy(
          codeReviewMode = admittedExecution.reviewMode ?: sourceInvariants.codeReviewMode,
          agentAddonSelection = request.agentAddonSelection.persisted,
        ),
      ).copy(acceptanceCriterionRepair = repairState),
      acquiredWorker,
    )
  }

  private fun planningResumeInput(input: FeatureTaskRuntimeRunInput): FeatureTaskRuntimeRunInput {
    val workflowId = input.explicitWorkflowId ?: return input
    if (!input.inheritRecordedPlanningTimeout) return input
    return input.copy(timeout = executionPlans.planningResumeTimeout(workflowId, input.timeout))
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

  private fun versionRecoveryRefusal(
    input: FeatureTaskRuntimeRunInput,
    worker: AcquiredFeatureTaskRuntimeWorker,
    specPath: Path,
    refusal: AcceptanceCriterionRepairVersionRecoveryResult.Refused,
  ): FeatureTaskRuntimeRunReport.Blocked =
    FeatureTaskRuntimeRunReport.Blocked(
      issueKey = input.issueKey,
      workflowId = worker.workflowId,
      featureSize = runInvariantsSource.read(specPath).requireInvariants().featureSize.name,
      lastIncompletePhase = worker.currentStepId,
      blockedReason =
        "Cannot regenerate the stored acceptance repair state at the current contract version; " +
          "recovery was refused (${refusal.reason.wireValue}). Retry the run once the workflow is quiescent.",
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
      activityParentWorkflowId = input.activityParentWorkflowId,
      operatorDecision = input.operatorDecision,
      agentAddonSelection = input.agentAddonSelection,
      eventSink = input.eventSink,
      skeletonDefinition =
        SkeletonDefinition.byIdAndRevision(
          admittedExecution.plan.definitionId,
          admittedExecution.plan.definitionSemanticRevision,
        ),
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
  ): RepairInspection {
    val auditStepIds =
      admittedExecution.plan.dispatchStrategyByStep
        .filterValues { it.slot == PhaseSlot.AUDIT }
        .keys
    if (auditStepIds.isEmpty()) return RepairInspection.Ready(null)
    val criteria = sourceRead.requireInvariants().acceptanceCriteria
    val read = sourceRead as? FeatureTaskRuntimeRunInvariantsRead.Read
    val specDigest = read?.sourceDigest ?: input.protectedSpecSha256
    val sourceText = read?.sourceText
    val binding =
      specDigest?.let {
        AcceptanceCriterionRepairBinding(
          catalogId = sha256Hex(JsonCodec.valueToJsonString(criteria)),
          executionId = worker.workflowId,
          specDigest = it,
          authoredCriteria = criteria,
          auditStepIds = auditStepIds,
          interactionScenarios = interactionScenarios(input.specPath, it, sourceText),
        )
      }
    return when (val prepared = repairPreparation.prepare(worker, criteria, specDigest, binding, input.repoRoot)) {
      is AcceptanceCriterionRepairRunPreparation.Prepared.Ready -> RepairInspection.Ready(prepared.runState)
      is AcceptanceCriterionRepairRunPreparation.Prepared.Refused -> RepairInspection.Refused(prepared.reason)
    }
  }

  private fun tracePreparationRefusal(
    input: FeatureTaskRuntimeRunInput,
    worker: AcquiredFeatureTaskRuntimeWorker,
    sourceInvariants: FeatureTaskRuntimeRunInvariants,
    reason: AcceptanceCriterionRepairRefusal,
  ): FeatureTaskRuntimeRunReport.Blocked =
    FeatureTaskRuntimeRunReport.Blocked(
      issueKey = input.issueKey,
      workflowId = worker.workflowId,
      featureSize = sourceInvariants.featureSize.name,
      lastIncompletePhase = worker.currentStepId,
      blockedReason =
        "Cannot prepare the interaction trace for the bound spec digest; preparation was refused " +
          "(${reason.wireValue}). Retry the run once the workflow is quiescent or restore the admitted spec.",
      completedPhaseIds = emptyList(),
      resolvedBranch = null,
    )

  private fun interactionScenarios(
    specPath: String,
    specDigest: String,
    specText: String?,
  ): List<InteractionScenario> {
    val text =
      specText
        ?: throw invalidInteractionScenarioCatalog(specPath, "selected spec text for the bound digest is unavailable")
    return when (
      val result = InteractionScenarioSectionParser.parse(text, specDigest, InteractionScenarioSpecContext.Subtask)
    ) {
      is InteractionScenarioParseResult.Parsed -> result.catalog.scenarios
      is InteractionScenarioParseResult.Rejected ->
        throw invalidInteractionScenarioCatalog(
          specPath,
          result.issues.first().let { "${it.fieldPath}: ${it.reason}" },
        )
    }
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

  private fun creationReviewMode(
    input: FeatureTaskRuntimeRunInput,
    definition: SkeletonDefinition,
  ): CodeReviewExecutionMode =
    input.goalContinuation?.codeReviewMode ?: input.requestedCodeReviewMode
      ?: if (PhaseSlot.INTAKE in definition.slots) {
        CodeReviewExecutionMode.AUTO
      } else {
        runInvariantsSource.read(Path.of(input.specPath)).requireInvariants().codeReviewMode
      }

  private fun creationQaSelection(input: FeatureTaskRuntimeRunInput): FeatureTaskRuntimeQaSelection? =
    if (input.goalContinuation != null) {
      input.goalContinuation.qaSelection
    } else {
      input.qaSelection ?: FeatureTaskRuntimeQaSelection.AUTO
    }

  private fun open(
    input: FeatureTaskRuntimeRunInput,
    scope: FeatureTaskRouteScope,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
  ): String {
    val goalContinuation = input.goalContinuation
    val specPath = Path.of(input.specPath)
    val definition = resolveRunDefinition(input.definition, goalContinuation != null)
    if (input.explicitWorkflowId == null && definition == SkeletonDefinition.GOAL_CHILD_REVISION_5 &&
      goalContinuation?.parentWorkflowId.isNullOrBlank()
    ) {
      onOpenFailure(
        WorkflowOpenResult.Error(
          "",
          "New goal-child runs require a usable parent workflow id before admission.",
        ),
      )
    }
    val reviewMode = creationReviewMode(input, definition)
    val qaSelection = creationQaSelection(input)
    val facts =
      PhaseStrategySelectionFacts(
        definition,
        setOfNotNull(
          reviewMode,
          executionPlans.creationQualityGate(input.repoRoot, goalContinuation?.qualityGateSelection),
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
          repositoryIdentity = identities.repositoryIdentity(input.repoRoot),
          governedSpecPath = identities.governedSpecPath("unassigned", input.repoRoot, specPath),
          routeScope = scope,
          initialArtifacts = input.openArtifacts,
          executionPlan =
            executionPlans.resolveCreation(
              FeatureTaskRuntimeExecutionPlanCreationRequest(
                repoRoot = input.repoRoot,
                definition = definition,
                reviewMode = reviewMode,
                qualityGate = goalContinuation?.qualityGateSelection,
                qaSelection = qaSelection,
                validationDepth = goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
                timeout = input.timeout,
                stepLaunchAssignments =
                  stepLaunches.resolve(
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
    val definition =
      if (goalContinuation != null) {
        SkeletonDefinition.byIdAndRevision(
          admittedExecution.plan.definitionId,
          admittedExecution.plan.definitionSemanticRevision,
        )
      } else {
        resolveRunDefinition(input.definition, goalContinuation = false)
      }
    val reviewMode = admittedExecution.reviewMode ?: input.requestedCodeReviewMode
    val qualityGate = admittedExecution.plan.qualityGateSelection
    val facts =
      PhaseStrategySelectionFacts(
        definition,
        setOfNotNull(reviewMode, qualityGate, admittedExecution.plan.qaSelection),
      )
    val resolved =
      stepLaunches.resolve(
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
          FeatureTaskRuntimeAgentResolver.resolve(
            stepId,
            input.agentAssignment,
            input.invokedAgentId,
          ).resolvedAgentId
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
