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
import skillbill.engine.featuretask.runloop.core.BlockAndPersistInPhaseArgs
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.qualitygate.RuntimeQualityGateCycles
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
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeReadinessGateCoordinator
import skillbill.error.featuretask.GoalPlanningPhaseGatesUnsupportedError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

internal class PhaseAttemptRunHost(
  private val acceptedRun: PhaseRun,
  private val backingRunState: PhaseRunState,
  private val directGitOperations: WorkflowGitOperations? = null,
  private val directDecompositionPlanner: FeatureTaskRuntimeDecompositionPlanner? = null,
  private val directFindingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory? = null,
  private val directSpecIntentProjectionResolver: SpecIntentProjectionResolver? = null,
  private val directLifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry? = null,
  private val directSharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort? = null,
  private val directDiffResolver: DiffResolverPort? = null,
  private val directQualityGateCycles: RuntimeQualityGateCycles? = null,
  private val directReadinessGateCoordinator: FeatureTaskRuntimeReadinessGateCoordinator? = null,
) : PhaseAttemptEnvironment,
  PhaseQualityGateReporting by backingRunState {
  override val request: FeatureTaskRuntimeRunFacts get() = acceptedRun.request

  internal val boundPhaseId: String get() = acceptedRun.phaseId

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

  val gitOperations: WorkflowGitOperations
    get() = directGitOperations ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner
    get() = directDecompositionPlanner ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory
    get() = directFindingVerificationBoundaryMemory ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val specIntentProjectionResolver: SpecIntentProjectionResolver
    get() = directSpecIntentProjectionResolver ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry
    get() = directLifecycleTelemetry ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort
    get() = directSharedEvidenceResolver ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val diffResolver: DiffResolverPort
    get() = directDiffResolver ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val qualityGateCycles: RuntimeQualityGateCycles
    get() = directQualityGateCycles ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val readinessGateCoordinator: FeatureTaskRuntimeReadinessGateCoordinator
    get() = directReadinessGateCoordinator ?: throw GoalPlanningPhaseGatesUnsupportedError()

  val telemetry: FeatureTaskRuntimeRunObservability
    get() = backingRunState.telemetry

  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner
    get() = backingRunState.coupledRunTransitions

  val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator
    get() = backingRunState.stepBinding

  val clock: Clock
    get() = backingRunState.clock

  val diagnostics: RuntimeDiagnostics
    get() = backingRunState.diagnostics

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

  internal fun launchStateForAcceptedStep(): PhaseLaunchState = backingRunState

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
  rejection: RequiredPhaseWrite.Rejected,
): PhaseOutcome =
  PhaseAttemptOnce.blockRequiredWriteRejection(
    this as? PhaseAttemptLaunchCollaborationScope
      ?: error("Finalization context is not bound to a run-loop attempt."),
    run,
    rejection,
  )

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
  return when (val write = coupledRunTransitions.acknowledgeRequiredPhaseStart(recorder, runningPhaseState)) {
    is RequiredPhaseWrite.Acknowledged -> null
    is RequiredPhaseWrite.Rejected -> blockRequiredWriteRejection(run, write)
  }
}

internal fun PhaseRuntimeFinalizationContext.persistFinalizationCompleted(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  acceptedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
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
            normalizedOutput = acceptedOutput,
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
        acceptedOutput.canonicalJson,
        acceptedOutput,
      ),
  )
}

internal fun PhaseRuntimeFinalizationContext.finalizationCoupledProgress(): FeatureTaskRuntimeProgressSnapshotAccess =
  progress

internal fun PhaseRuntimeFinalizationContext.writeRuntimeSubtaskCommit(
  branch: String,
  message: String,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
): WorkflowGitOperationResult =
  (
    this as? PhaseAttemptLaunchCollaborationScope
      ?: error("Finalization context is not bound to a run-loop attempt.")
  ).writeRuntimeSubtaskCommit(
    branch,
    message,
    identity,
  )
