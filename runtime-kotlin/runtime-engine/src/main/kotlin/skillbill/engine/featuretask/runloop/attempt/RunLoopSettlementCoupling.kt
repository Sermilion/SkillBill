package skillbill.engine.featuretask.runloop.attempt

import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext

internal data class RunLoopSettlementCoupling(
  val progress: FeatureTaskRuntimeProgressSnapshotAccess,
  val session: FeatureTaskRuntimeRunSessionObservations,
  val sessionObservations: FeatureTaskRuntimeRunSessionObservations,
  val transitions: FeatureTaskRuntimeRunTransitionOwner,
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
