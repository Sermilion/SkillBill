package skillbill.engine.featuretask.slot.attempt

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.review.finding.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.engine.featuretask.runloop.attempt.remediationCoupling
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.qualitygate.RuntimeQualityGateCycles
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseQualityGateReporting
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeReadinessGateCoordinator
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import java.time.Clock

internal interface PhaseAttemptTransitionDeclarationAccess {
  val transitionDeclaration: FeatureTaskRuntimeTransitionDeclaration
}

/** Per-attempt facts shared across helper surfaces. */
internal interface PhaseAttemptEnvironment {
  val request: FeatureTaskRuntimeRunFacts
}

/** Output-gate and runtime-owned gate settlement collaborators for one accepted step. */
internal interface PhaseOutputSettlementContext :
  PhaseAttemptEnvironment,
  PhaseAttemptTransitionDeclarationAccess {
  val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner

  val progress: FeatureTaskRuntimeProgressSnapshotAccess

  val session: FeatureTaskRuntimeRunSessionObservations

  val recorder: PhaseRunRecords

  val gitOperations: WorkflowGitOperations

  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort

  val diffResolver: DiffResolverPort

  val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory

  val specIntentProjectionResolver: SpecIntentProjectionResolver

  val clock: Clock

  val diagnostics: RuntimeDiagnostics

  val goalContinuationRecorder: PhaseRunGoal

  val phaseSettlementService: PhaseRunSettlements

  val observability: FeatureTaskRuntimeRunObservability

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner

  val specSource: SpecSource
}

/** Checkpoint remediation and repair-receipt collaborators without run-loop dispatch authority. */
internal interface PhaseCheckpointRemediationContext :
  PhaseAttemptEnvironment,
  PhaseAttemptPlanAuthorization {
  val progress: FeatureTaskRuntimeProgressSnapshotAccess

  val session: FeatureTaskRuntimeRunSessionObservations

  val gitOperations: WorkflowGitOperations

  val goalContinuationRecorder: PhaseRunGoal

  val recorder: PhaseRunRecords

  val diagnostics: RuntimeDiagnostics

  val observability: FeatureTaskRuntimeRunObservability

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner

  val transitions: FeatureTaskRuntimeTransitionDeclaration

  val checkpoints: PhaseRunCheckpoints
}

/** Accepted-plan metadata read by runtime launch and checkpoint owners. */
internal interface PhaseAttemptPlanAuthorization {
  fun acceptedStepPolicy(stepId: String): PhaseStepPolicy

  fun unselectedStepIds(): Set<String>

  fun extendsOwnedInventory(stepId: String): Boolean
}

/** Strategy and hook resolution without exposing the run host. */
internal interface PhaseAttemptStrategyLookup {
  fun strategyFor(stepId: String): PhaseStrategy
}

/** Launch and pre-launch hook inputs; narrower than the full collaborator aggregate. */
internal interface PhaseAttemptLaunchRuntimeContext : PhaseAttemptEnvironment {
  val progress: FeatureTaskRuntimeProgressSnapshotAccess

  val session: FeatureTaskRuntimeRunSessionObservations

  val gitOperations: WorkflowGitOperations

  val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner

  val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory

  val specIntentProjectionResolver: SpecIntentProjectionResolver

  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry

  val diagnostics: RuntimeDiagnostics

  val recorder: PhaseRunRecords

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner

  fun pushLocalBranchIfAhead(branch: String): String? {
    val git = gitOperations
    val unpushed = git.localBranchHasUnpushedCommits(request.repoRoot, branch)
    if (unpushed !is WorkflowGitOperationResult.Ok) {
      return "Could not tell whether branch '$branch' has unpushed commits: ${unpushed.error}"
    }
    if (!unpushed.value.trim().equals("true", ignoreCase = true)) return null
    val push = git.pushBranch(request.repoRoot, branch)
    return if (push is WorkflowGitOperationResult.Ok) {
      null
    } else {
      "Could not push branch '$branch': ${push.error}"
    }
  }
}

/** Quality-gate cycle inputs without run-host, review, checkpoint, or strategy lookup authority. */
internal interface PhaseQualityGateCycleContext :
  PhaseAttemptEnvironment,
  PhaseAttemptTransitionDeclarationAccess,
  PhaseQualityGateReporting {
  val progress: FeatureTaskRuntimeProgressSnapshotAccess

  val session: FeatureTaskRuntimeRunSessionObservations

  val recorder: PhaseRunRecords

  val gitOperations: WorkflowGitOperations

  val qualityGateCycles: RuntimeQualityGateCycles

  val readinessGateCoordinator: FeatureTaskRuntimeReadinessGateCoordinator

  val clock: Clock

  val diagnostics: RuntimeDiagnostics

  val goalContinuationRecorder: PhaseRunGoal

  val observability: FeatureTaskRuntimeRunObservability

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner
}

/** Runtime commit and pull-request finalization without arbitrary attempt or strategy authority. */
internal interface PhaseRuntimeFinalizationContext : PhaseAttemptEnvironment {
  val progress: FeatureTaskRuntimeProgressSnapshotAccess

  val session: FeatureTaskRuntimeRunSessionObservations

  val transitions: FeatureTaskRuntimeTransitionDeclaration

  val recorder: PhaseRunRecords

  val gitOperations: WorkflowGitOperations

  val readinessGateCoordinator: FeatureTaskRuntimeReadinessGateCoordinator

  val clock: Clock

  val diagnostics: RuntimeDiagnostics

  val goalContinuationRecorder: PhaseRunGoal

  val observability: FeatureTaskRuntimeRunObservability

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner

  val checkpoints: PhaseRunCheckpoints
}

/** Post-completion traversal hook inputs for planning and decomposition stops. */
internal interface PhaseAttemptTraversalRuntimeContext : PhaseAttemptEnvironment {
  val progress: FeatureTaskRuntimeProgressSnapshotAccess

  val session: FeatureTaskRuntimeRunSessionObservations

  val specSource: SpecSource

  val recorder: PhaseRunRecords

  val diagnostics: RuntimeDiagnostics

  val gitOperations: WorkflowGitOperations

  val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner

  val observability: FeatureTaskRuntimeRunObservability
}

/** Launch preparation without checkpoint remediation, traversal, or strategy lookup authority. */
internal interface PhaseAttemptLaunchPreparationContext :
  PhaseOutputSettlementContext,
  PhaseAttemptLaunchRuntimeContext,
  PhaseAttemptPlanAuthorization {
  val qualityGateCycles: RuntimeQualityGateCycles

  fun stepHooks(run: PhaseRun): PhaseStepHooks

  fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField>

  fun phaseSettlementTarget(iteration: Int): FeatureTaskRuntimePhaseSettlementTarget?
}

/** Run-loop and attempt helpers that resolve strategies for arbitrary selected steps. */
internal interface PhaseRunLoopAttemptCollaborators :
  PhaseOutputSettlementContext,
  PhaseCheckpointRemediationContext,
  PhaseAttemptTraversalRuntimeContext,
  PhaseAttemptPlanAuthorization,
  PhaseAttemptLaunchRuntimeContext,
  PhaseAttemptStrategyLookup

internal open class PhaseAttemptSettlementScope(
  private val boundHost: PhaseAttemptRunHost,
) : PhaseOutputSettlementContext,
  PhaseAttemptPlanAuthorization,
  PhaseAttemptLaunchRuntimeContext,
  PhaseRuntimeFinalizationContext,
  PhaseCheckpointRemediationContext,
  PhaseAttemptTraversalRuntimeContext {
  override val request: FeatureTaskRuntimeRunFacts
    get() = boundHost.request

  override val gitOperations: WorkflowGitOperations
    get() = boundHost.gitOperations

  override val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort
    get() = boundHost.sharedEvidenceResolver

  override val diffResolver: DiffResolverPort
    get() = boundHost.diffResolver

  override val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner
    get() = boundHost.decompositionPlanner

  override val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory
    get() = boundHost.findingVerificationBoundaryMemory

  override val specIntentProjectionResolver: SpecIntentProjectionResolver
    get() = boundHost.specIntentProjectionResolver

  override val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry
    get() = boundHost.lifecycleTelemetry

  override val clock
    get() = boundHost.clock

  override val diagnostics: RuntimeDiagnostics
    get() = boundHost.diagnostics

  override val progress: FeatureTaskRuntimeProgressSnapshotAccess
    get() = boundHost.progress

  override val session: FeatureTaskRuntimeRunSessionObservations
    get() = boundHost.session

  override val observability: FeatureTaskRuntimeRunObservability
    get() = boundHost.telemetry

  override val recorder: PhaseRunRecords
    get() = boundHost.records

  override val goalContinuationRecorder: PhaseRunGoal
    get() = boundHost.goal

  override val phaseSettlementService: PhaseRunSettlements
    get() = boundHost.settlements

  override val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner
    get() = boundHost.coupledRunTransitions

  override val specSource: SpecSource
    get() = boundHost.specSource

  override val transitionDeclaration: FeatureTaskRuntimeTransitionDeclaration
    get() = boundHost.transitions

  override val transitions: FeatureTaskRuntimeTransitionDeclaration
    get() = boundHost.transitions

  override val checkpoints: PhaseRunCheckpoints
    get() = boundHost.checkpoints

  override fun acceptedStepPolicy(stepId: String): PhaseStepPolicy =
    boundHost.selectedOwnerOf(stepId)?.policyFor(stepId)
      ?: error("Step '$stepId' is not in the accepted execution plan.")

  override fun unselectedStepIds(): Set<String> = boundHost.unselectedStepIds()

  override fun extendsOwnedInventory(stepId: String): Boolean =
    boundHost.selectedOwnerOf(stepId)?.policyFor(stepId)?.extendsOwnedInventory == true

  internal fun requireAcceptedBoundStep(stepId: String) {
    check(stepId == boundHost.boundPhaseId) {
      "Step '$stepId' is not the accepted binding for this attempt; " +
        "only '${boundHost.boundPhaseId}' is authorized."
    }
  }

  internal fun resolveAcceptedLaunchState(): PhaseLaunchState = boundHost.launchStateForAcceptedStep()

  internal fun stepBindingCoordinator(): FeatureTaskRuntimeRunLoopStepBindingCoordinator = boundHost.stepBinding

  internal fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome =
    boundHost.ensureFeatureBranch(guardPhase)

  internal fun selectedOwnerOf(stepId: String): PhaseStrategy? = boundHost.selectedOwnerOf(stepId)

  internal fun resolveStrategyFor(stepId: String): PhaseStrategy = boundHost.strategyFor(stepId)

  internal open val qualityGateCycles: RuntimeQualityGateCycles
    get() = boundHost.qualityGateCycles

  override val readinessGateCoordinator: FeatureTaskRuntimeReadinessGateCoordinator
    get() = boundHost.readinessGateCoordinator

  internal fun fanOut(stepId: String): PhaseRunFanOut = boundHost.fanOut(stepId)

  internal fun resolvePhaseSettlementTarget(iteration: Int): FeatureTaskRuntimePhaseSettlementTarget? =
    boundHost.settlementTarget(iteration)

  internal fun runAcceptedAttemptLoop(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome = boundHost.runAcceptedAttemptLoop(run, call)

  internal fun runPreparedStep(
    run: PhaseRun,
    call: PhaseStepCall,
    input: PhaseStepInput,
    launchState: PhaseLaunchState,
  ): PhaseStepOutput = boundHost.runPreparedStep(run, call, input, launchState)

  internal fun recordReviewRunForAcceptedStep(
    reviewRunId: String,
    result: ParallelCodeReviewResult,
    laneTelemetryRecorded: Boolean,
  ) = boundHost.recordReviewRunForRunStatePorts(reviewRunId, result, laneTelemetryRecorded)

  internal fun pinnedReviewTargetForAcceptedStep(resolve: () -> ReviewTarget): ReviewTarget =
    boundHost.pinnedReviewTargetForRunStatePorts(resolve)

  internal fun runnerForAcceptedAttempt(
    run: PhaseRun,
    call: PhaseStepCall,
  ) = boundHost.runnerForAcceptedAttempt(run, call)
}

internal open class PhaseAttemptLaunchCollaborationScope(
  host: PhaseAttemptRunHost,
) : PhaseAttemptSettlementScope(host),
  PhaseQualityGateReporting by host,
  PhaseQualityGateCycleContext,
  PhaseAttemptLaunchPreparationContext {
  internal val acceptedLaunchState: PhaseLaunchState
    get() = resolveAcceptedLaunchState()

  internal val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator
    get() = stepBindingCoordinator()

  override val qualityGateCycles: RuntimeQualityGateCycles
    get() = super.qualityGateCycles

  override fun stepHooks(run: PhaseRun): PhaseStepHooks {
    requireAcceptedBoundStep(run.phaseId)
    return resolveStrategyFor(run.phaseId).stepHooks(run.phaseId)
  }

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    requireAcceptedBoundStep(stepId)
    return resolveStrategyFor(stepId).briefingInvariantFields(stepId)
  }

  override fun phaseSettlementTarget(iteration: Int): FeatureTaskRuntimePhaseSettlementTarget? =
    resolvePhaseSettlementTarget(iteration)

  internal fun writeRuntimeSubtaskCommit(
    branch: String,
    message: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): WorkflowGitOperationResult =
    FeatureTaskRuntimeRunLoopCheckpoint.writeSubtaskCommit(
      this,
      branch,
      message,
      identity,
    )
}

internal open class PhaseAttemptScope(
  host: PhaseAttemptRunHost,
) : PhaseAttemptLaunchCollaborationScope(host)

internal class PhaseRunLoopAttemptScope(
  host: PhaseAttemptRunHost,
) : PhaseAttemptLaunchCollaborationScope(host),
  PhaseRunLoopAttemptCollaborators {
  override fun strategyFor(stepId: String): PhaseStrategy {
    requireAcceptedBoundStep(stepId)
    if (selectedOwnerOf(stepId) == null) {
      error("Step '$stepId' is not in the accepted execution plan.")
    }
    return resolveStrategyFor(stepId)
  }
}

internal fun phaseAttemptLaunchCollaborationScope(host: PhaseAttemptRunHost): PhaseAttemptLaunchCollaborationScope =
  PhaseAttemptLaunchCollaborationScope(host)

internal val PhaseRunLoopAttemptCollaborators.blockingSessionForPhaseEffects:
  FeatureTaskRuntimeRunSessionObservations
  get() = settlementCoupling().session

internal val PhaseOutputSettlementContext.blockingSessionForPhaseEffects: FeatureTaskRuntimeRunSessionObservations
  get() = settlementCoupling().session

internal fun PhaseCheckpointRemediationContext.blockingSessionForPhaseEffects():
  FeatureTaskRuntimeRunSessionObservations =
  remediationCoupling().session
