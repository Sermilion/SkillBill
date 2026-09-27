package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.PhaseAttemptAccumulatorContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptLoopState
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.slot.PhaseStepDescription
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepState

internal data class PhaseStepCall(
  val description: PhaseStepDescription,
  val runner: PhaseRunner,
  val state: PhaseStepState,
)

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

internal class GateOutput(
  val run: PhaseRun,
  val iteration: Int,
  val captured: CapturedPhaseOutput,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val settledEnvelope: PhaseSettledEnvelopeRead,
  val call: PhaseStepCall,
  val outputGateFailuresBefore: Int? = null,
  val settlementContext: PhaseAttemptEnvironment,
) {
  val request get() = settlementContext.request
  val state get() = settlementContext.state
  val recorder get() = settlementContext.recorder
  val outputValidator get() = settlementContext.outputValidator
  val phaseGates get() = settlementContext.phaseGates
  val clock get() = settlementContext.clock
  val diagnostics get() = settlementContext.diagnostics
  val goalContinuationRecorder get() = settlementContext.goalContinuationRecorder
  val phaseSettlementService get() = settlementContext.phaseSettlementService
  val observability get() = settlementContext.observability

  val rejectionExhaustsFixLoop: Boolean?
    get() =
      outputGateFailuresBefore?.let {
        FeatureTaskRuntimeAttemptBudgets.outputGateRejectionExhaustsBudget(run.phaseId, run.policy, it)
      }
}

internal fun PhaseAttemptEnvironment.stepHooks(run: PhaseRun): PhaseStepHooks =
  strategyFor(run.phaseId).stepHooks(run.phaseId)

internal fun PhaseAttemptEnvironment.stepState(run: PhaseRun): PhaseStepState = runState.step(run)

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
