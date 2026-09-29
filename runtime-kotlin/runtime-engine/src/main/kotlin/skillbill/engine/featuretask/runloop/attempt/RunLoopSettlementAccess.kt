package skillbill.engine.featuretask.runloop.attempt

import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopProgressObservations
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext

@Deprecated(
  message = "Use settlementCoupling() for progress, session, and transition owner access.",
  replaceWith = ReplaceWith("settlementCoupling().progress"),
)
internal fun runLoopCoupledProgressForSettlement(
  context: PhaseOutputSettlementContext,
): FeatureTaskRuntimeRunLoopProgressObservations = context.settlementCoupling().progress
