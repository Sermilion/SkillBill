package skillbill.engine.featuretask.runloop.attempt

import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopProgressObservations
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopTransitionOwner
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext

internal data class RunLoopSettlementCoupling(
  val progress: FeatureTaskRuntimeRunLoopProgressObservations,
  val session: FeatureTaskRuntimeRunLoopSessionObservations,
  val sessionObservations: FeatureTaskRuntimeRunLoopSessionObservations,
  val transitions: FeatureTaskRuntimeRunLoopTransitionOwner,
)

internal fun PhaseOutputSettlementContext.settlementCoupling(): RunLoopSettlementCoupling =
  RunLoopSettlementCoupling(progress, session, session, coupledRunTransitions)

internal fun PhaseCheckpointRemediationContext.remediationCoupling(): RunLoopSettlementCoupling =
  RunLoopSettlementCoupling(progress, session, session, coupledRunTransitions)

internal fun PhaseOutputSettlementContext.phaseAttemptContext(
  run: PhaseRun,
  iteration: Int,
  observability: FeatureTaskRuntimeRunObservability,
  outputGateFailuresBefore: Int? = null,
): PhaseAttemptContext =
  PhaseAttemptContext(
    run = run,
    loopTransitions = coupledRunTransitions,
    transitionDeclaration = transitionDeclaration,
    state = progress,
    session = session,
    iteration = iteration,
    observability = observability,
    outputGateFailuresBefore = outputGateFailuresBefore,
  )
