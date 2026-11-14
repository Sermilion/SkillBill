package skillbill.engine.featuretask.runloop.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.config.model.PhaseAgentRoutingSource
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeModelResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeResumeLaunchAssignment
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchResolver
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.review.finding.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.engine.featuretask.runloop.finalization.CommitPushManifestCompletion
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.qualitygate.RuntimeQualityGateCycles
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunEvidenceOwnership
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindings
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledProgress
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.PhaseBindingFactory
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseUpstreamFailureContinuationHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptCollaborators
import skillbill.engine.featuretask.slot.attempt.phaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.workflow.WorkflowAgentAssignmentsFailureCode
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.taskruntime.FeatureTaskImplementationChecklistStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Path
import java.time.Clock

public class FeatureTaskRuntimeRunLoopContext internal constructor(
  override val request: FeatureTaskRuntimeRunFacts,
  internal val runState: PhaseRunState,
  override val gitOperations: WorkflowGitOperations,
  override val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  override val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory,
  override val specIntentProjectionResolver: SpecIntentProjectionResolver,
  override val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  internal val qualityGateCycles: RuntimeQualityGateCycles,
  override val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  override val diffResolver: DiffResolverPort,
  internal val phaseRunFactory: FeatureTaskRuntimePhaseRunFactory,
) : PhaseRunLoopAttemptCollaborators {
  internal val commitPushManifestCompletion: CommitPushManifestCompletion?
    get() = runState.commitPushManifestCompletion
  override val progress: FeatureTaskRuntimeProgressSnapshotAccess get() = runState.coupledProgress().progressSnapshot

  internal val state: FeatureTaskRuntimeRunState get() = runState.coupledProgress()
  override val session: FeatureTaskRuntimeRunSessionObservations get() = runState.coupledSession().sessionSnapshot()
  override val observability: FeatureTaskRuntimeRunObservability get() = runState.telemetry
  override val recorder: PhaseRunRecords get() = runState.records
  override val transitions: FeatureTaskRuntimeTransitionDeclaration get() = runState.transitions

  override val transitionDeclaration: FeatureTaskRuntimeTransitionDeclaration get() = runState.transitions
  override val goalContinuationRecorder: PhaseRunGoal get() = runState.goal
  override val phaseSettlementService: PhaseRunSettlements get() = runState.settlements
  override val checkpoints: PhaseRunCheckpoints get() = runState.checkpoints
  override val diagnostics: RuntimeDiagnostics get() = runState.diagnostics
  override val clock: Clock get() = runState.clock
  override val specSource: SpecSource get() = runState.specSource

  override val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner get() = runState.coupledRunTransitions

  internal fun hasLeftSlot(slot: PhaseSlot): Boolean =
    transitions.forwardPhaseIds.any { stepId ->
      val owner = runState.selectedOwnerOf(stepId)
      owner != null && owner.slot.ordinal > slot.ordinal &&
        state.phase(stepId).record?.status?.let { it != WorkflowStepStatus.PENDING } == true
    }

  internal fun bindingFactory(run: PhaseRun): PhaseBindingFactory {
    requireAcceptedRun(run)
    runState.stepBinding.beginStepBinding(run)
    return FeatureTaskRuntimeRunLoopStepBindings.factory(launchScope(run), run)
  }

  internal fun fanOutUnitBindings(
    run: PhaseRun,
    unitId: Int,
    bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator,
  ): PhaseBindingFactory {
    requireAcceptedRun(run)
    bindingCoordinator.beginStepBinding(run, unitId)
    return FeatureTaskRuntimeRunLoopStepBindings.factory(
      launchScope(run),
      run,
      unitId,
      bindingCoordinator = bindingCoordinator,
    )
  }

  private fun requireAcceptedRun(run: PhaseRun) {
    require(run.request === request)
    require(runState.selectedOwnerOf(run.phaseId) != null)
    require(runState.strategyFor(run.phaseId).policyFor(run.phaseId) == run.policy)
  }

  private fun launchScope(run: PhaseRun) =
    phaseAttemptLaunchCollaborationScope(
      PhaseAttemptRunHost(
        acceptedRun = run,
        backingRunState = runState,
        directGitOperations = gitOperations,
        directDecompositionPlanner = decompositionPlanner,
        directFindingVerificationBoundaryMemory = findingVerificationBoundaryMemory,
        directSpecIntentProjectionResolver = specIntentProjectionResolver,
        directLifecycleTelemetry = lifecycleTelemetry,
        directSharedEvidenceResolver = sharedEvidenceResolver,
        directDiffResolver = diffResolver,
        directQualityGateCycles = qualityGateCycles,
        directVerifySelectedLaunch = { diagnostics ->
          phaseRunFactory.verifySelectedLaunch(run, diagnostics, launchImminent = true)
        },
      ),
    )

  override fun strategyFor(stepId: String): PhaseStrategy {
    if (runState.selectedOwnerOf(stepId) == null) {
      error("Step '$stepId' is not in the accepted execution plan.")
    }
    return runState.strategyFor(stepId)
  }

  internal fun stepHooks(run: PhaseRun): PhaseStepHooks<*> = strategyFor(run.phaseId).stepHooks(run.phaseId)

  override fun acceptedStepPolicy(stepId: String): PhaseStepPolicy =
    runState.selectedOwnerOf(stepId)?.policyFor(stepId)
      ?: error("Step '$stepId' is not in the accepted execution plan.")

  override fun unselectedStepIds(): Set<String> = runState.unselectedStepIds()

  override fun extendsOwnedInventory(stepId: String): Boolean =
    runState.selectedOwnerOf(stepId)?.policyFor(stepId)?.extendsOwnedInventory == true
}

internal data class LaunchRejectionAttribution(
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
)

internal fun resolveLaunchRejectionAttribution(
  declarations: List<PhaseHandoffProjectionDeclaration>,
  projectionName: String,
  currentProducerIteration: (String) -> Int?,
  fallbackProducerIteration: FeatureTaskRuntimeProducerIteration,
): LaunchRejectionAttribution {
  val declaration =
    declarations.singleOrNull { it.projectionName == projectionName }
      ?: return LaunchRejectionAttribution(
        projectionContractId = "feature_task_runtime.$projectionName",
        producerIteration = fallbackProducerIteration,
      )
  val declaredProducer = declaration.producerIteration
  return LaunchRejectionAttribution(
    projectionContractId = declaration.projectionContractId,
    producerIteration =
      FeatureTaskRuntimeProducerIteration(
        phaseId = declaredProducer.phaseId,
        iteration = currentProducerIteration(declaredProducer.phaseId) ?: declaredProducer.iteration,
      ),
  )
}

internal fun isFeatureSpecPathForIssue(
  path: String,
  issueKey: String,
): Boolean {
  val normalized = path.trim().trimEnd('/')
  if (normalized == LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY) return true
  if (!normalized.startsWith(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX)) return false
  val issueDirectory = normalized.removePrefix(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX).substringBefore('/')
  val key = issueKey.trim()
  return issueDirectory == key || issueDirectory.startsWith("$key-")
}

internal fun reconcileCheckpointPathInventory(
  repoRoot: Path,
  issueKey: String,
  specReference: String,
  workflowId: String,
  paths: List<String>,
): List<String> {
  val specPath =
    Path
      .of(specReference)
      .let { path -> if (path.isAbsolute) repoRoot.relativize(path) else path }
      .normalize()
      .toString()
  return paths
    .filterNot { path ->
      path == specPath ||
        isFeatureSpecPathForIssue(path, issueKey) ||
        FeatureTaskRuntimeRunEvidenceOwnership.isOwnedByRun(path, workflowId)
    }.distinct()
}

internal fun resolveReviewPassNumber(
  reservedPassNumber: Int?,
  completedReviewPassCount: Int,
): Int {
  reservedPassNumber?.let { pass ->
    require(pass == 1) { "Review reservation allows only pass 1, was $pass." }
  }
  require(completedReviewPassCount <= 1) {
    "Review completed-pass count cannot exceed one, was $completedReviewPassCount."
  }
  return 1
}

@Inject
public open class FeatureTaskRuntimeRunLoopEntry(
  private val gitOperations: WorkflowGitOperations,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory,
  private val specIntentProjectionResolver: SpecIntentProjectionResolver,
  private val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  private val qualityGateCycles: RuntimeQualityGateCycles,
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val diffResolver: DiffResolverPort,
  private val implementationChecklistStore: FeatureTaskImplementationChecklistStore,
  private val commitPushManifestCompletion: CommitPushManifestCompletion,
  private val phaseRunFactory: FeatureTaskRuntimePhaseRunFactory,
) {
  public fun context(
    request: FeatureTaskRuntimeRunFacts,
    runState: PhaseRunState,
  ): FeatureTaskRuntimeRunLoopContext {
    runState.implementationChecklistStore = implementationChecklistStore
    runState.commitPushManifestCompletion = commitPushManifestCompletion
    return FeatureTaskRuntimeRunLoopContext(
      request,
      runState,
      gitOperations,
      decompositionPlanner,
      findingVerificationBoundaryMemory,
      specIntentProjectionResolver,
      lifecycleTelemetry,
      qualityGateCycles,
      sharedEvidenceResolver,
      diffResolver,
      phaseRunFactory,
    )
  }

  public open fun run(
    context: FeatureTaskRuntimeRunLoopContext,
    beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit = {},
  ): FeatureTaskRuntimeRunReport = runLoop(context, beforeDrive, AgentRunOutputSink.NONE, false, null)

  public open fun run(
    context: FeatureTaskRuntimeRunLoopContext,
    beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
    afterStepSettlement: (() -> Unit)?,
  ): FeatureTaskRuntimeRunReport {
    if (outputSink === AgentRunOutputSink.NONE && !captureStreamOutput && afterStepSettlement == null) {
      return run(context, beforeDrive)
    }
    return runLoop(context, beforeDrive, outputSink, captureStreamOutput, afterStepSettlement)
  }

  private fun runLoop(
    context: FeatureTaskRuntimeRunLoopContext,
    beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
    afterStepSettlement: (() -> Unit)?,
  ): FeatureTaskRuntimeRunReport {
    val loop = FeatureTaskRuntimeRunLoop(context = context)
    beforeDrive(loop)
    loop.drive(outputSink, captureStreamOutput, afterStepSettlement)
    return loop.report()
  }
}

@Inject
public class FeatureTaskRuntimePhaseRunFactory(
  private val stepLaunchResolver: FeatureTaskRuntimeStepLaunchResolver,
  private val projectStore: ProjectStorePort,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
) {
  internal fun verifySelectedLaunch(
    run: PhaseRun,
    diagnostics: RuntimeDiagnostics,
    launchImminent: Boolean = run.launchAssignment != null,
  ) {
    val inputs =
      StepLaunchAssignmentInputs(
        invokedAgentId = run.request.invokedAgentId,
        agentAssignment = run.request.agentAssignment,
        modelAssignment = run.request.modelAssignment,
        workflowAgentAssignments = run.request.workflowAgentAssignments,
        agentAddonSelection = run.request.agentAddonSelection.persisted,
        legacyPausedStepAssignments = legacyPausedStepAssignments(run.request),
      )
    val environmentKind =
      if (run.request.goalContinuation == null) {
        LaunchEnvironmentKind.INHERITED
      } else {
        LaunchEnvironmentKind.GOVERNED_CHILD
      }
    val assignments = run.request.workflowAgentAssignments
    if (assignments?.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN) {
      val expected =
        assignments.phaseAssignments[run.phaseId]?.agentId?.wireValue
          ?: throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
            "Frozen routing has no provider assignment for phase '${run.phaseId}'.",
          )
      val accepted = run.launchAssignment
      if (accepted != null && accepted.agentId != expected) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "The accepted provider for phase '${run.phaseId}' no longer matches its parent assignment.",
        )
      }
      if (launchImminent) {
        stepLaunchResolver.verifySelectedProvider(run.phaseId, expected, environmentKind, inputs, diagnostics)
      }
      return
    }
    val assignment = run.launchAssignment
    if (assignment != null) {
      stepLaunchResolver.verifySelectedLaunch(assignment, environmentKind, inputs, diagnostics)
    } else if (launchImminent) {
      stepLaunchResolver.verifySelectedProvider(
        run.phaseId,
        run.resolvedAgent.resolvedAgentId,
        environmentKind,
        inputs,
        diagnostics,
      )
    }
  }

  private fun legacyPausedStepAssignments(request: FeatureTaskRuntimeRunFacts): Map<String, String> {
    val assignments =
      request.workflowAgentAssignments
        ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY }
        ?: return emptyMap()
    if (request.workflowId.isBlank()) return emptyMap()
    val pausedStepIds =
      executionPlans.resumeEvidenceIfPlanExists(request.workflowId)?.pausedStepIds.orEmpty()
    return assignments.phaseAssignments
      .filterKeys { it in pausedStepIds }
      .mapValues { (_, phase) -> phase.agentId.wireValue }
  }

  internal fun build(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    request: FeatureTaskRuntimeRunFacts,
    specSource: SpecSource,
    reentry: PendingReentry?,
  ): PhaseRun {
    val recorded = context.runState.stepLaunchAssignment(phaseId)
    val resolvedAgent = resolveAgent(context, phaseId, request, recorded)
    val resolvedAgentId = resolvedAgent.resolvedAgentId
    val repairState =
      (request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair?.snapshot
    val criterion =
      repairState?.let { state ->
        state.criteria.singleOrNull { it.canonicalRef == state.currentCriterionRef }
      }
    val escalatedRepair =
      phaseId == (request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair?.steps?.repair &&
        criterion?.escalated == true
    val reasoningDirective =
      if (escalatedRepair) {
        FeatureTaskRuntimeModelResolver.reasoningTierDirective(resolvedAgentId, request.modelAssignment)
      } else {
        null
      }
    val escalatedAssignment =
      reasoningDirective?.let { directive ->
        stepLaunchResolver.resolveDirective(
          phaseId,
          resolvedAgentId,
          directive,
          if (request.goalContinuation == null) {
            LaunchEnvironmentKind.INHERITED
          } else {
            LaunchEnvironmentKind.GOVERNED_CHILD
          },
        )
      }
    val run =
      PhaseRun(
        phaseId = phaseId,
        declaration =
          phaseDeclaration(
            phaseId,
            request.runInvariants.featureSize,
            context.runState.unselectedStepIds(),
          ),
        resolvedAgent = resolvedAgent,
        modelDirective =
          reasoningDirective ?: FeatureTaskRuntimeResumeLaunchAssignment.modelDirective(
            recorded,
            resolvedAgentId,
            phaseId,
            request.modelAssignment,
          ),
        compaction = request.compactionSettings.directiveFor(phaseId),
        request = request,
        specSource = specSource,
        policy = context.acceptedStepPolicy(phaseId),
        projectStoreRoot = projectStore.resolve(request.repoRoot).root,
        launchAssignment =
          escalatedAssignment ?: FeatureTaskRuntimeResumeLaunchAssignment.recordedForAgent(recorded, resolvedAgentId),
        reentry = reentry,
        failureContinuationOutput =
          (context.runState.strategyFor(phaseId).stepHooks(phaseId) as? PhaseUpstreamFailureContinuationHooks)
            ?.failureContinuationOutput(context.state.outputs()),
      )
    verifySelectedLaunch(run, context.diagnostics)
    return run
  }

  private fun resolveAgent(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    request: FeatureTaskRuntimeRunFacts,
    recorded: StepLaunchAssignment?,
  ): FeatureTaskRuntimeResolvedPhaseAgent =
    frozenAgent(context, phaseId, request, recorded) ?: legacyAgent(context, phaseId, request, recorded)

  private fun frozenAgent(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    request: FeatureTaskRuntimeRunFacts,
    recorded: StepLaunchAssignment?,
  ): FeatureTaskRuntimeResolvedPhaseAgent? =
    request.workflowAgentAssignments
      ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
      ?.let { frozen ->
        val phase =
          frozen.phaseAssignments[phaseId]
            ?: run {
              routingDiagnostic(context, phaseId, "refused", "missing_frozen_phase")
              throw SkillBillRuntimeException(
                WorkflowAgentAssignmentsFailureCode.INVALID_RECORD,
                "Frozen routing for workflow '${request.workflowId}' has no assignment for phase '$phaseId'. " +
                  "Keep the stored record and repair it before resuming.",
              )
            }
        if (recorded != null && recorded.agentId != phase.agentId.wireValue) {
          routingDiagnostic(context, phaseId, "refused", "provider_contradiction")
          throw SkillBillRuntimeException(
            WorkflowAgentAssignmentsFailureCode.INVALID_RECORD,
            "Frozen routing for workflow '${request.workflowId}' " +
              "selects '${phase.agentId.wireValue}' for '$phaseId', " +
              "but its retained launch evidence selects '${recorded.agentId}'. " +
              "Keep both records and repair the contradiction before resuming.",
          )
        }
        FeatureTaskRuntimeResolvedPhaseAgent(
          phaseId = phaseId,
          invokedAgentId = frozen.invokedAgentId.wireValue,
          configuredAgentOverrideId = frozen.configuredAgentOverrideId?.wireValue,
          routingSource = PhaseAgentRoutingSource.valueOf(phase.source.name),
          selectedProviderId = phase.agentId.wireValue,
        )
      }

  private fun legacyAgent(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    request: FeatureTaskRuntimeRunFacts,
    recorded: StepLaunchAssignment?,
  ): FeatureTaskRuntimeResolvedPhaseAgent {
    val legacy =
      request.workflowAgentAssignments
        ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY }
    val recordedPhase = legacy?.phaseAssignments?.get(phaseId)
    val compatibilitySelection =
      FeatureTaskRuntimeAgentResolver.resolve(
        phaseId = phaseId,
        assignment = request.agentAssignment.copy(matrix = null),
        invokedAgentId = request.invokedAgentId,
      )
    val resolved =
      compatibilitySelection.copy(
        routingSource =
          if (recordedPhase?.agentId?.wireValue == compatibilitySelection.resolvedAgentId) {
            PhaseAgentRoutingSource.LEGACY_RECORDED
          } else {
            PhaseAgentRoutingSource.LEGACY_INVOKING
          },
      )
    val priorPhase = context.state.phase(phaseId).record
    val providerPaused =
      priorPhase?.status == WorkflowStepStatus.PAUSED &&
        FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(priorPhase.blockedReason.orEmpty())
    val retained =
      recorded?.takeIf { providerPaused }?.let { assignment ->
        resolved.copy(
          invokedAgentId = assignment.agentId,
          configuredAgentOverrideId = null,
          routingSource = PhaseAgentRoutingSource.LEGACY_RECORDED,
          selectedProviderId = assignment.agentId,
        )
      } ?: resolved
    routingDiagnostic(
      context,
      phaseId,
      "selected",
      if (providerPaused && recorded != null) {
        "provider_limit_retained"
      } else if (retained.routingSource == PhaseAgentRoutingSource.LEGACY_RECORDED) {
        "legacy_recorded"
      } else {
        "legacy_invoking"
      },
      retained.resolvedAgentId,
    )
    return retained
  }
}

private const val ROUTING_DIAGNOSTIC_ID_LIMIT = 128

private fun routingDiagnostic(
  context: FeatureTaskRuntimeRunLoopContext,
  phaseId: String,
  outcome: String,
  reason: String,
  providerId: String? = null,
) {
  RuntimeDiagnosticsBestEffortWarning.record(
    context.diagnostics,
    "seam=workflow_agent_assignment workflow=${context.request.workflowId.take(
      ROUTING_DIAGNOSTIC_ID_LIMIT,
    )} phase=$phaseId " +
      "provider=${providerId ?: "unknown"} outcome=$outcome reason=$reason",
  )
}

public class FeatureTaskRuntimeRunLoop internal constructor(
  internal val context: FeatureTaskRuntimeRunLoopContext,
) {
  internal val session = context.runState.coupledSession()

  init {
    val resumed = FeatureTaskRuntimeRunLoopDrive.resumedReentry(context)
    context.runState.coupledRunTransitions.establishResumedReentryPair(resumed)
  }

  internal fun drive(
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
    afterStepSettlement: (() -> Unit)?,
  ) {
    with(FeatureTaskRuntimeRunLoopDrive) {
      invalidateStaleEvidence(context)
      context.runPhaseDriveLoop { phaseId -> advance(phaseId, outputSink, captureStreamOutput, afterStepSettlement) }
    }
  }

  internal fun advance(
    phaseId: String,
    outputSink: AgentRunOutputSink,
    captureStreamOutput: Boolean,
    afterStepSettlement: (() -> Unit)?,
  ): PhaseSettlement {
    try {
      if (context.request.stopAfterIntake && PhaseSlot.slotForStep(phaseId) != PhaseSlot.INTAKE) {
        return PhaseSettlement.stop()
      }
      FeatureTaskRuntimeRunLoopDrive.phaseEntryBlockReason(context, phaseId)?.let { reason ->
        FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
          context.request,
          context.state,
          session,
          phaseId,
          reason,
        )
        return PhaseSettlement.stop()
      }
      FeatureTaskRuntimeRunLoopDrive.settleWithoutLaunch(context, phaseId)?.let { settled -> return settled }
      val reason = FeatureTaskRuntimeRunLoopDrive.advancePhaseReason(context, phaseId, outputSink, captureStreamOutput)
      return FeatureTaskRuntimeRunLoopDrive.settleAdvanceOutcome(
        context.request,
        context.state,
        session,
        phaseId,
        reason,
      )
    } finally {
      afterStepSettlement?.invoke()
    }
  }

  internal fun report(): FeatureTaskRuntimeRunReport {
    val branch =
      session.resolvedBranch
        ?: context.recorder.loadResolvedBranch(context.request.workflowId)?.branch
    return session.decomposed ?: session.paused?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: session.blocked?.let { report ->
      if (report.resolvedBranch == null && branch != null) report.copy(resolvedBranch = branch) else report
    } ?: FeatureTaskRuntimeRunReport.Completed(
      issueKey = context.request.issueKey,
      workflowId = context.request.workflowId,
      featureSize = context.request.runInvariants.featureSize.name,
      completedPhaseIds = context.state.completedPhaseIds,
      resolvedBranch = branch,
    )
  }

  internal fun applyOperatorDecision(): String? =
    buildString {
      append("Operator decisions over review remediation are removed; ")
      append("the run advances to validate after one implement_fix round.")
      context.request.goalContinuation?.let {
        append(
          " Recover with: '${recommendedDurableChildRecoveryCommand(
            it.parentIssueKey,
            it.subtaskId,
            null,
            null,
          )}'.",
        )
      }
    }
}

internal fun PhaseRunState.retainingChecklistFrom(previous: PhaseRunState): PhaseRunState {
  val store = previous.implementationChecklistStore
  if (store != null && implementationChecklistStore == null) implementationChecklistStore = store
  if (commitPushManifestCompletion == null) {
    commitPushManifestCompletion = previous.commitPushManifestCompletion
  }
  return this
}
