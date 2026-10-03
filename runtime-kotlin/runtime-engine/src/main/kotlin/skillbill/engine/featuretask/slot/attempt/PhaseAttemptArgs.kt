package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.PhaseAttemptAccumulatorContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptLoopState
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.slot.PhaseStepDescription
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

/** Accepted-step launch surface exposed to one prepared step call; not a full step binding. */
internal interface PhaseAcceptedStepCallTarget {
  val launchState: PhaseLaunchState

  fun nextStepIteration(): Int

  fun requireAcceptedAttempt(
    run: PhaseRun,
    call: PhaseStepCall,
  )

  fun requireAcceptedStep(
    run: PhaseRun,
    strategyId: String,
  )
}

internal data class PhaseStepCall(
  val description: PhaseStepDescription,
  private val acceptedStep: PhaseAcceptedStepCallTarget,
  val request: FeatureTaskRuntimeRunFacts,
  val strategyId: String,
) : PhaseAcceptedStepCallTarget by acceptedStep {
  internal val acceptedExecution: PhaseAcceptedStepExecution
    get() =
      acceptedStep as? PhaseAcceptedStepExecution
        ?: error("Step call is not backed by an accepted execution binding.")
}

internal data class RecordRejectionAttemptArgs(
  val context: PhaseAttemptContext,
  val priorCorrection: PriorAttemptCorrection?,
  val call: PhaseStepCall,
)

internal data class FixLoopOutcomeArgs(
  val context: PhaseAttemptAccumulatorContext,
  val loop: PhaseAttemptLoopState,
  val agentId: String,
  val call: PhaseStepCall,
)

internal data class GateCapturedEvidence(
  val captured: CapturedPhaseOutput,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val settledEnvelope: PhaseSettledEnvelopeRead,
)

internal class GateOutput(
  val run: PhaseRun,
  val iteration: Int,
  private val evidence: GateCapturedEvidence,
  val outputGateFailuresBefore: Int? = null,
  val progress: FeatureTaskRuntimeProgressSnapshotAccess,
  val recorder: PhaseRunRecords,
  val phaseSettlementService: PhaseRunSettlements,
  val observability: FeatureTaskRuntimeRunObservability,
  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner,
  val settleAcceptedOutput:
    (NormalizedFeatureTaskRuntimePhaseOutput, FeatureTaskRuntimeRunObservability) -> AttemptResult,
  val stepHooks: PhaseStepHooks,
) {
  val captured get() = evidence.captured
  val fileManifest get() = evidence.fileManifest
  val settledEnvelope get() = evidence.settledEnvelope

  val rejectionExhaustsFixLoop: Boolean?
    get() =
      outputGateFailuresBefore?.let {
        FeatureTaskRuntimeAttemptBudgets.outputGateRejectionExhaustsBudget(run.phaseId, run.policy, it)
      }
}

internal fun recordRejectionAttemptArgs(
  context: PhaseAttemptContext,
  call: PhaseStepCall,
  priorCorrection: PriorAttemptCorrection? = null,
): RecordRejectionAttemptArgs =
  RecordRejectionAttemptArgs(
    context = context,
    priorCorrection = priorCorrection,
    call = call,
  )
