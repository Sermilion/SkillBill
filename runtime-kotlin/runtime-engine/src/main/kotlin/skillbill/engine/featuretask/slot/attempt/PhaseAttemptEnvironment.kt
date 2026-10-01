package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.attempt.remediationCoupling
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseQualityGateReporting
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.ports.diagnostics.RuntimeDiagnostics
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
  val progress: FeatureTaskRuntimeProgressSnapshotAccess

  val session: FeatureTaskRuntimeRunSessionObservations

  val recorder: PhaseRunRecords

  val phaseGates: FeatureTaskRuntimePhaseGates

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

  val phaseGates: FeatureTaskRuntimePhaseGates

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

  val phaseGates: FeatureTaskRuntimePhaseGates

  val diagnostics: RuntimeDiagnostics

  val recorder: PhaseRunRecords

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner

  fun pushLocalBranchIfAhead(branch: String): String? {
    val git = phaseGates.gitOperations
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

  val phaseGates: FeatureTaskRuntimePhaseGates

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

  val phaseGates: FeatureTaskRuntimePhaseGates

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

  val phaseGates: FeatureTaskRuntimePhaseGates

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner

  val observability: FeatureTaskRuntimeRunObservability
}

/** Launch preparation without checkpoint remediation, traversal, or strategy lookup authority. */
internal interface PhaseAttemptLaunchPreparationContext :
  PhaseOutputSettlementContext,
  PhaseAttemptLaunchRuntimeContext,
  PhaseAttemptPlanAuthorization {
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

internal typealias PhaseAttemptRunLoopCollaborators = PhaseRunLoopAttemptCollaborators

internal open class PhaseAttemptSettlementScope(
  private val boundHost: PhaseAttemptRunHost,
) : PhaseOutputSettlementContext,
  PhaseAttemptPlanAuthorization {
  internal fun attemptRunHost(): PhaseAttemptRunHost = boundHost

  override val request: FeatureTaskRuntimeRunFacts
    get() = attemptRunHost().request

  override val phaseGates: FeatureTaskRuntimePhaseGates
    get() = attemptRunHost().phaseGates

  override val clock
    get() = attemptRunHost().clock

  override val diagnostics: RuntimeDiagnostics
    get() = attemptRunHost().diagnostics

  override val progress: FeatureTaskRuntimeProgressSnapshotAccess
    get() = attemptRunHost().progress

  override val session: FeatureTaskRuntimeRunSessionObservations
    get() = attemptRunHost().session

  override val observability: FeatureTaskRuntimeRunObservability
    get() = attemptRunHost().telemetry

  override val recorder: PhaseRunRecords
    get() = attemptRunHost().records

  override val goalContinuationRecorder: PhaseRunGoal
    get() = attemptRunHost().goal

  override val phaseSettlementService: PhaseRunSettlements
    get() = attemptRunHost().settlements

  override val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner
    get() = attemptRunHost().coupledRunTransitions

  override val specSource: SpecSource
    get() = attemptRunHost().specSource

  override val transitionDeclaration: FeatureTaskRuntimeTransitionDeclaration
    get() = attemptRunHost().transitions

  override fun acceptedStepPolicy(stepId: String): PhaseStepPolicy =
    attemptRunHost().selectedOwnerOf(stepId)?.policyFor(stepId)
      ?: error("Step '$stepId' is not in the accepted execution plan.")

  override fun unselectedStepIds(): Set<String> = attemptRunHost().unselectedStepIds()

  override fun extendsOwnedInventory(stepId: String): Boolean =
    attemptRunHost().selectedOwnerOf(stepId)?.policyFor(stepId)?.extendsOwnedInventory == true

  internal fun requireAcceptedBoundStep(stepId: String) {
    check(stepId == attemptRunHost().boundPhaseId) {
      "Step '$stepId' is not the accepted binding for this attempt; " +
        "only '${attemptRunHost().boundPhaseId}' is authorized."
    }
  }
}

internal open class PhaseAttemptLaunchCollaborationScope(
  host: PhaseAttemptRunHost,
) : PhaseAttemptSettlementScope(host),
  PhaseAttemptLaunchRuntimeContext,
  PhaseAttemptLaunchPreparationContext {
  internal val acceptedLaunchState: PhaseLaunchState
    get() = attemptRunHost().launchStateForAcceptedStep()

  override fun stepHooks(run: PhaseRun): PhaseStepHooks {
    requireAcceptedBoundStep(run.phaseId)
    return attemptRunHost().strategyFor(run.phaseId).stepHooks(run.phaseId)
  }

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    requireAcceptedBoundStep(stepId)
    return attemptRunHost().strategyFor(stepId).briefingInvariantFields(stepId)
  }

  override fun phaseSettlementTarget(iteration: Int): FeatureTaskRuntimePhaseSettlementTarget? =
    attemptRunHost().settlementTarget(iteration)
}

internal open class PhaseAttemptRemediationCollaborationScope(
  host: PhaseAttemptRunHost,
) : PhaseAttemptLaunchCollaborationScope(host),
  PhaseCheckpointRemediationContext,
  PhaseAttemptTraversalRuntimeContext {
  override val specSource: SpecSource
    get() = attemptRunHost().specSource

  override val transitions: FeatureTaskRuntimeTransitionDeclaration
    get() = attemptRunHost().transitions

  override val checkpoints: PhaseRunCheckpoints
    get() = attemptRunHost().checkpoints
}

internal open class PhaseAttemptScope(
  host: PhaseAttemptRunHost,
) : PhaseAttemptLaunchCollaborationScope(host)

internal class PhaseRunLoopAttemptScope(
  host: PhaseAttemptRunHost,
) : PhaseAttemptRemediationCollaborationScope(host),
  PhaseRunLoopAttemptCollaborators {
  override fun strategyFor(stepId: String): PhaseStrategy {
    requireAcceptedBoundStep(stepId)
    if (attemptRunHost().selectedOwnerOf(stepId) == null) {
      error("Step '$stepId' is not in the accepted execution plan.")
    }
    return attemptRunHost().strategyFor(stepId)
  }

  internal fun runnerForAcceptedAttempt(
    run: PhaseRun,
    call: PhaseStepCall,
  ) = attemptRunHost().runnerForAcceptedAttempt(run, call)
}

internal fun phaseAttemptLaunchCollaborationScope(host: PhaseAttemptRunHost): PhaseAttemptLaunchCollaborationScope =
  PhaseAttemptLaunchCollaborationScope(host)

internal fun phaseAttemptCollaborationScope(host: PhaseAttemptRunHost): PhaseAttemptRemediationCollaborationScope =
  PhaseAttemptRemediationCollaborationScope(host)

internal fun remediationCollaborationScope(
  launchEnvironment: PhaseAttemptLaunchCollaborationScope,
): PhaseAttemptRemediationCollaborationScope =
  when (launchEnvironment) {
    is PhaseAttemptRemediationCollaborationScope -> launchEnvironment
    else -> PhaseAttemptRemediationCollaborationScope(launchEnvironment.attemptRunHost())
  }

internal typealias PhaseAttemptRunCollaborationScope = PhaseAttemptRemediationCollaborationScope

internal val PhaseRunLoopAttemptCollaborators.blockingSessionForPhaseEffects:
  FeatureTaskRuntimeRunSessionObservations
  get() = settlementCoupling().session

internal val PhaseOutputSettlementContext.blockingSessionForPhaseEffects: FeatureTaskRuntimeRunSessionObservations
  get() = settlementCoupling().session

internal fun PhaseCheckpointRemediationContext.blockingSessionForPhaseEffects():
  FeatureTaskRuntimeRunSessionObservations =
  remediationCoupling().session
