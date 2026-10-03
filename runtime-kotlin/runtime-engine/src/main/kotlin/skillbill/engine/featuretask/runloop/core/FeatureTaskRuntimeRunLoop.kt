package skillbill.engine.featuretask.runloop.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.review.finding.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.qualitygate.RuntimeQualityGateCycles
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunEvidenceOwnership
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindings
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.coupledProgress
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptCollaborators
import skillbill.engine.featuretask.slot.attempt.phaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeReadinessGateCoordinator
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import java.nio.file.Path

internal class FeatureTaskRuntimeRunLoopContext(
  override val request: FeatureTaskRuntimeRunFacts,
  val runState: PhaseRunState,
  override val gitOperations: WorkflowGitOperations,
  override val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  override val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory,
  override val specIntentProjectionResolver: SpecIntentProjectionResolver,
  override val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  val qualityGateCycles: RuntimeQualityGateCycles,
  val readinessGateCoordinator: FeatureTaskRuntimeReadinessGateCoordinator,
  override val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  override val diffResolver: DiffResolverPort,
) : PhaseRunLoopAttemptCollaborators {
  override val progress get() = runState.coupledProgress().progressSnapshot

  internal val state: FeatureTaskRuntimeRunState get() = runState.coupledProgress()
  override val session get() = runState.coupledSession().sessionSnapshot()
  override val observability: FeatureTaskRuntimeRunObservability get() = runState.telemetry
  override val recorder: PhaseRunRecords get() = runState.records
  override val transitions: FeatureTaskRuntimeTransitionDeclaration get() = runState.transitions

  override val transitionDeclaration: FeatureTaskRuntimeTransitionDeclaration get() = runState.transitions
  override val goalContinuationRecorder: PhaseRunGoal get() = runState.goal
  override val phaseSettlementService: PhaseRunSettlements get() = runState.settlements
  override val checkpoints: PhaseRunCheckpoints get() = runState.checkpoints
  override val diagnostics: RuntimeDiagnostics get() = runState.diagnostics
  override val clock get() = runState.clock
  override val specSource: SpecSource get() = runState.specSource

  override val coupledRunTransitions get() = runState.coupledRunTransitions

  fun acceptedStep(run: PhaseRun): PhaseAcceptedStepExecution {
    require(run.request === request)
    require(runState.selectedOwnerOf(run.phaseId) != null)
    require(runState.strategyFor(run.phaseId).policyFor(run.phaseId) == run.policy)
    runState.stepBinding.beginStepBinding(run)
    return FeatureTaskRuntimeRunLoopStepBindings.create(
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
          directReadinessGateCoordinator = readinessGateCoordinator,
        ),
      ),
      run,
    )
  }

  override fun strategyFor(stepId: String): PhaseStrategy {
    if (runState.selectedOwnerOf(stepId) == null) {
      error("Step '$stepId' is not in the accepted execution plan.")
    }
    return runState.strategyFor(stepId)
  }

  fun stepHooks(run: PhaseRun): PhaseStepHooks = strategyFor(run.phaseId).stepHooks(run.phaseId)

  override fun acceptedStepPolicy(stepId: String) =
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

private const val FEATURE_SPEC_ROOT = ".feature-specs"

fun isFeatureSpecPathForIssue(
  path: String,
  issueKey: String,
): Boolean {
  val normalized = path.trim().trimEnd('/')
  if (normalized == FEATURE_SPEC_ROOT) return true
  if (!normalized.startsWith("$FEATURE_SPEC_ROOT/")) return false
  val issueDirectory = normalized.removePrefix("$FEATURE_SPEC_ROOT/").substringBefore('/')
  val key = issueKey.trim()
  return issueDirectory == key || issueDirectory.startsWith("$key-")
}

fun reconcileCheckpointPathInventory(
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

fun resolveReviewPassNumber(
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
open class FeatureTaskRuntimeRunLoopEntry(
  private val gitOperations: WorkflowGitOperations,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory,
  private val specIntentProjectionResolver: SpecIntentProjectionResolver,
  private val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  private val qualityGateCycles: RuntimeQualityGateCycles,
  private val readinessGateCoordinator: FeatureTaskRuntimeReadinessGateCoordinator,
  private val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  private val diffResolver: DiffResolverPort,
) {
  internal fun context(
    request: FeatureTaskRuntimeRunFacts,
    runState: PhaseRunState,
  ): FeatureTaskRuntimeRunLoopContext =
    FeatureTaskRuntimeRunLoopContext(
      request,
      runState,
      gitOperations,
      decompositionPlanner,
      findingVerificationBoundaryMemory,
      specIntentProjectionResolver,
      lifecycleTelemetry,
      qualityGateCycles,
      readinessGateCoordinator,
      sharedEvidenceResolver,
      diffResolver,
    )

  internal open fun run(
    context: FeatureTaskRuntimeRunLoopContext,
    beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit = {},
  ): FeatureTaskRuntimeRunReport {
    val loop = FeatureTaskRuntimeRunLoop(context = context)
    beforeDrive(loop)
    loop.drive()
    return loop.report()
  }
}

class FeatureTaskRuntimeRunLoop internal constructor(
  internal val context: FeatureTaskRuntimeRunLoopContext,
) {
  internal val session = context.runState.coupledSession()

  init {
    val resumed = FeatureTaskRuntimeRunLoopDrive.resumedReentry(context)
    context.runState.coupledRunTransitions.establishResumedReentryPair(resumed)
  }

  fun drive() {
    with(FeatureTaskRuntimeRunLoopDrive) {
      invalidateStaleEvidence(context)
      context.runPhaseDriveLoop(::advance)
    }
  }

  internal fun advance(phaseId: String): PhaseSettlement {
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
    val reason = FeatureTaskRuntimeRunLoopDrive.advancePhaseReason(context, phaseId)
    return FeatureTaskRuntimeRunLoopDrive.settleAdvanceOutcome(
      context.request,
      context.state,
      session,
      phaseId,
      reason,
    )
  }

  fun report(): FeatureTaskRuntimeRunReport {
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

  fun applyOperatorDecision(): String? =
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
