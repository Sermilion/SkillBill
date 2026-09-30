package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.PhaseAttemptAccumulatorContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptLoopState
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.slot.PhaseStepDescription
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead

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

internal class GateOutput(
  val run: PhaseRun,
  val iteration: Int,
  val captured: CapturedPhaseOutput,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val settledEnvelope: PhaseSettledEnvelopeRead,
  val call: PhaseStepCall,
  val outputGateFailuresBefore: Int? = null,
  val settlementContext: PhaseOutputSettlementContext,
  val stepHooks: PhaseStepHooks,
) {
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
