package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.service.WorkflowService
import skillbill.application.workflow.service.openFeatureTask
import skillbill.config.model.FrozenWorkflowAgentOptionCheck
import skillbill.config.model.ResolvedWorkflowAgentAssignments
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.config.model.checkFrozenWorkflowAgentOptions
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.incident.BlockIncidentEntryReconciler
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeResumeLaunchAssignment
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeWorkerCoordinator
import skillbill.engine.featuretask.lifecycle.core.WorkerAdmissionSelection
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
import skillbill.engine.featuretask.phaserun.WorkflowStatusLaunch
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.error.shellcontent.invalidInteractionScenarioCatalog
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.spec.InteractionScenarioSectionParser
import skillbill.review.spec.model.InteractionScenario
import skillbill.review.spec.model.InteractionScenarioParseResult
import skillbill.review.spec.model.InteractionScenarioSpecContext
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
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
  data class Ready(
    val runState: AcceptanceCriterionRepairRunState?,
  ) : RepairInspection

  data class Refused(
    val reason: AcceptanceCriterionRepairRefusal,
  ) : RepairInspection
}

private const val INTAKE_PASS_CRITERION = "Intake writes or keeps the parent spec before run invariants are read."

@Inject
public class FeatureTaskRuntimeRunEntry(
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

  public fun run(
    input: FeatureTaskRuntimeRunInput,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
    outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
    captureStreamOutput: Boolean = false,
    afterStepSettlement: (() -> Unit)? = null,
  ): FeatureTaskRuntimeRunReport =
    blockIncidentReconciler.guardEntry({ it is FeatureTaskRuntimeRunReport.Blocked }) {
      runAfterReconciliation(input, onOpenFailure, outputSink, captureStreamOutput, afterStepSettlement)
    }

  private fun runAfterReconciliation(
    input: FeatureTaskRuntimeRunInput,
    onOpenFailure: (WorkflowOpenResult.Error) -> Nothing,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
    afterStepSettlement: (() -> Unit)?,
  ): FeatureTaskRuntimeRunReport {
    val request = planningResumeInput(executionPlans.prepareRoutingContext(input))
    val goalContinuation = request.goalContinuation
    val scope = routeScope(goalContinuation != null)
    val workflowId = request.explicitWorkflowId ?: open(request, scope, onOpenFailure)
    val specPath = Path.of(request.specPath)
    identityRefusal(request, workflowId, scope)?.let { return it }
    val acceptedRequest =
      request.copy(
        openCodeIdentity = statusPublisherFactory.retainedOpenCodeIdentity(workflowId),
      )
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
      selection =
        WorkerAdmissionSelection(
          requestedReviewSelection =
            (goalContinuation?.codeReviewMode ?: request.requestedCodeReviewMode)
              ?.let { RuntimeReviewSelection.valueOf(it.name) },
          authorizedGoalParentWorkflowId = request.activityParentWorkflowId,
          invokedAgentId = request.invokedAgentId,
        ),
    ) { admittedExecution, acquiredWorker ->
      providerOptionRefusal(request, admittedExecution, acquiredWorker)?.let { return@runOwned it }
      verifyAdmittedAddons(request, admittedExecution)
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
      intakeIfNeeded(acceptedRequest, admittedExecution, outputSink, captureStreamOutput, afterStepSettlement)
        ?.let { return@runOwned it }
      runWithRepairState(
        acceptedRequest,
        acquiredWorker,
        outputSink,
        captureStreamOutput,
        afterStepSettlement,
      )
    }
  }

  private fun providerOptionRefusal(
    request: FeatureTaskRuntimeRunInput,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
    acquiredWorker: AcquiredFeatureTaskRuntimeWorker,
  ): FeatureTaskRuntimeRunReport.Blocked? {
    when (
      val optionCheck =
        admittedExecution.workflowAgentAssignments?.let {
          checkFrozenWorkflowAgentOptions(
            it,
            request.agentAssignment.override,
            request.agentAssignment.perPhaseAgentIds,
          )
        } ?: FrozenWorkflowAgentOptionCheck.Accepted
    ) {
      FrozenWorkflowAgentOptionCheck.Accepted -> {
        return null
      }

      is FrozenWorkflowAgentOptionCheck.Refused -> {
        return frozenAssignmentRefusal(request, acquiredWorker, optionCheck)
      }
    }
  }

  private fun intakeIfNeeded(
    request: FeatureTaskRuntimeRunInput,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
    afterStepSettlement: (() -> Unit)?,
  ): FeatureTaskRuntimeRunReport? {
    val workflowId = admittedExecution.identity.workflowId
    val specPath = Path.of(request.specPath)
    val definition = admittedDefinition(request, admittedExecution)
    if (PhaseSlot.INTAKE in definition.slots && !intakeCompleted(workflowId)) {
      val invariants = intakePassInvariants(request, specPath, admittedExecution)
      val intakeReport =
        runner.run(
          runRequest(request, workflowId, admittedExecution, invariants).copy(stopAfterIntake = true),
          outputSink = outputSink,
          captureStreamOutput = captureStreamOutput,
          afterStepSettlement = afterStepSettlement,
        )
      if (!intakeCompleted(workflowId)) return intakeReport
    }
    return null
  }

  private fun verifyAdmittedAddons(
    request: FeatureTaskRuntimeRunInput,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
  ) {
    val launchDefinition = admittedDefinition(request, admittedExecution)
    val legacyPausedStepAssignments =
      if (admittedExecution.workflowAgentAssignments?.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY) {
        val pausedStepIds =
          executionPlans
            .resumeEvidenceIfPlanExists(admittedExecution.identity.workflowId)
            ?.pausedStepIds
            .orEmpty()
        admittedExecution.plan.stepLaunchAssignments
          .filterKeys { it in pausedStepIds }
          .mapValues { (_, assignment) -> assignment.agentId }
      } else {
        emptyMap()
      }
    stepLaunches.verifyAddons(
      PhaseStrategySelectionFacts(
        launchDefinition,
        setOfNotNull(
          admittedExecution.reviewMode ?: request.requestedCodeReviewMode,
          admittedExecution.plan.qualityGateSelection,
          admittedExecution.plan.qaSelection,
        ),
      ),
      StepLaunchAssignmentInputs(
        invokedAgentId = request.invokedAgentId,
        agentAssignment = request.agentAssignment,
        modelAssignment = request.modelAssignment,
        environmentKind =
          if (admittedExecution.identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD) {
            LaunchEnvironmentKind.GOVERNED_CHILD
          } else {
            LaunchEnvironmentKind.INHERITED
          },
        workflowAgentAssignments = admittedExecution.workflowAgentAssignments,
        agentAddonSelection = request.agentAddonSelection.persisted,
        legacyPausedStepAssignments = legacyPausedStepAssignments,
      ),
    )
  }

  private fun frozenAssignmentRefusal(
    request: FeatureTaskRuntimeRunInput,
    worker: AcquiredFeatureTaskRuntimeWorker,
    refusal: FrozenWorkflowAgentOptionCheck.Refused,
  ): FeatureTaskRuntimeRunReport.Blocked {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignments value_expected=${refusal.retainedAgentId.take(MAX_DIAGNOSTIC_ID)} " +
        "phase=${refusal.phaseId.take(MAX_DIAGNOSTIC_ID)} " +
        "provider=${refusal.requestedAgentId.take(MAX_DIAGNOSTIC_ID)} reason=explicit_provider_refused " +
        "classification=${refusal.reason.name.lowercase()}",
    )
    return FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = worker.workflowId,
      featureSize =
        runInvariantsSource
          .read(Path.of(request.specPath))
          .requireInvariants()
          .featureSize.name,
      lastIncompletePhase = refusal.phaseId,
      blockedReason =
        if (refusal.reason == FrozenWorkflowAgentOptionCheck.Reason.UNKNOWN_PROVIDER) {
          "Provider '${refusal.requestedAgentId}' is unsupported for phase '${refusal.phaseId}'. " +
            "Select a supported provider id and retry."
        } else {
          "Phase '${refusal.phaseId}' is frozen to provider '${refusal.retainedAgentId}', but the explicit option " +
            "requested '${refusal.requestedAgentId}'. Remove the conflicting option or use the recorded provider. " +
            "Frozen assignments cannot be retargeted."
        },
      completedPhaseIds = emptyList(),
      resolvedBranch = request.goalContinuation?.goalBranch,
    )
  }

  private fun identityRefusal(
    request: FeatureTaskRuntimeRunInput,
    workflowId: String,
    scope: FeatureTaskRouteScope,
  ): FeatureTaskRuntimeRunReport.Blocked? {
    val launch =
      WorkflowStatusLaunch(
        goalScoped = scope == FeatureTaskRouteScope.GOAL_CHILD || request.definition == SkeletonDefinition.PLAN,
        agentId = if (openCodeAssigned(request)) SupportedAgent.OPENCODE.wireValue else request.invokedAgentId,
      )
    val refusal =
      statusPublisherFactory.registerWorkflow(request.repoRoot, request.issueKey, workflowId, launch)
        ?: return null
    return FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = workflowId,
      featureSize =
        runInvariantsSource
          .read(Path.of(request.specPath))
          .requireInvariants()
          .featureSize.name,
      lastIncompletePhase = "resume_identity",
      blockedReason = refusal,
      completedPhaseIds = emptyList(),
      resolvedBranch = request.goalContinuation?.goalBranch,
    )
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
    acquiredWorker: AcquiredFeatureTaskRuntimeWorker,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
    afterStepSettlement: (() -> Unit)?,
  ): FeatureTaskRuntimeRunReport {
    val specPath = Path.of(request.specPath)
    val admittedExecution = acquiredWorker.admittedExecution
    val sourceRead = runInvariantsSource.read(specPath)
    val sourceInvariants = sourceRead.requireInvariants()
    val repairState =
      when (val inspection = inspectRepairState(request, acquiredWorker, sourceRead, admittedExecution)) {
        is RepairInspection.Refused -> {
          return tracePreparationRefusal(request, acquiredWorker, sourceInvariants, inspection.reason)
        }

        is RepairInspection.Ready -> {
          inspection.runState
        }
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
      outputSink,
      captureStreamOutput,
      afterStepSettlement,
    )
  }

  private fun planningResumeInput(input: FeatureTaskRuntimeRunInput): FeatureTaskRuntimeRunInput {
    val workflowId = input.explicitWorkflowId ?: return input
    if (!input.inheritRecordedPlanningTimeout) return input
    return input.copy(timeout = executionPlans.planningResumeTimeout(workflowId, input.timeout))
  }

  private fun openCodeAssigned(input: FeatureTaskRuntimeRunInput): Boolean =
    input.invokedAgentId == SupportedAgent.OPENCODE.wireValue ||
      input.agentAssignment.override == SupportedAgent.OPENCODE.wireValue ||
      SupportedAgent.OPENCODE.wireValue in input.agentAssignment.perPhaseAgentIds.values

  private fun changedQaSelectionRefusal(
    input: FeatureTaskRuntimeRunInput,
    worker: AcquiredFeatureTaskRuntimeWorker,
    specPath: Path,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
  ): FeatureTaskRuntimeRunReport.Blocked =
    FeatureTaskRuntimeRunReport.Blocked(
      issueKey = input.issueKey,
      workflowId = worker.workflowId,
      featureSize =
        runInvariantsSource
          .read(specPath)
          .requireInvariants()
          .featureSize.name,
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
      featureSize =
        runInvariantsSource
          .read(specPath)
          .requireInvariants()
          .featureSize.name,
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
      openCodeIdentity = input.openCodeIdentity,
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
      planIntakeRoot = input.planIntakeRoot,
      planSpecResolution = input.planSpecResolution,
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
      is InteractionScenarioParseResult.Parsed -> {
        result.catalog.scenarios
      }

      is InteractionScenarioParseResult.Rejected -> {
        throw invalidInteractionScenarioCatalog(
          specPath,
          result.issues.first().let { "${it.fieldPath}: ${it.reason}" },
        )
      }
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
    val workflowId = input.workflowIdForCreation ?: workflowService.allocateWorkflowId(WorkflowFamilyKind.TASK_RUNTIME)
    val assignments =
      if (scope == FeatureTaskRouteScope.STANDALONE) {
        input.workflowAgentAssignments?.let(executionPlans::prepareRouting)
          ?: executionPlans.resolveAdmissionRouting(
            workflowId,
            input.invokedAgentId,
            input.agentAssignment,
            input.modelAssignment,
          )
      } else {
        null
      }
    val initialArtifacts =
      assignments?.let {
        input.openArtifacts +
          DurableWorkflowArtifactFamily.WORKFLOW_AGENT_ASSIGNMENTS.entry(
            it.canonicalBytes.toString(Charsets.UTF_8),
          )
      } ?: input.openArtifacts
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
          repositoryIdentity = identities.openingIdentity(input.repoRoot, scope, definition == SkeletonDefinition.PLAN),
          governedSpecPath = identities.governedSpecPath("unassigned", input.repoRoot, specPath),
          routeScope = scope,
          initialArtifacts = checkNotNull(WorkflowArtifactPatch.from(initialArtifacts)),
          workflowId = workflowId,
          executionPlan = creationPlan(input, facts, assignments?.assignments),
        ),
      )
    return when (opened) {
      is WorkflowOpenResult.Ok -> opened.workflowId
      is WorkflowOpenResult.Error -> onOpenFailure(opened)
    }
  }

  private fun creationPlan(
    input: FeatureTaskRuntimeRunInput,
    facts: PhaseStrategySelectionFacts,
    assignments: ResolvedWorkflowAgentAssignments?,
  ): ValidatedFeatureTaskRuntimeExecutionPlan =
    executionPlans.resolveCreation(
      FeatureTaskRuntimeExecutionPlanCreationRequest(
        repoRoot = input.repoRoot,
        definition = facts.definition,
        reviewMode = creationReviewMode(input, facts.definition),
        qualityGate = input.goalContinuation?.qualityGateSelection,
        qaSelection = creationQaSelection(input),
        validationDepth = input.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
        timeout = input.timeout,
        stepLaunchAssignments =
          stepLaunches
            .resolve(
              facts = facts,
              inputs =
                StepLaunchAssignmentInputs(
                  invokedAgentId = input.invokedAgentId,
                  agentAssignment = input.agentAssignment,
                  modelAssignment = input.modelAssignment,
                  workflowAgentAssignments = assignments,
                  agentAddonSelection = input.agentAddonSelection.persisted,
                ),
              diagnostics = diagnostics,
            ).also { assignments ->
              stepLaunches.verifyLaunchCapability(assignments, LaunchEnvironmentKind.INHERITED, diagnostics)
            },
      ),
    )

  private fun warnIfResumeAssignmentDiffers(
    input: FeatureTaskRuntimeRunInput,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
  ) {
    if (warnIfFrozenAssignmentDiffers(input, admittedExecution)) return
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
            workflowAgentAssignments = admittedExecution.workflowAgentAssignments,
            agentAddonSelection = input.agentAddonSelection.persisted,
            environmentKind =
              when (admittedExecution.identity.routeScope) {
                FeatureTaskRouteScope.GOAL_CHILD -> LaunchEnvironmentKind.GOVERNED_CHILD
                FeatureTaskRouteScope.STANDALONE -> LaunchEnvironmentKind.INHERITED
              },
          ),
        diagnostics = diagnostics,
      )
    val expected = boundedAssignmentIdentity(admittedExecution.plan.stepLaunchAssignments)
    val current = boundedAssignmentIdentity(resolved)
    val used =
      boundedAssignmentIdentity(
        FeatureTaskRuntimeResumeLaunchAssignment.usedAssignments(
          admittedExecution.plan.stepLaunchAssignments,
          resolved,
          { stepId ->
            FeatureTaskRuntimeAgentResolver
              .resolve(
                stepId,
                input.agentAssignment.copy(
                  matrix =
                    input.agentAssignment.matrix.takeIf {
                      admittedExecution.workflowAgentAssignments?.policyMode != WorkflowAgentAssignmentPolicyMode.LEGACY
                    },
                ),
                input.invokedAgentId,
              ).resolvedAgentId
          },
        ),
      )
    if (expected != current) {
      diagnostics.warning(
        "seam=execution_plan_resume value_expected=$expected value_used=$used current=$current",
      )
    }
  }

  private fun warnIfFrozenAssignmentDiffers(
    input: FeatureTaskRuntimeRunInput,
    admittedExecution: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean {
    admittedExecution.workflowAgentAssignments
      ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
      ?.let { frozen ->
        val retained =
          admittedExecution.plan.stepLaunchAssignments.keys
            .sorted()
            .joinToString(";") { phaseId ->
              "$phaseId=${frozen.phaseAssignments.getValue(phaseId).agentId.wireValue}"
            }.ifBlank { "none" }
        val current =
          admittedExecution.plan.stepLaunchAssignments.keys
            .sorted()
            .joinToString(";") { phaseId ->
              val selected =
                FeatureTaskRuntimeAgentResolver.resolve(
                  phaseId,
                  input.agentAssignment,
                  input.invokedAgentId,
                )
              "$phaseId=${selected.resolvedAgentId}"
            }.ifBlank { "none" }
        if (retained != current || input.invokedAgentId != frozen.invokedAgentId.wireValue) {
          diagnostics.warning(
            "seam=workflow_agent_assignments value_expected=$retained value_used=$retained " +
              "invoked=${frozen.invokedAgentId.wireValue} reason=current_defaults_and_wrapper_ignored",
          )
        }
        return true
      }
    return false
  }

  private fun boundedAssignmentIdentity(assignments: Map<String, StepLaunchAssignment>): String =
    assignments.entries
      .sortedBy { it.key }
      .joinToString(";") { (stepId, assignment) ->
        val model = assignment.launch.effectiveModel?.let(::boundedSafeModelIdentity) ?: "none"
        "$stepId=$model/${assignment.profile.wireValue}"
      }.ifBlank { "none" }

  private fun routeScope(goalChild: Boolean): FeatureTaskRouteScope =
    if (goalChild) FeatureTaskRouteScope.GOAL_CHILD else FeatureTaskRouteScope.STANDALONE
}

public fun FeatureTaskRuntimeRunInvariantsRead.requireInvariants(): FeatureTaskRuntimeRunInvariants =
  when (this) {
    is FeatureTaskRuntimeRunInvariantsRead.Read -> invariants
    is FeatureTaskRuntimeRunInvariantsRead.Rejected -> throw IllegalArgumentException(reason)
  }
