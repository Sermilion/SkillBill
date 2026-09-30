package skillbill.engine.featuretask.slot.attempt

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.core.BlockAndPersistInPhaseArgs
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.runLoopCoupledProgress
import skillbill.engine.featuretask.runloop.state.runLoopCoupledSession
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.PhaseRunner
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
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

internal class PhaseAttemptRunHost(
  override val request: FeatureTaskRuntimeRunFacts,
  private val backingRunState: PhaseRunState,
  internal val boundPhaseId: String,
  private val acceptedLaunchState: PhaseLaunchState,
) : PhaseAttemptEnvironment,
  PhaseQualityGateReporting by backingRunState {
  val progress: FeatureTaskRuntimeProgressSnapshotAccess
    get() = backingRunState.runLoopCoupledProgress().progressSnapshot

  val session: FeatureTaskRuntimeRunSessionObservations
    get() = backingRunState.runLoopCoupledSession().sessionSnapshot()

  val records: PhaseRunRecords
    get() = backingRunState.records

  val goal: PhaseRunGoal
    get() = backingRunState.goal

  val settlements: PhaseRunSettlements
    get() = backingRunState.settlements

  val checkpoints: PhaseRunCheckpoints
    get() = backingRunState.checkpoints

  val specSource: SpecSource
    get() = backingRunState.specSource

  val transitions: FeatureTaskRuntimeTransitionDeclaration
    get() = backingRunState.transitions

  val phaseGates: FeatureTaskRuntimePhaseGates
    get() = backingRunState.phaseGates

  val telemetry: FeatureTaskRuntimeRunObservability
    get() = backingRunState.telemetry

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner
    get() = backingRunState.coupledRunTransitions

  val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator
    get() = backingRunState.stepBinding

  val outputValidator: FeatureTaskRuntimePhaseOutputValidator
    get() = backingRunState.collaborators.outputValidator

  val clock: Clock
    get() = backingRunState.collaborators.clock

  val diagnostics: RuntimeDiagnostics
    get() = backingRunState.collaborators.diagnostics

  fun selectedOwnerOf(stepId: String): PhaseStrategy? = backingRunState.selectedOwnerOf(stepId)

  fun strategyFor(stepId: String): PhaseStrategy = backingRunState.strategyFor(stepId)

  fun unselectedStepIds(): Set<String> = backingRunState.unselectedStepIds()

  fun settlementTarget(iteration: Int): FeatureTaskRuntimePhaseSettlementTarget? =
    backingRunState.settlementTarget(iteration)

  fun fanOut(stepId: String): PhaseRunFanOut = backingRunState.fanOut(stepId)

  fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome =
    backingRunState.ensureFeatureBranch(guardPhase)

  fun runAcceptedAttemptLoop(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome {
    call.requireAcceptedAttempt(run, call)
    check(run.phaseId == boundPhaseId && run.request === request)
    return backingRunState.attemptLoop.run(run, call, PhaseRunLoopAttemptScope(this))
  }

  internal fun runPreparedStep(
    run: PhaseRun,
    call: PhaseStepCall,
    input: PhaseStepInput,
    launchState: PhaseLaunchState,
  ): PhaseStepOutput {
    check(run.phaseId == boundPhaseId && run.request === request)
    check(input.stepName == boundPhaseId && input.facts.issueKey == request.issueKey)
    val owner = requireNotNull(backingRunState.selectedOwnerOf(boundPhaseId))
    check(owner.acceptsAttemptStrategy(call.strategyId) && call.request === request)
    return backingRunState.runnerFor(boundPhaseId).run(input, launchState)
  }

  internal fun runnerForAcceptedAttempt(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseRunner {
    call.requireAcceptedAttempt(run, call)
    check(run.phaseId == boundPhaseId && run.request === request)
    val owner = requireNotNull(backingRunState.selectedOwnerOf(boundPhaseId))
    check(owner.acceptsAttemptStrategy(call.strategyId) && call.request === request)
    return backingRunState.runnerFor(boundPhaseId)
  }

  internal fun launchStateForAcceptedStep(): PhaseLaunchState = acceptedLaunchState

  internal fun recordReviewRunForRunStatePorts(
    reviewRunId: String,
    result: ParallelCodeReviewResult,
    laneTelemetryRecorded: Boolean,
  ) {
    requireReviewOwner()
    backingRunState.recordReviewRun(reviewRunId, result, laneTelemetryRecorded)
  }

  internal fun pinnedReviewTargetForRunStatePorts(resolve: () -> ReviewTarget): ReviewTarget =
    backingRunState.pinnedReviewTarget(resolve.also { requireReviewOwner() })

  private fun requireReviewOwner() {
    check(
      boundPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
        backingRunState.selectedOwnerOf(boundPhaseId)?.slot == PhaseSlot.CODE_REVIEW,
    ) {
      "Review persistence belongs to the accepted review step."
    }
  }
}

internal class PhaseQualityGateCycleScope(
  private val boundRunHost: PhaseAttemptRunHost,
) : PhaseQualityGateCycleContext,
  PhaseQualityGateReporting by boundRunHost {
  internal fun runLoopAttemptHost(): PhaseAttemptRunHost = boundRunHost

  override val request: FeatureTaskRuntimeRunFacts
    get() = boundRunHost.request

  override val progress
    get() = boundRunHost.progress

  override val session
    get() = boundRunHost.session

  override val recorder
    get() = boundRunHost.records

  override val outputValidator
    get() = boundRunHost.outputValidator

  override val phaseGates
    get() = boundRunHost.phaseGates

  override val clock
    get() = boundRunHost.clock

  override val diagnostics
    get() = boundRunHost.diagnostics

  override val goalContinuationRecorder
    get() = boundRunHost.goal

  override val observability
    get() = boundRunHost.telemetry

  override val coupledRunTransitions
    get() = boundRunHost.coupledRunTransitions

  override val transitionDeclaration
    get() = boundRunHost.transitions
}

internal class PhaseRuntimeFinalizationScope(
  private val boundRunHost: PhaseAttemptRunHost,
) : PhaseRuntimeFinalizationContext {
  internal fun runLoopAttemptHost(): PhaseAttemptRunHost = boundRunHost

  override val request: FeatureTaskRuntimeRunFacts
    get() = boundRunHost.request

  override val progress
    get() = boundRunHost.progress

  override val session
    get() = boundRunHost.session

  override val transitions: FeatureTaskRuntimeTransitionDeclaration
    get() = boundRunHost.transitions

  override val recorder
    get() = boundRunHost.records

  override val outputValidator
    get() = boundRunHost.outputValidator

  override val phaseGates
    get() = boundRunHost.phaseGates

  override val clock
    get() = boundRunHost.clock

  override val diagnostics
    get() = boundRunHost.diagnostics

  override val goalContinuationRecorder
    get() = boundRunHost.goal

  override val observability
    get() = boundRunHost.telemetry

  override val coupledRunTransitions
    get() = boundRunHost.coupledRunTransitions

  override val checkpoints
    get() = boundRunHost.checkpoints
}

internal fun PhaseRuntimeFinalizationContext.blockAndPersistInPhase(args: BlockAndPersistInPhaseArgs): PhaseOutcome =
  FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersistInPhase(
    finalizationCoupledProgress(),
    coupledRunTransitions,
    recorder,
    goalContinuationRecorder,
    args,
  )

internal fun PhaseRuntimeFinalizationContext.blockRequiredWriteRejection(
  run: PhaseRun,
  rejection: RequiredPhaseWriteRejected,
): PhaseOutcome = PhaseAttemptOnce.blockRequiredWriteRejection(finalizationAttemptHost(), run, rejection)

internal fun PhaseRuntimeFinalizationContext.persistFinalizationRequiredRunning(
  run: PhaseRun,
  iteration: Int,
): PhaseOutcome? {
  val runningPhaseState =
    FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
      request,
      finalizationCoupledProgress(),
      goalContinuationRecorder,
      PhaseStateRequestArgs(
        write =
          PhaseStateWriteArgs(
            run = run,
            iteration = iteration,
            status = STATUS_RUNNING,
            finished = false,
            outputArtifact = null,
          ),
      ),
    )
  try {
    coupledRunTransitions.acknowledgeRequiredPhaseStart(recorder, runningPhaseState)
  } catch (rejection: RequiredPhaseWriteRejected) {
    return blockRequiredWriteRejection(run, rejection)
  }
  return null
}

internal fun PhaseRuntimeFinalizationContext.persistFinalizationCompleted(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
): Boolean {
  val phaseState =
    FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
      request,
      finalizationCoupledProgress(),
      goalContinuationRecorder,
      PhaseStateRequestArgs(
        write =
          PhaseStateWriteArgs(
            run = run,
            iteration = iteration,
            status = STATUS_COMPLETED,
            finished = true,
            outputArtifact = outputText,
          ),
        extras =
          PhaseStateRequestAttachments(
            normalizedOutput = acceptedOutput.normalizedOutput,
            repairEvidence = acceptedOutput.repairEvidence,
          ),
      ),
    )
  return coupledRunTransitions.persistAuthoritativePhaseCompletion(
    recorder = recorder,
    phaseState = phaseState,
    inMemoryOutput =
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        acceptedOutput.normalizedOutput.canonicalJson,
        acceptedOutput.normalizedOutput,
        acceptedOutput.repairEvidence,
      ),
  )
}

internal fun PhaseRuntimeFinalizationContext.finalizationCoupledProgress(): FeatureTaskRuntimeProgressSnapshotAccess =
  progress

internal fun PhaseRuntimeFinalizationContext.finalizationAttemptHost(): PhaseAttemptRunHost =
  when (this) {
    is PhaseRuntimeFinalizationScope -> runLoopAttemptHost()
    else -> error("Finalization context is not bound to a run-loop attempt host.")
  }

internal fun PhaseRuntimeFinalizationContext.writeRuntimeSubtaskCommit(
  branch: String,
  message: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): WorkflowGitOperationResult =
  FeatureTaskRuntimeRunLoopCheckpoint.writeSubtaskCommit(
    phaseAttemptCollaborationScope(finalizationAttemptHost()),
    branch,
    message,
    identity,
  )
